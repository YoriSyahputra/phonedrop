package files

import (
	"archive/zip"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"

	"phonedrop/internal/discovery"

	"github.com/skip2/go-qrcode"
)

type FileInfo struct {
	Name    string `json:"name"`
	Size    int64  `json:"size"`
	ModTime int64  `json:"mod_time"`
}

type Handler struct {
	dropDir      string
	port         string
	onFileAdded  func()
	onShared     func(files []string, text string)
	onDirectSend func(files []string, senderID string) string
}

func NewHandler(dropDir string, port string, onFileAdded func()) (*Handler, error) {
	if err := os.MkdirAll(dropDir, 0755); err != nil {
		return nil, err
	}
	return &Handler{
		dropDir:     dropDir,
		port:        port,
		onFileAdded: onFileAdded,
	}, nil
}

// SetShareReceiver mendaftarkan callback saat konten diterima dari menu "Bagikan" Android.
func (h *Handler) SetShareReceiver(fn func(files []string, text string)) {
	h.onShared = fn
}

// SetDirectSender mendaftarkan callback untuk mendorong file langsung ke HP (Direct Send).
func (h *Handler) SetDirectSender(fn func(files []string, senderID string) string) {
	h.onDirectSend = fn
}

// HandleShareTarget menerima POST dari Web Share Target API (menu "Bagikan" di HP).
// File di-stream langsung ke disk; teks/URL diteruskan ke clipboard laptop.
// Setelah selesai, browser diarahkan kembali ke UI dengan status hasil.
func (h *Handler) HandleShareTarget(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Redirect(w, r, "/", http.StatusSeeOther)
		return
	}

	reader, err := r.MultipartReader()
	if err != nil {
		http.Redirect(w, r, "/?shared=error", http.StatusSeeOther)
		return
	}

	var savedFiles []string
	var title, text, link string

	for {
		part, err := reader.NextPart()
		if err == io.EOF {
			break
		}
		if err != nil {
			log.Printf("[SHARE] Gagal membaca aliran: %v", err)
			http.Redirect(w, r, "/?shared=error", http.StatusSeeOther)
			return
		}

		if part.FileName() == "" {
			// Field teks kecil (title / text / url) — dibatasi 64 KB agar hemat RAM.
			val, _ := io.ReadAll(io.LimitReader(part, 64<<10))
			switch part.FormName() {
			case "title":
				title = strings.TrimSpace(string(val))
			case "text":
				text = strings.TrimSpace(string(val))
			case "url":
				link = strings.TrimSpace(string(val))
			}
			part.Close()
			continue
		}

		name, err := h.saveStream(part.FileName(), part)
		part.Close()
		if err != nil {
			log.Printf("[SHARE] Gagal menyimpan %s: %v", part.FileName(), err)
			http.Redirect(w, r, "/?shared=error", http.StatusSeeOther)
			return
		}
		savedFiles = append(savedFiles, name)
	}

	// Gabungkan teks: banyak aplikasi mengirim URL di field "text", hindari duplikasi.
	sharedText := text
	if link != "" && !strings.Contains(sharedText, link) {
		if sharedText != "" {
			sharedText += "\n"
		}
		sharedText += link
	}
	if sharedText == "" && len(savedFiles) == 0 {
		sharedText = title
	}

	if len(savedFiles) > 0 && h.onFileAdded != nil {
		h.onFileAdded()
	}
	if h.onShared != nil && (len(savedFiles) > 0 || sharedText != "") {
		h.onShared(savedFiles, sharedText)
	}

	q := url.Values{}
	switch {
	case len(savedFiles) > 0:
		q.Set("shared", "files")
		q.Set("count", fmt.Sprint(len(savedFiles)))
	case sharedText != "":
		q.Set("shared", "text")
	default:
		q.Set("shared", "empty")
	}
	http.Redirect(w, r, "/?"+q.Encode(), http.StatusSeeOther)
}

// saveStream menyimpan aliran data ke dropDir dengan nama unik; file parsial dihapus jika gagal.
func (h *Handler) saveStream(rawName string, src io.Reader) (string, error) {
	cleanName := filepath.Base(rawName)
	if cleanName == "." || cleanName == "/" || cleanName == "" {
		cleanName = "shared-file"
	}
	destPath := h.resolveUniquePath(cleanName)

	dst, err := os.Create(destPath)
	if err != nil {
		return "", err
	}
	_, copyErr := io.Copy(dst, src)
	closeErr := dst.Close()
	if copyErr != nil || closeErr != nil {
		_ = os.Remove(destPath)
		if copyErr != nil {
			return "", copyErr
		}
		return "", closeErr
	}
	log.Printf("[FILE] Tersimpan: %s", destPath)
	return filepath.Base(destPath), nil
}

func (h *Handler) HandleUpload(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Metode tidak diizinkan", http.StatusMethodNotAllowed)
		return
	}

	reader, err := r.MultipartReader()
	if err != nil {
		http.Error(w, "Format multipart tidak valid", http.StatusBadRequest)
		return
	}

	var savedFiles []string

	for {
		part, err := reader.NextPart()
		if err == io.EOF {
			break
		}
		if err != nil {
			http.Error(w, "Gagal membaca aliran file", http.StatusInternalServerError)
			return
		}

		if part.FileName() == "" {
			part.Close()
			continue
		}

		cleanName := filepath.Base(part.FileName())
		destPath := h.resolveUniquePath(cleanName)

		dst, err := os.Create(destPath)
		if err != nil {
			part.Close()
			http.Error(w, "Gagal membuat file di disk", http.StatusInternalServerError)
			return
		}

		_, copyErr := io.Copy(dst, part)
		dst.Close()
		part.Close()

		if copyErr != nil {
			http.Error(w, "Gagal menyimpan file", http.StatusInternalServerError)
			return
		}

		finalName := filepath.Base(destPath)
		savedFiles = append(savedFiles, finalName)
		log.Printf("[FILE] Tersimpan: %s", destPath)
	}

	if h.onFileAdded != nil && len(savedFiles) != 0 {
		h.onFileAdded()
	}

	directMode := ""
	if r.URL.Query().Get("direct") == "1" && h.onDirectSend != nil && len(savedFiles) > 0 {
		directMode = h.onDirectSend(savedFiles, r.URL.Query().Get("sender_id"))
	}

	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{
		"status":      "ok",
		"files":       savedFiles,
		"direct_mode": directMode,
	})
}

func (h *Handler) HandleList(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "Metode tidak diizinkan", http.StatusMethodNotAllowed)
		return
	}

	entries, err := os.ReadDir(h.dropDir)
	if err != nil {
		http.Error(w, "Gagal membaca direktori", http.StatusInternalServerError)
		return
	}

	var list []FileInfo
	for _, entry := range entries {
		if entry.IsDir() || strings.HasPrefix(entry.Name(), ".") {
			continue
		}
		info, err := entry.Info()
		if err != nil {
			continue
		}
		list = append(list, FileInfo{
			Name:    entry.Name(),
			Size:    info.Size(),
			ModTime: info.ModTime().UnixMilli(),
		})
	}

	sort.Slice(list, func(i, j int) bool {
		return list[i].ModTime > list[j].ModTime
	})

	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(list)
}

func (h *Handler) HandleDownload(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "Metode tidak diizinkan", http.StatusMethodNotAllowed)
		return
	}

	rawName := r.URL.Query().Get("name")
	if rawName == "" {
		http.Error(w, "Parameter nama file kosong", http.StatusBadRequest)
		return
	}

	cleanName := filepath.Base(rawName)
	targetPath := filepath.Join(h.dropDir, cleanName)

	info, err := os.Stat(targetPath)
	if err != nil || info.IsDir() {
		http.Error(w, "File tidak ditemukan", http.StatusNotFound)
		return
	}

	encodedName := url.PathEscape(cleanName)
	w.Header().Set("Content-Disposition", fmt.Sprintf("attachment; filename=\"%s\"; filename*=UTF-8''%s", cleanName, encodedName))
	http.ServeFile(w, r, targetPath)
}

// HandleQR membuat gambar PNG QR Code yang langsung mengarah ke link download file.
func (h *Handler) HandleQR(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		http.Error(w, "Metode tidak diizinkan", http.StatusMethodNotAllowed)
		return
	}

	rawName := r.URL.Query().Get("name")
	if rawName == "" {
		http.Error(w, "Parameter nama file kosong", http.StatusBadRequest)
		return
	}

	cleanName := filepath.Base(rawName)
	targetPath := filepath.Join(h.dropDir, cleanName)

	if info, err := os.Stat(targetPath); err != nil || info.IsDir() {
		http.Error(w, "File tidak ditemukan", http.StatusNotFound)
		return
	}

	lanIP := discovery.GetPrimaryIP()
	downloadURL := fmt.Sprintf("http://%s:%s/api/download?name=%s", lanIP, h.port, url.QueryEscape(cleanName))

	pngBytes, err := qrcode.Encode(downloadURL, qrcode.Medium, 256)
	if err != nil {
		http.Error(w, "Gagal membuat QR Code", http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "image/png")
	w.Header().Set("Cache-Control", "no-store")
	if r.Method == http.MethodHead {
		return
	}
	_, _ = w.Write(pngBytes)
}

// HandleZip membuat file .zip secara streaming dari file-file yang dipilih atau seluruh file di dropDir.
// Streaming langsung ke ResponseWriter tanpa buffering seluruh file di memori (hemat RAM < 50MB).
func (h *Handler) HandleZip(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		http.Error(w, "Metode tidak diizinkan", http.StatusMethodNotAllowed)
		return
	}

	targetFiles := h.resolveTargetFiles(r)
	if len(targetFiles) == 0 {
		http.Error(w, "Tidak ada file yang dapat dikompres", http.StatusNotFound)
		return
	}

	zipName := fmt.Sprintf("PhoneDrop_Archive_%s.zip", time.Now().Format("20060102_150405"))
	w.Header().Set("Content-Type", "application/zip")
	w.Header().Set("Content-Disposition", fmt.Sprintf("attachment; filename=\"%s\"", zipName))
	w.Header().Set("Cache-Control", "no-cache, no-store")

	if r.Method == http.MethodHead {
		return
	}

	zipWriter := zip.NewWriter(w)
	defer zipWriter.Close()

	for _, fname := range targetFiles {
		cleanName := filepath.Base(fname)
		filePath := filepath.Join(h.dropDir, cleanName)

		info, err := os.Stat(filePath)
		if err != nil || info.IsDir() {
			continue
		}

		header, err := zip.FileInfoHeader(info)
		if err != nil {
			continue
		}
		header.Name = cleanName
		header.Method = zip.Deflate

		writer, err := zipWriter.CreateHeader(header)
		if err != nil {
			log.Printf("[ZIP] Gagal membuat header file %s: %v", cleanName, err)
			continue
		}

		file, err := os.Open(filePath)
		if err != nil {
			log.Printf("[ZIP] Gagal membuka file %s: %v", cleanName, err)
			continue
		}

		_, copyErr := io.Copy(writer, file)
		_ = file.Close()
		if copyErr != nil {
			log.Printf("[ZIP] Gagal menulis aliran file %s: %v", cleanName, copyErr)
			return
		}
	}
}

// HandleZipQR membuat gambar PNG QR Code untuk mengunduh arsip .zip via LAN.
func (h *Handler) HandleZipQR(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		http.Error(w, "Metode tidak diizinkan", http.StatusMethodNotAllowed)
		return
	}

	targetFiles := h.resolveTargetFiles(r)
	if len(targetFiles) == 0 {
		http.Error(w, "Tidak ada file untuk dibuatkan QR Code", http.StatusNotFound)
		return
	}

	lanIP := discovery.GetPrimaryIP()
	downloadURL := fmt.Sprintf("http://%s:%s/api/zip", lanIP, h.port)

	rawFiles := r.URL.Query().Get("files")
	if rawFiles != "" {
		downloadURL += fmt.Sprintf("?files=%s", url.QueryEscape(rawFiles))
	}

	pngBytes, err := qrcode.Encode(downloadURL, qrcode.Medium, 256)
	if err != nil {
		http.Error(w, "Gagal membuat QR Code ZIP", http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "image/png")
	w.Header().Set("Cache-Control", "no-store")
	if r.Method == http.MethodHead {
		return
	}
	_, _ = w.Write(pngBytes)
}

// HandleAPKQR membuat gambar PNG QR Code untuk mengunduh aplikasi Android pendamping (/phonedrop.apk).
func (h *Handler) HandleAPKQR(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		http.Error(w, "Metode tidak diizinkan", http.StatusMethodNotAllowed)
		return
	}

	lanIP := discovery.GetPrimaryIP()
	apkURL := fmt.Sprintf("http://%s:%s/phonedrop.apk", lanIP, h.port)

	pngBytes, err := qrcode.Encode(apkURL, qrcode.Medium, 256)
	if err != nil {
		http.Error(w, "Gagal membuat QR Code APK", http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "image/png")
	w.Header().Set("Cache-Control", "no-store")
	if r.Method == http.MethodHead {
		return
	}
	_, _ = w.Write(pngBytes)
}

// resolveTargetFiles mengurai nama file dari query URL (param "files" atau "name").
// Jika kosong, defaultnya mengambil semua file yang ada di dropDir.
func (h *Handler) resolveTargetFiles(r *http.Request) []string {
	var targets []string

	if filesParam := r.URL.Query().Get("files"); filesParam != "" {
		for _, part := range strings.Split(filesParam, ",") {
			trimmed := strings.TrimSpace(part)
			if trimmed != "" {
				targets = append(targets, filepath.Base(trimmed))
			}
		}
	} else if names := r.URL.Query()["name"]; len(names) > 0 {
		for _, n := range names {
			trimmed := strings.TrimSpace(n)
			if trimmed != "" {
				targets = append(targets, filepath.Base(trimmed))
			}
		}
	}

	if len(targets) > 0 {
		return targets
	}

	entries, err := os.ReadDir(h.dropDir)
	if err != nil {
		return nil
	}

	for _, entry := range entries {
		if entry.IsDir() || strings.HasPrefix(entry.Name(), ".") {
			continue
		}
		targets = append(targets, entry.Name())
	}
	return targets
}

func (h *Handler) HandleDelete(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodDelete && r.Method != http.MethodPost {
		http.Error(w, "Metode tidak diizinkan", http.StatusMethodNotAllowed)
		return
	}

	rawName := r.URL.Query().Get("name")
	if rawName == "" {
		http.Error(w, "Nama file kosong", http.StatusBadRequest)
		return
	}

	cleanName := filepath.Base(rawName)
	targetPath := filepath.Join(h.dropDir, cleanName)

	if err := os.Remove(targetPath); err != nil {
		http.Error(w, "Gagal menghapus file", http.StatusInternalServerError)
		return
	}

	log.Printf("[FILE] Dihapus: %s", targetPath)
	if h.onFileAdded != nil {
		h.onFileAdded()
	}

	w.Header().Set("Content-Type", "application/json")
	_, _ = w.Write([]byte(`{"status":"deleted"}`))
}

func (h *Handler) resolveUniquePath(filename string) string {
	ext := filepath.Ext(filename)
	base := strings.TrimSuffix(filename, ext)
	candidate := filepath.Join(h.dropDir, filename)

	counter := 1
	for {
		if _, err := os.Stat(candidate); os.IsNotExist(err) {
			return candidate
		}
		newName := fmt.Sprintf("%s_%d%s", base, counter, ext)
		candidate = filepath.Join(h.dropDir, newName)
		counter++
	}
}
