// Package discovery advertises PhoneDrop v2 over LAN via _phonedrop._tcp.local.
// using Avahi when available on Ubuntu, with a minimal pure-Go mDNS responder fallback.
package discovery

import (
	"context"
	"encoding/binary"
	"fmt"
	"net"
	"os"
	"os/exec"
	"strings"
	"time"
)

const ServiceType = "_phonedrop._tcp"

// GetLocalIPv4s returns non-loopback local IPv4 addresses sorted by LAN preference.
func GetLocalIPv4s() []string {
	ifaces, err := net.Interfaces()
	if err != nil {
		return nil
	}
	var out []string
	for _, iface := range ifaces {
		if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, err := iface.Addrs()
		if err != nil {
			continue
		}
		for _, a := range addrs {
			ipNet, ok := a.(*net.IPNet)
			if !ok || ipNet.IP == nil {
				continue
			}
			v4 := ipNet.IP.To4()
			if v4 == nil || v4.IsLoopback() {
				continue
			}
			out = append(out, v4.String())
		}
	}
	return out
}

// Advertise announces _phonedrop._tcp on port using avahi-publish-service if present,
// plus a lightweight pure-Go UDP multicast mDNS PTR/SRV/TXT/A responder fallback.
func Advertise(ctx context.Context, instanceName string, port int) {
	if instanceName == "" {
		hn, err := os.Hostname()
		if err != nil || hn == "" {
			hn = "ubuntu-desktop"
		}
		instanceName = "PhoneDrop-" + hn
	}
	if port <= 0 {
		port = 28200
	}

	if path, err := exec.LookPath("avahi-publish-service"); err == nil && path != "" {
		go runAvahiPublish(ctx, path, instanceName, port)
	}
	go runPureGoMDNS(ctx, instanceName, port)
}

func runAvahiPublish(ctx context.Context, bin, instanceName string, port int) {
	for ctx.Err() == nil {
		cmd := exec.CommandContext(ctx, bin, instanceName, ServiceType, fmt.Sprintf("%d", port), "proto=pdp2", "v=2")
		_ = cmd.Run()
		if ctx.Err() != nil {
			return
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(3 * time.Second):
		}
	}
}

func runPureGoMDNS(ctx context.Context, instanceName string, port int) {
	maddr := &net.UDPAddr{IP: net.ParseIP("224.0.0.251"), Port: 5353}
	conn, err := net.ListenMulticastUDP("udp4", nil, maddr)
	if err != nil {
		return
	}
	defer conn.Close()

	go func() {
		<-ctx.Done()
		_ = conn.Close()
	}()

	hn, _ := os.Hostname()
	if hn == "" {
		hn = "phonedrop"
	}
	hostTarget := strings.TrimSuffix(hn, ".local") + ".local."
	svcFull := ServiceType + ".local."
	instFull := instanceName + "." + svcFull

	announce := func() {
		ips := GetLocalIPv4s()
		if len(ips) == 0 {
			return
		}
		ip4 := net.ParseIP(ips[0]).To4()
		if ip4 == nil {
			return
		}
		pkt := buildMDNSResponse(svcFull, instFull, hostTarget, port, ip4)
		_, _ = conn.WriteToUDP(pkt, maddr)
	}

	announce()
	buf := make([]byte, 1500)
	ticker := time.NewTicker(20 * time.Second)
	defer ticker.Stop()

	go func() {
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				announce()
			}
		}
	}()

	for {
		n, _, err := conn.ReadFromUDP(buf)
		if err != nil {
			return
		}
		if n < 12 {
			continue
		}
		flags := binary.BigEndian.Uint16(buf[2:4])
		// Only respond to queries (QR bit == 0) mentioning _phonedrop._tcp
		if flags&0x8000 == 0 && strings.Contains(string(buf[:n]), "_phonedrop") {
			announce()
		}
	}
}

func encodeDNSName(name string) []byte {
	name = strings.Trim(name, ".")
	var out []byte
	for _, label := range strings.Split(name, ".") {
		if len(label) == 0 || len(label) > 63 {
			continue
		}
		out = append(out, byte(len(label)))
		out = append(out, label...)
	}
	out = append(out, 0)
	return out
}

func buildMDNSResponse(svcFull, instFull, hostTarget string, port int, ip4 net.IP) []byte {
	var pkt []byte
	// DNS Header: ID=0, Flags=0x8400 (Response+Authoritative), QD=0, AN=4, NS=0, AR=0
	hdr := make([]byte, 12)
	binary.BigEndian.PutUint16(hdr[2:4], 0x8400)
	binary.BigEndian.PutUint16(hdr[6:8], 4)
	pkt = append(pkt, hdr...)

	appendRR := func(name string, rrType uint16, ttl uint32, rdata []byte) {
		pkt = append(pkt, encodeDNSName(name)...)
		var meta [10]byte
		binary.BigEndian.PutUint16(meta[0:2], rrType)
		binary.BigEndian.PutUint16(meta[2:4], 0x0001) // IN class
		binary.BigEndian.PutUint32(meta[4:8], ttl)
		binary.BigEndian.PutUint16(meta[8:10], uint16(len(rdata)))
		pkt = append(pkt, meta[:]...)
		pkt = append(pkt, rdata...)
	}

	// 1. PTR _phonedrop._tcp.local. -> <instance>._phonedrop._tcp.local.
	appendRR(svcFull, 12, 120, encodeDNSName(instFull))

	// 2. SRV <instance>._phonedrop._tcp.local. -> port + hostTarget
	var srv []byte
	var sp [6]byte
	binary.BigEndian.PutUint16(sp[4:6], uint16(port))
	srv = append(srv, sp[:]...)
	srv = append(srv, encodeDNSName(hostTarget)...)
	appendRR(instFull, 33, 120, srv)

	// 3. TXT proto=pdp2
	txt := []byte{10, 'p', 'r', 'o', 't', 'o', '=', 'p', 'd', 'p', '2'}
	appendRR(instFull, 16, 120, txt)

	// 4. A <host>.local. -> ip4
	appendRR(hostTarget, 1, 120, ip4[:4])
	return pkt
}
