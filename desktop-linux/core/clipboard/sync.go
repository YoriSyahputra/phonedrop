// Package clipboard synchronizes Linux desktop clipboard text (X11 xclip or
// Wayland wl-clipboard) over PDP2 PacketTypeClip with SHA-256 origin-hash loop prevention.
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

	"phonedrop/v2/desktop/core/protocol"
)

// Backend identifies the detected Linux display clipboard mechanism.
type Backend string

const (
	BackendWayland Backend = "Wayland (wl-clipboard)"
	BackendX11     Backend = "X11 (xclip)"
	BackendNone    Backend = "None"
)

// Manager watches local clipboard changes and injects remote updates without echo loops.
type Manager struct {
	mu          sync.RWMutex
	backend     Backend
	lastContent string
	recentHash  [8]byte
}

// NewManager detects the active clipboard backend and initializes state.
func NewManager() *Manager {
	m := &Manager{backend: detectBackend()}
	if initial, err := m.readRaw(); err == nil {
		m.lastContent = initial
		m.recentHash = protocol.ComputeClipOriginHash(initial)
	}
	return m
}

func detectBackend() Backend {
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
	if _, err := exec.LookPath("wl-paste"); err == nil {
		return BackendWayland
	}
	if _, err := exec.LookPath("xclip"); err == nil {
		return BackendX11
	}
	return BackendNone
}

// BackendName returns a human-readable backend label.
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
		return "", errors.New("clipboard backend unavailable")
	}
	var out bytes.Buffer
	cmd.Stdout = &out
	if err := cmd.Run(); err != nil {
		return "", err
	}
	return out.String(), nil
}

// ApplyRemote writes an incoming remote clipboard payload to the local OS clipboard,
// skipping echo if the OriginHash matches the latest known content.
func (m *Manager) ApplyRemote(clip protocol.ClipPayload) error {
	text := strings.ReplaceAll(clip.Text, "\r\n", "\n")
	hash := protocol.ComputeClipOriginHash(text)

	m.mu.Lock()
	if m.lastContent == text || (clip.OriginHash != [8]byte{} && m.recentHash == clip.OriginHash) {
		m.mu.Unlock()
		return nil
	}
	m.lastContent = text
	m.recentHash = hash
	backend := m.backend
	m.mu.Unlock()

	var cmd *exec.Cmd
	switch backend {
	case BackendWayland:
		cmd = exec.Command("wl-copy")
	case BackendX11:
		cmd = exec.Command("xclip", "-selection", "clipboard", "-i")
	default:
		return errors.New("clipboard backend unavailable")
	}
	cmd.Stdin = strings.NewReader(text)
	return cmd.Run()
}

// Current returns the cached clipboard content.
func (m *Manager) Current() string {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.lastContent
}

// BuildClipPacket constructs a binary PacketClip payload for text.
func BuildClipPacket(text string) ([]byte, error) {
	p := protocol.ClipPayload{
		TimestampMs: uint64(time.Now().UnixMilli()),
		OriginHash:  protocol.ComputeClipOriginHash(text),
		Mime:        "text/plain;charset=utf-8",
		Text:        text,
	}
	return p.Encode()
}

// Watch polls the local OS clipboard at interval and invokes onChange for new text.
func (m *Manager) Watch(ctx context.Context, interval time.Duration, onChange func(protocol.ClipPayload)) {
	if m.backend == BackendNone {
		return
	}
	if interval <= 0 {
		interval = 700 * time.Millisecond
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
			norm := strings.ReplaceAll(current, "\r\n", "\n")
			hash := protocol.ComputeClipOriginHash(norm)

			m.mu.Lock()
			changed := norm != m.lastContent && hash != m.recentHash
			if changed {
				m.lastContent = norm
				m.recentHash = hash
			}
			m.mu.Unlock()

			if changed && onChange != nil {
				onChange(protocol.ClipPayload{
					TimestampMs: uint64(time.Now().UnixMilli()),
					OriginHash:  hash,
					Mime:        "text/plain;charset=utf-8",
					Text:        norm,
				})
			}
		}
	}
}
