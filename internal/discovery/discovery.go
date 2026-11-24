package discovery

import (
	"fmt"
	"net"
	"strings"
)

// GetPrimaryIP mengambil IPv4 lokal utama (misal 192.168.x.x) untuk disematkan ke QR Code.
func GetPrimaryIP() string {
	ips := getLocalIPs()
	if len(ips) > 0 {
		return ips[0]
	}
	return "localhost"
}

// GetLocalIPs mengembalikan semua IPv4 LAN aktif (tanpa loopback / docker).
func GetLocalIPs() []string {
	return getLocalIPs()
}

// PrintBanner mencetak info server. tlsPort kosong berarti mode HTTPS nonaktif.
func PrintBanner(port string, tlsPort string, dropDir string, clipBackend string) {
	ips := getLocalIPs()

	fmt.Println("========================================================")
	fmt.Println("  PhoneDrop - Local Sync Utility (Lightweight Edition)  ")
	fmt.Println("========================================================")
	fmt.Printf("  Folder Tujuan : %s\n", dropDir)
	fmt.Printf("  Clipboard     : %s\n", clipBackend)
	fmt.Println("--------------------------------------------------------")
	fmt.Println("  Buka salah satu URL berikut di browser Laptop / HP:")
	if len(ips) == 0 {
		fmt.Printf("  -> http://localhost:%s (Wi-Fi belum terhubung)\n", port)
	} else {
		for _, ip := range ips {
			if tlsPort != "" {
				fmt.Printf("  -> https://%s:%s   (HTTPS / PWA)\n", ip, tlsPort)
			}
			fmt.Printf("  -> http://%s:%s\n", ip, port)
		}
		if tlsPort != "" {
			fmt.Println("--------------------------------------------------------")
			fmt.Println("  Sertifikat CA (install sekali di HP):")
			fmt.Printf("  -> http://%s:%s/phonedrop-ca.crt\n", ips[0], port)
		}
	}
	fmt.Println("========================================================")
}

func getLocalIPs() []string {
	var ips []string
	interfaces, err := net.Interfaces()
	if err != nil {
		return ips
	}

	for _, iface := range interfaces {
		if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 {
			continue
		}
		if strings.HasPrefix(iface.Name, "docker") || strings.HasPrefix(iface.Name, "veth") || strings.HasPrefix(iface.Name, "br-") || strings.HasPrefix(iface.Name, "tailscale") || strings.HasPrefix(iface.Name, "wg") {
			continue
		}

		addrs, err := iface.Addrs()
		if err != nil {
			continue
		}

		for _, addr := range addrs {
			var ip net.IP
			switch v := addr.(type) {
			case *net.IPNet:
				ip = v.IP
			case *net.IPAddr:
				ip = v.IP
			}

			if ip == nil || ip.IsLoopback() {
				continue
			}
			ip = ip.To4()
			if ip == nil {
				continue
			}
			ips = append(ips, ip.String())
		}
	}
	return ips
}
