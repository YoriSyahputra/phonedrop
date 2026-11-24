package mirror

import (
	"encoding/json"
	"fmt"
	"io"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"sync"
	"sync/atomic"
	"time"
)

type nalPacket struct {
	data       []byte
	isCritical bool
}

// MPVPlayer embeds hardware-accelerated low-latency mpv into an X11 GtkDrawingArea (--wid)
// and exposes a UNIX domain JSON IPC controller.
type MPVPlayer struct {
	wid      uint64
	ipcPath  string
	mu       sync.Mutex
	cmd      *exec.Cmd
	stdin    io.WriteCloser
	writeCh  chan nalPacket
	doneCh   chan struct{}
	running  atomic.Bool
	seqID    atomic.Uint64
}

var mpvInstanceCounter atomic.Uint64

// NewMPVPlayer prepares an embedded mpv player targeting X11 window wid (0 for standalone).
func NewMPVPlayer(wid uint64) *MPVPlayer {
	idx := mpvInstanceCounter.Add(1)
	sockPath := filepath.Join(os.TempDir(), fmt.Sprintf("phonedrop-mpv-%d-%d.sock", os.Getpid(), idx))
	return &MPVPlayer{
		wid:     wid,
		ipcPath: sockPath,
	}
}

// IPCPath returns the UNIX domain socket path for mpv JSON IPC.
func (m *MPVPlayer) IPCPath() string {
	return m.ipcPath
}

// IsRunning reports whether mpv is currently active.
func (m *MPVPlayer) IsRunning() bool {
	return m.running.Load()
}

// Start launches mpv reading raw Annex-B H.264 bytes from stdin.
func (m *MPVPlayer) Start() error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.running.Load() {
		return nil
	}
	_ = os.Remove(m.ipcPath)

	args := []string{
		"--no-config",
		"--profile=low-latency",
		"--no-cache",
		"--untimed",
		"--no-audio",
		"--demuxer=lavf",
		"--demuxer-lavf-format=h264",
		"--hwdec=auto-safe",
		"--input-ipc-server=" + m.ipcPath,
		"--title=PhoneDrop Mirror",
	}
	if m.wid != 0 {
		args = append(args, fmt.Sprintf("--wid=%d", m.wid))
	}
	args = append(args, "fd://0")

	cmd := exec.Command("mpv", args...)
	stdin, err := cmd.StdinPipe()
	if err != nil {
		return fmt.Errorf("mpv stdin pipe: %w", err)
	}
	if err := cmd.Start(); err != nil {
		_ = stdin.Close()
		return fmt.Errorf("start mpv: %w", err)
	}

	m.cmd = cmd
	m.stdin = stdin
	m.writeCh = make(chan nalPacket, 64)
	m.doneCh = make(chan struct{})
	m.running.Store(true)

	go m.writerLoop(cmd, stdin, m.writeCh, m.doneCh)
	return nil
}

func (m *MPVPlayer) writerLoop(cmd *exec.Cmd, stdin io.WriteCloser, ch <-chan nalPacket, done chan struct{}) {
	defer close(done)
	defer m.running.Store(false)
	defer func() {
		_ = stdin.Close()
		_ = cmd.Wait()
		_ = os.Remove(m.ipcPath)
	}()

	for pkt := range ch {
		if len(pkt.data) == 0 {
			continue
		}
		if _, err := stdin.Write(pkt.data); err != nil {
			return
		}
	}
}

// WriteNAL queues an Annex-B NAL unit for playback, dropping non-critical P-frames
// if the decoder input buffer falls behind to preserve low latency.
func (m *MPVPlayer) WriteNAL(nal []byte, isCritical bool) bool {
	if !m.running.Load() || len(nal) == 0 {
		return false
	}
	cp := make([]byte, len(nal))
	copy(cp, nal)
	pkt := nalPacket{data: cp, isCritical: isCritical}

	m.mu.Lock()
	ch := m.writeCh
	m.mu.Unlock()
	if ch == nil {
		return false
	}

	if isCritical {
		select {
		case ch <- pkt:
			return true
		case <-time.After(150 * time.Millisecond):
			return false
		}
	}
	select {
	case ch <- pkt:
		return true
	default:
		return false
	}
}

// SendIPCCommand sends a JSON IPC command array to mpv's --input-ipc-server socket.
func (m *MPVPlayer) SendIPCCommand(command ...any) ([]byte, error) {
	conn, err := net.DialTimeout("unix", m.ipcPath, 500*time.Millisecond)
	if err != nil {
		return nil, err
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(500 * time.Millisecond))

	req := map[string]any{
		"command":    command,
		"request_id": m.seqID.Add(1),
	}
	b, err := json.Marshal(req)
	if err != nil {
		return nil, err
	}
	b = append(b, '\n')
	if _, err := conn.Write(b); err != nil {
		return nil, err
	}
	buf := make([]byte, 4096)
	n, err := conn.Read(buf)
	if err != nil {
		return nil, err
	}
	return buf[:n], nil
}

// Stop gracefully shuts down mpv via IPC "quit" and closes stdin.
func (m *MPVPlayer) Stop() {
	_, _ = m.SendIPCCommand("quit")

	m.mu.Lock()
	ch := m.writeCh
	m.writeCh = nil
	cmd := m.cmd
	done := m.doneCh
	m.mu.Unlock()

	if ch != nil {
		close(ch)
	}
	if done != nil {
		select {
		case <-done:
		case <-time.After(1500 * time.Millisecond):
			if cmd != nil && cmd.Process != nil {
				_ = cmd.Process.Kill()
			}
		}
	}
}
