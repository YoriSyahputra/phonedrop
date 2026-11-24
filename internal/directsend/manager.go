// Package directsend mengelola pengiriman file tanpa klik (Zero-Click Direct Send)
// dari Laptop ke HP Android melalui tiga jalur:
//  1. Native Android Background Service (SSE stream /api/direct-send/events)
//  2. ADB Push langsung ke /sdcard/Download/PhoneDrop (jika ADB terhubung)
//  3. Fallback PWA Browser Auto-Download via WebSocket
package directsend

import (
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// Event adalah pesan JSON yang dikirim ke klien Direct Send (APK maupun antarmuka web).
type Event struct {
	Type      string   `json:"type"`            // "file", "ping", "connected"
	Files     []string `json:"files,omitempty"` // Daftar nama file di dropDir
	SenderID  string   `json:"sender_id,omitempty"`
	Timestamp int64    `json:"timestamp"`
}

type pendingItem struct {
	file      string
	createdAt time.Time
}

// Manager mengelola koneksi SSE dari APK Android, antrean tertunda, serta fallback ADB/WS.
type Manager struct {
	mu            sync.Mutex
	dropDir       string
	subscribers   map[chan Event]string // chan -> device name
	pending       []pendingItem
	onStatusChg   func(apkClients int)
	onWSBroadcast func(files []string, senderID string)
}

const pendingTTL = 15 * time.Minute

func NewManager(dropDir string, onStatusChg func(apkClients int), onWSBroadcast func(files []string, senderID string)) *Manager {
	return &Manager{
		dropDir:       dropDir,
		subscribers:   make(map[chan Event]string),
		onStatusChg:   onStatusChg,
		onWSBroadcast: onWSBroadcast,
	}
}

// APKClientCount mengembalikan jumlah perangkat APK Android yang sedang mendengarkan di background.
func (m *Manager) APKClientCount() int {
	m.mu.Lock()
	defer m.mu.Unlock()
	return len(m.subscribers)
}

// PushFiles mendorong satu atau beberapa file ke HP secara instan (Zero-Click).
// Mengembalikan metode pengiriman yang digunakan ("apk", "adb", "pwa", atau "queued").
func (m *Manager) PushFiles(files []string, senderID string) string {
	var clean []string
	for _, f := range files {
		name := filepath.Base(strings.TrimSpace(f))
		if name != "" && name != "." && name != "/" {
			clean = append(clean, name)
		}
	}
	if len(clean) == 0 {
		return "empty"
	}

	m.mu.Lock()
	numAPK := len(m.subscribers)
	if numAPK > 0 {
		ev := Event{
			Type:      "file",
			Files:     clean,
			SenderID:  senderID,
			Timestamp: time.Now().UnixMilli(),
		}
		for ch := range m.subscribers {
			select {
			case ch <- ev:
			default:
			}
		}
		m.mu.Unlock()
		log.Printf("[DIRECT-SEND] %d file dikirim ke %d APK background service: %v", len(clean), numAPK, clean)
		return "apk"
	}
	m.mu.Unlock()

	// Jalur 2: Cek apakah ada HP yang terhubung via ADB (USB / Wireless Debugging)
	if m.tryADBPush(clean) {
		log.Printf("[DIRECT-SEND] %d file dikirim langsung via ADB ke /sdcard/Download/PhoneDrop: %v", len(clean), clean)
		return "adb"
	}

	// Jalur 3: Broadcast via WebSocket ke PWA yang sedang terbuka + simpan di antrean
	// jika APK / PWA baru dibuka beberapa saat kemudian.
	m.enqueuePending(clean)
	if m.onWSBroadcast != nil {
		m.onWSBroadcast(clean, senderID)
	}
	log.Printf("[DIRECT-SEND] %d file dikirim via PWA WebSocket & antrean siaga: %v", len(clean), clean)
	return "pwa"
}

func (m *Manager) enqueuePending(files []string) {
	m.mu.Lock()
	defer m.mu.Unlock()

	now := time.Now()
	// Bersihkan item kedaluwarsa
	var active []pendingItem
	for _, item := range m.pending {
		if now.Sub(item.createdAt) < pendingTTL {
			active = append(active, item)
		}
	}

	for _, f := range files {
		exists := false
		for _, item := range active {
			if item.file == f {
				exists = true
				break
			}
		}
		if !exists {
			active = append(active, pendingItem{file: f, createdAt: now})
		}
	}
	if len(active) > 25 {
		active = active[len(active)-25:]
	}
	m.pending = active
}

// DrainPending mengambil dan mengosongkan daftar file yang belum sempat diterima HP.
func (m *Manager) DrainPending() []string {
	m.mu.Lock()
	defer m.mu.Unlock()

	now := time.Now()
	var out []string
	for _, item := range m.pending {
		if now.Sub(item.createdAt) < pendingTTL {
			out = append(out, item.file)
		}
	}
	m.pending = nil
	return out
}

// ClearPending menghapus antrean tertunda (misal ketika PWA sudah mengunduhnya).
func (m *Manager) ClearPending(files []string) {
	m.mu.Lock()
	defer m.mu.Unlock()

	if len(files) == 0 {
		m.pending = nil
		return
	}
	removeSet := make(map[string]bool, len(files))
	for _, f := range files {
		removeSet[filepath.Base(f)] = true
	}
	var kept []pendingItem
	for _, item := range m.pending {
		if !removeSet[item.file] {
			kept = append(kept, item)
		}
	}
	m.pending = kept
}

// HandleEvents melayani koneksi streaming HTTP (NDJSON / SSE) untuk APK Android Background Service.
func (m *Manager) HandleEvents(w http.ResponseWriter, r *http.Request) {
	flusher, ok := w.(http.Flusher)
	if !ok {
		http.Error(w, "Streaming tidak didukung", http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/x-ndjson; charset=utf-8")
	w.Header().Set("Cache-Control", "no-cache, no-store")
	w.Header().Set("Connection", "keep-alive")
	w.Header().Set("X-Accel-Buffering", "no")

	clientName := r.URL.Query().Get("device")
	if clientName == "" {
		clientName = r.RemoteAddr
	}

	ch := make(chan Event, 16)
	m.mu.Lock()
	m.subscribers[ch] = clientName
	count := len(m.subscribers)
	m.mu.Unlock()

	log.Printf("[DIRECT-SEND] APK Background Service terhubung (%s) — total aktif: %d", clientName, count)
	if m.onStatusChg != nil {
		m.onStatusChg(count)
	}

	defer func() {
		m.mu.Lock()
		delete(m.subscribers, ch)
		rem := len(m.subscribers)
		m.mu.Unlock()
		log.Printf("[DIRECT-SEND] APK Background Service terputus (%s) — sisa aktif: %d", clientName, rem)
		if m.onStatusChg != nil {
			m.onStatusChg(rem)
		}
	}()

	// Kirim sapaan awal
	_ = writeEventLine(w, flusher, Event{
		Type:      "connected",
		Timestamp: time.Now().UnixMilli(),
	})

	// Jika ada file tertunda di antrean, langsung kirimkan sekarang!
	if pending := m.DrainPending(); len(pending) > 0 {
		_ = writeEventLine(w, flusher, Event{
			Type:      "file",
			Files:     pending,
			Timestamp: time.Now().UnixMilli(),
		})
	}

	ticker := time.NewTicker(20 * time.Second)
	defer ticker.Stop()

	ctx := r.Context()
	for {
		select {
		case <-ctx.Done():
			return
		case ev := <-ch:
			if err := writeEventLine(w, flusher, ev); err != nil {
				return
			}
		case <-ticker.C:
			if err := writeEventLine(w, flusher, Event{
				Type:      "ping",
				Timestamp: time.Now().UnixMilli(),
			}); err != nil {
				return
			}
		}
	}
}

func writeEventLine(w http.ResponseWriter, flusher http.Flusher, ev Event) error {
	b, err := json.Marshal(ev)
	if err != nil {
		return err
	}
	if _, err := fmt.Fprintf(w, "%s\n", b); err != nil {
		return err
	}
	flusher.Flush()
	return nil
}

// HandleTrigger menangani permintaan HTTP POST /api/direct-send untuk mendorong file ke HP.
func (m *Manager) HandleTrigger(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Metode tidak diizinkan", http.StatusMethodNotAllowed)
		return
	}

	var files []string
	if name := r.URL.Query().Get("name"); name != "" {
		files = append(files, name)
	}
	if rawList := r.URL.Query().Get("files"); rawList != "" {
		for _, part := range strings.Split(rawList, ",") {
			if strings.TrimSpace(part) != "" {
				files = append(files, strings.TrimSpace(part))
			}
		}
	}
	senderID := r.URL.Query().Get("sender_id")

	if len(files) == 0 {
		http.Error(w, "Parameter nama file kosong", http.StatusBadRequest)
		return
	}

	mode := m.PushFiles(files, senderID)
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{
		"status":      "ok",
		"mode":        mode,
		"files":       files,
		"apk_clients": m.APKClientCount(),
	})
}

// HandleAck dipanggil oleh PWA setelah berhasil mengunduh file Direct Send agar tidak diunduh ulang.
func (m *Manager) HandleAck(w http.ResponseWriter, r *http.Request) {
	var files []string
	if rawList := r.URL.Query().Get("files"); rawList != "" {
		files = strings.Split(rawList, ",")
	}
	m.ClearPending(files)
	w.Header().Set("Content-Type", "application/json")
	_, _ = w.Write([]byte(`{"status":"ok"}`))
}

// HandleStatus mengembalikan status kesiapan Direct Send (jumlah APK terhubung & ADB).
func (m *Manager) HandleStatus(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{
		"apk_clients":   m.APKClientCount(),
		"apk_connected": m.APKClientCount() > 0,
		"adb_connected": hasADBDevice(),
	})
}

func hasADBDevice() bool {
	out, err := exec.Command("adb", "devices").Output()
	if err != nil {
		return false
	}
	lines := strings.Split(strings.TrimSpace(string(out)), "\n")
	for i := 1; i < len(lines); i++ {
		fields := strings.Fields(lines[i])
		if len(fields) >= 2 && fields[1] == "device" {
			return true
		}
	}
	return false
}

func (m *Manager) tryADBPush(files []string) bool {
	if !hasADBDevice() {
		return false
	}
	destDir := "/sdcard/Download/PhoneDrop"
	if err := exec.Command("adb", "shell", "mkdir", "-p", destDir).Run(); err != nil {
		return false
	}
	for _, f := range files {
		localPath := filepath.Join(m.dropDir, filepath.Base(f))
		remotePath := destDir + "/" + filepath.Base(f)
		if err := exec.Command("adb", "push", localPath, remotePath).Run(); err != nil {
			return false
		}
		_ = exec.Command("adb", "shell", "am", "broadcast",
			"-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
			"-d", "file://"+remotePath).Run()
	}
	return true
}
