package clipboard

import (
	"bytes"
	"context"
	"errors"
	"os"
	"os/exec"
	"strings"
	"sync"
	"time"
)

type BackendType string

const (
	BackendWayland BackendType = "Wayland (wl-clipboard)"
	BackendX11     BackendType = "X11 (xclip)"
	BackendNone    BackendType = "Tidak Terdeteksi (Install wl-clipboard / xclip)"
)

type Manager struct {
	mu          sync.RWMutex
	lastContent string
	backend     BackendType
}

func NewManager() *Manager {
	m := &Manager{
		backend: detectBackend(),
	}
	if initial, err := m.readRaw(); err == nil {
		m.lastContent = initial
	}
	return m
}

func detectBackend() BackendType {
	if os.Getenv("WAYLAND_DISPLAY") != "" {
		if _, err := exec.LookPath("wl-paste"); err == nil {
			if _, err := exec.LookPath("wl-copy"); err == nil {
				return BackendWayland
			}
		}
	}
	if os.Getenv("DISPLAY") != "" {
		if _, err := exec.LookPath("xclip"); err == nil {
			return BackendX11
		}
	}
	// Fallback jika variabel env belum terdeteksi sempurna namun binary tersedia
	if _, err := exec.LookPath("wl-paste"); err == nil {
		return BackendWayland
	}
	if _, err := exec.LookPath("xclip"); err == nil {
		return BackendX11
	}
	return BackendNone
}

func (m *Manager) BackendName() string {
	return string(m.backend)
}

func (m *Manager) readRaw() (string, error) {
	var cmd *exec.Cmd
	switch m.backend {
	case BackendWayland:
		cmd = exec.Command("wl-paste", "--no-newline")
	case BackendX11:
		cmd = exec.Command("xclip", "-selection", "clipboard", "-o")
	default:
		return "", errors.New("clipboard backend tidak tersedia")
	}

	var out bytes.Buffer
	cmd.Stdout = &out
	if err := cmd.Run(); err != nil {
		return "", err
	}
	return out.String(), nil
}

// Get mengambil isi clipboard terakhir yang tercatat di memori.
func (m *Manager) Get() string {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.lastContent
}

// Set menyuntikkan teks baru ke clipboard Linux dan memperbarui state internal.
func (m *Manager) Set(text string) error {
	text = strings.ReplaceAll(text, "\r\n", "\n")

	m.mu.Lock()
	if m.lastContent == text {
		m.mu.Unlock()
		return nil
	}
	m.lastContent = text
	m.mu.Unlock()

	var cmd *exec.Cmd
	switch m.backend {
	case BackendWayland:
		cmd = exec.Command("wl-copy")
	case BackendX11:
		cmd = exec.Command("xclip", "-selection", "clipboard", "-i")
	default:
		return errors.New("clipboard backend tidak tersedia")
	}

	cmd.Stdin = strings.NewReader(text)
	return cmd.Run()
}

// Watch memantau perubahan clipboard Linux secara periodik dan memanggil onChange.
func (m *Manager) Watch(ctx context.Context, interval time.Duration, onChange func(string)) {
	if m.backend == BackendNone {
		return
	}

	ticker := time.NewTicker(interval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			current, err := m.readRaw()
			if err != nil || current == "" {
				continue
			}

			m.mu.Lock()
			changed := current != m.lastContent
			if changed {
				m.lastContent = current
			}
			m.mu.Unlock()

			if changed {
				onChange(current)
			}
		}
	}
}
