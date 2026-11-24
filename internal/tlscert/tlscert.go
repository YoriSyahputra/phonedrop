// Package tlscert mengelola Certificate Authority (CA) lokal dan sertifikat
// server HTTPS untuk PhoneDrop, tanpa dependensi eksternal (mkcert/openssl).
//
// Alur:
//  1. CA lokal dibuat SEKALI (berlaku 10 tahun) dan disimpan di ~/.config/phonedrop/tls.
//     File CA ini yang di-install sekali di HP agar HTTPS dipercaya browser.
//  2. Sertifikat server ditandatangani CA tersebut dan memuat semua IP LAN aktif.
//     Jika IP laptop berubah / sertifikat mendekati kedaluwarsa, sertifikat server
//     dibuat ulang otomatis — CA tetap sama sehingga HP tidak perlu install ulang.
package tlscert

import (
	"bytes"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"errors"
	"fmt"
	"math/big"
	"net"
	"os"
	"path/filepath"
	"slices"
	"time"
)

const (
	caCertFile     = "ca.pem"
	caKeyFile      = "ca-key.pem"
	serverCertFile = "server.pem"
	serverKeyFile  = "server-key.pem"

	caValidity     = 10 * 365 * 24 * time.Hour
	serverValidity = 397 * 24 * time.Hour // Batas aman validitas sertifikat leaf
	renewBefore    = 30 * 24 * time.Hour
)

// Bundle berisi sertifikat siap pakai untuk server TLS dan CA (DER) untuk diunduh HP.
type Bundle struct {
	Certificate tls.Certificate
	CADER       []byte
	Dir         string
	Renewed     bool
}

// Ensure memastikan CA dan sertifikat server tersedia serta valid untuk host/IP yang diberikan.
func Ensure(dir string, hosts []string) (*Bundle, error) {
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return nil, fmt.Errorf("membuat folder TLS: %w", err)
	}

	caCert, caKey, err := loadOrCreateCA(dir)
	if err != nil {
		return nil, err
	}

	renewed := false
	certPath := filepath.Join(dir, serverCertFile)
	keyPath := filepath.Join(dir, serverKeyFile)

	if !serverCertValid(certPath, caCert, hosts) {
		if err := createServerCert(certPath, keyPath, caCert, caKey, hosts); err != nil {
			return nil, err
		}
		renewed = true
	}

	tlsCert, err := tls.LoadX509KeyPair(certPath, keyPath)
	if err != nil {
		return nil, fmt.Errorf("memuat sertifikat server: %w", err)
	}

	return &Bundle{Certificate: tlsCert, CADER: caCert.Raw, Dir: dir, Renewed: renewed}, nil
}

func loadOrCreateCA(dir string) (*x509.Certificate, *ecdsa.PrivateKey, error) {
	certPath := filepath.Join(dir, caCertFile)
	keyPath := filepath.Join(dir, caKeyFile)

	if cert, key, err := loadPair(certPath, keyPath); err == nil && time.Now().Before(cert.NotAfter) {
		return cert, key, nil
	}

	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return nil, nil, fmt.Errorf("membuat kunci CA: %w", err)
	}

	hostname, _ := os.Hostname()
	tmpl := &x509.Certificate{
		SerialNumber: randomSerial(),
		Subject: pkix.Name{
			CommonName:   fmt.Sprintf("PhoneDrop Local CA (%s)", hostname),
			Organization: []string{"PhoneDrop"},
		},
		NotBefore:             time.Now().Add(-time.Hour),
		NotAfter:              time.Now().Add(caValidity),
		KeyUsage:              x509.KeyUsageCertSign | x509.KeyUsageCRLSign,
		BasicConstraintsValid: true,
		IsCA:                  true,
		MaxPathLenZero:        true,
	}

	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		return nil, nil, fmt.Errorf("membuat sertifikat CA: %w", err)
	}
	if err := writePair(certPath, keyPath, der, key); err != nil {
		return nil, nil, err
	}

	cert, err := x509.ParseCertificate(der)
	if err != nil {
		return nil, nil, err
	}
	return cert, key, nil
}

func createServerCert(certPath, keyPath string, caCert *x509.Certificate, caKey *ecdsa.PrivateKey, hosts []string) error {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return fmt.Errorf("membuat kunci server: %w", err)
	}

	tmpl := &x509.Certificate{
		SerialNumber: randomSerial(),
		Subject: pkix.Name{
			CommonName:   "PhoneDrop Server",
			Organization: []string{"PhoneDrop"},
		},
		NotBefore:   time.Now().Add(-time.Hour),
		NotAfter:    time.Now().Add(serverValidity),
		KeyUsage:    x509.KeyUsageDigitalSignature,
		ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
	}
	for _, h := range hosts {
		if ip := net.ParseIP(h); ip != nil {
			tmpl.IPAddresses = append(tmpl.IPAddresses, ip)
		} else {
			tmpl.DNSNames = append(tmpl.DNSNames, h)
		}
	}

	der, err := x509.CreateCertificate(rand.Reader, tmpl, caCert, &key.PublicKey, caKey)
	if err != nil {
		return fmt.Errorf("menandatangani sertifikat server: %w", err)
	}
	return writePair(certPath, keyPath, der, key)
}

// serverCertValid memeriksa sertifikat server: ditandatangani CA aktif, belum mendekati
// kedaluwarsa, dan mencakup semua host/IP yang dibutuhkan saat ini.
func serverCertValid(certPath string, caCert *x509.Certificate, hosts []string) bool {
	data, err := os.ReadFile(certPath)
	if err != nil {
		return false
	}
	block, _ := pem.Decode(data)
	if block == nil {
		return false
	}
	cert, err := x509.ParseCertificate(block.Bytes)
	if err != nil {
		return false
	}
	if time.Now().Add(renewBefore).After(cert.NotAfter) {
		return false
	}
	if !bytes.Equal(cert.RawIssuer, caCert.RawSubject) || cert.CheckSignatureFrom(caCert) != nil {
		return false
	}
	for _, h := range hosts {
		if ip := net.ParseIP(h); ip != nil {
			if !slices.ContainsFunc(cert.IPAddresses, func(c net.IP) bool { return c.Equal(ip) }) {
				return false
			}
		} else if !slices.Contains(cert.DNSNames, h) {
			return false
		}
	}
	return true
}

func loadPair(certPath, keyPath string) (*x509.Certificate, *ecdsa.PrivateKey, error) {
	certPEM, err := os.ReadFile(certPath)
	if err != nil {
		return nil, nil, err
	}
	keyPEM, err := os.ReadFile(keyPath)
	if err != nil {
		return nil, nil, err
	}
	cb, _ := pem.Decode(certPEM)
	kb, _ := pem.Decode(keyPEM)
	if cb == nil || kb == nil {
		return nil, nil, errors.New("file PEM rusak")
	}
	cert, err := x509.ParseCertificate(cb.Bytes)
	if err != nil {
		return nil, nil, err
	}
	key, err := x509.ParseECPrivateKey(kb.Bytes)
	if err != nil {
		return nil, nil, err
	}
	return cert, key, nil
}

func writePair(certPath, keyPath string, der []byte, key *ecdsa.PrivateKey) error {
	keyDER, err := x509.MarshalECPrivateKey(key)
	if err != nil {
		return err
	}
	if err := os.WriteFile(certPath, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}), 0o644); err != nil {
		return fmt.Errorf("menulis %s: %w", certPath, err)
	}
	if err := os.WriteFile(keyPath, pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: keyDER}), 0o600); err != nil {
		return fmt.Errorf("menulis %s: %w", keyPath, err)
	}
	return nil
}

func randomSerial() *big.Int {
	n, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 127))
	if err != nil {
		return big.NewInt(time.Now().UnixNano())
	}
	return n
}
