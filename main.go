package main

import (
	"context"
	"crypto/tls"
	"embed"
	"flag"
	"fmt"
	"io/fs"
	"log"
	"mime"
	"net"
	"net/http"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"
	"time"

	"phonedrop/internal/clipboard"
	"phonedrop/internal/directsend"
	"phonedrop/internal/discovery"
	"phonedrop/internal/files"
	"phonedrop/internal/input"
	"phonedrop/internal/tlscert"
	"phonedrop/internal/ws"
)

//go:embed web/*
var embeddedWeb embed.FS

func main() {
	_ = mime.AddExtensionType(".webmanifest", "application/manifest+json")
	_ = mime.AddExtensionType(".svg", "image/svg+xml")
	_ = mime.AddExtensionType(".js", "application/javascript")
	_ = mime.AddExtensionType(".apk", "application/vnd.android.package-archive")

	port := flag.String("port", "8080", "Port HTTP server")
	tlsPort := flag.String("tls-port", "8443", "Port HTTPS server (PWA / secure context)")
	noTLS := flag.Bool("no-tls", false, "Nonaktifkan server HTTPS")
	flag.Parse()

	homeDir, err := os.UserHomeDir()
	if err != nil {
		log.Fatalf("Gagal mendeteksi direktori home: %v", err)
	}
	dropDir := filepath.Join(homeDir, "Downloads", "PhoneDrop")

	clipMgr := clipboard.NewManager()
	inputCtrl := input.NewController()

	hub := ws.NewHub(
		func(incomingText string) {
			if err := clipMgr.Set(incomingText); err != nil {
				log.Printf("[CLIP] Error: %v", err)
			}
		},
		func(ev input.EventPayload) {
			inputCtrl.Execute(ev)
		},
		func() string {
			return clipMgr.Get()
		},
	)
	go hub.Run()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	go clipMgr.Watch(ctx, 800*time.Millisecond, func(newText string) {
		log.Printf("[CLIPBOARD] Perubahan terdeteksi di Laptop (%d karakter)", len(newText))
		hub.BroadcastClipboard(newText)
	})

	fileHandler, err := files.NewHandler(dropDir, *port, func() {
		hub.NotifyFilesUpdated()
	})
	if err != nil {
		log.Fatalf("Gagal menyiapkan folder tujuan %s: %v", dropDir, err)
	}

	directMgr := directsend.NewManager(
		dropDir,
		func(apkClients int) {
			hub.BroadcastDirectStatus(apkClients)
		},
		func(targetFiles []string, senderID string) {
			hub.BroadcastDirectSend(targetFiles, senderID)
		},
	)
	fileHandler.SetDirectSender(func(targetFiles []string, senderID string) string {
		return directMgr.PushFiles(targetFiles, senderID)
	})

	// Konten dari menu "Bagikan" Android: teks → clipboard laptop, + notifikasi desktop.
	fileHandler.SetShareReceiver(func(savedFiles []string, text string) {
		var body string
		if len(savedFiles) > 0 {
			log.Printf("[SHARE] Menerima %d file dari HP", len(savedFiles))
			body = fmt.Sprintf("%d file diterima di ~/Downloads/PhoneDrop", len(savedFiles))
			if len(savedFiles) == 1 {
				body = savedFiles[0]
			}
		}
		if text != "" {
			log.Printf("[SHARE] Menerima teks dari HP (%d karakter)", len(text))
			if err := clipMgr.Set(text); err != nil {
				log.Printf("[CLIP] Error: %v", err)
			}
			hub.BroadcastClipboard(text)
			if body == "" {
				body = "Teks disalin ke clipboard laptop"
			}
		}
		go func() {
			_ = exec.Command("notify-send", "-a", "PhoneDrop", "-i", "phonedrop", "PhoneDrop · Dibagikan dari HP", body).Run()
		}()
	})

	mux := http.NewServeMux()
	mux.HandleFunc("/ws", hub.ServeWS)
	mux.HandleFunc("/api/upload", fileHandler.HandleUpload)
	mux.HandleFunc("/api/files", fileHandler.HandleList)
	mux.HandleFunc("/api/download", fileHandler.HandleDownload)
	mux.HandleFunc("/api/qr", fileHandler.HandleQR)
	mux.HandleFunc("/api/zip", fileHandler.HandleZip)
	mux.HandleFunc("/api/qr-zip", fileHandler.HandleZipQR)
	mux.HandleFunc("/api/qr-apk", fileHandler.HandleAPKQR)
	mux.HandleFunc("/api/delete", fileHandler.HandleDelete)
	mux.HandleFunc("/api/direct-send", directMgr.HandleTrigger)
	mux.HandleFunc("/api/direct-send/events", directMgr.HandleEvents)
	mux.HandleFunc("/api/direct-send/status", directMgr.HandleStatus)
	mux.HandleFunc("/api/direct-send/ack", directMgr.HandleAck)
	mux.HandleFunc("/share-target", fileHandler.HandleShareTarget)

	webSubFS, err := fs.Sub(embeddedWeb, "web")
	if err != nil {
		log.Fatalf("Gagal memuat embedded web assets: %v", err)
	}
	mux.Handle("/", http.FileServer(http.FS(webSubFS)))

	// --- HTTPS (TLS) dengan CA lokal ---
	var tlsServer *http.Server
	activeTLSPort := ""
	if !*noTLS {
		hosts := append([]string{"localhost", "127.0.0.1"}, discovery.GetLocalIPs()...)
		if hn, err := os.Hostname(); err == nil && hn != "" {
			hosts = append(hosts, hn, hn+".local")
		}
		bundle, err := tlscert.Ensure(filepath.Join(homeDir, ".config", "phonedrop", "tls"), hosts)
		if err != nil {
			log.Printf("[TLS] Gagal menyiapkan sertifikat, HTTPS dinonaktifkan: %v", err)
		} else {
			if bundle.Renewed {
				log.Printf("[TLS] Sertifikat server dibuat/diperbarui di %s", bundle.Dir)
			}
			// Unduhan CA tersedia di HTTP & HTTPS agar HP bisa install sebelum percaya HTTPS.
			mux.HandleFunc("/phonedrop-ca.crt", func(w http.ResponseWriter, r *http.Request) {
				w.Header().Set("Content-Type", "application/x-x509-ca-cert")
				w.Header().Set("Content-Disposition", `attachment; filename="PhoneDrop-CA.crt"`)
				_, _ = w.Write(bundle.CADER)
			})
			tlsServer = &http.Server{
				Addr:              ":" + *tlsPort,
				Handler:           mux,
				ReadHeaderTimeout: 10 * time.Second,
				TLSConfig: &tls.Config{
					MinVersion:   tls.VersionTLS12,
					Certificates: []tls.Certificate{bundle.Certificate},
				},
			}
			activeTLSPort = *tlsPort
		}
	}

	server := &http.Server{
		Handler:           mux,
		ReadHeaderTimeout: 10 * time.Second,
	}

	httpListeners, err := createListeners(*port, nil)
	if err != nil {
		log.Fatalf("Server HTTP gagal dijalankan: %v", err)
	}

	var tlsListeners []net.Listener
	if tlsServer != nil {
		tlsListeners, err = createListeners(*tlsPort, tlsServer.TLSConfig)
		if err != nil {
			log.Printf("[TLS] Gagal menjalankan server HTTPS: %v", err)
			tlsServer = nil
			activeTLSPort = ""
		}
	}

	discovery.PrintBanner(*port, activeTLSPort, dropDir, clipMgr.BackendName())

	for _, ln := range httpListeners {
		go func(l net.Listener) {
			if err := server.Serve(l); err != nil && err != http.ErrServerClosed {
				log.Printf("[HTTP] Listener %s berhenti: %v", l.Addr(), err)
			}
		}(ln)
	}

	if tlsServer != nil {
		for _, ln := range tlsListeners {
			go func(l net.Listener) {
				if err := tlsServer.Serve(l); err != nil && err != http.ErrServerClosed {
					log.Printf("[HTTPS] Listener %s berhenti: %v", l.Addr(), err)
				}
			}(ln)
		}
	}

	stopChan := make(chan os.Signal, 1)
	signal.Notify(stopChan, os.Interrupt, syscall.SIGTERM)
	<-stopChan

	log.Println("\nMematikan server PhoneDrop...")
	cancel()
	shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer shutdownCancel()
	_ = server.Shutdown(shutdownCtx)
	if tlsServer != nil {
		_ = tlsServer.Shutdown(shutdownCtx)
	}
}

func createListeners(port string, tlsCfg *tls.Config) ([]net.Listener, error) {
	wildcardLn, err := net.Listen("tcp", ":"+port)
	if err == nil {
		if tlsCfg != nil {
			return []net.Listener{tls.NewListener(wildcardLn, tlsCfg)}, nil
		}
		return []net.Listener{wildcardLn}, nil
	}

	var targets []string
	seen := make(map[string]bool)
	addTarget := func(ip string) {
		if ip != "" && !seen[ip] {
			seen[ip] = true
			targets = append(targets, ip)
		}
	}

	addTarget("127.0.0.1")
	for _, ip := range discovery.GetLocalIPs() {
		addTarget(ip)
	}

	var listeners []net.Listener
	var boundAddrs []string
	for _, ip := range targets {
		addr := net.JoinHostPort(ip, port)
		ln, lnErr := net.Listen("tcp", addr)
		if lnErr != nil {
			continue
		}
		if tlsCfg != nil {
			listeners = append(listeners, tls.NewListener(ln, tlsCfg))
		} else {
			listeners = append(listeners, ln)
		}
		boundAddrs = append(boundAddrs, addr)
	}

	if len(listeners) == 0 {
		return nil, fmt.Errorf("gagal bind ke :%s maupun interface spesifik (error: %w)", port, err)
	}

	log.Printf("[NET] Wildcard :%s tidak dapat dipakai (%v), aktif pada interface: %s", port, err, strings.Join(boundAddrs, ", "))
	return listeners, nil
}
