package mirror

import (
	"context"
	"errors"
	"fmt"
	"io"
	"math"
	"os/exec"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"phonedrop/v2/desktop/core/protocol"
)

var displayFPSPattern = regexp.MustCompile(`(?:fps|peakRefreshRate)=([0-9]+(?:\.[0-9]+)?)`)

// ParseDisplayMaxRefreshRate extracts the highest supported panel refresh rate (60..240 Hz)
// from Android `dumpsys display` output, returning 0 if none could be parsed.
func ParseDisplayMaxRefreshRate(dumpsysOutput string) int {
	maxHz := 0
	for _, m := range displayFPSPattern.FindAllStringSubmatch(dumpsysOutput, -1) {
		if len(m) < 2 {
			continue
		}
		v, err := strconv.ParseFloat(m[1], 64)
		if err != nil {
			continue
		}
		hz := int(math.Round(v))
		if hz >= 60 && hz <= 240 && hz > maxHz {
			maxHz = hz
		}
	}
	return maxHz
}

// DetectPhoneMaxRefreshRate queries the connected Android device via ADB for its highest
// supported display refresh rate (e.g. 144, 120, 90, 60). Returns 0 when unavailable.
func DetectPhoneMaxRefreshRate(serial string) int {
	target := ResolveActiveADBSerial(serial)
	if target == "" {
		return 0
	}
	ctx, cancel := context.WithTimeout(context.Background(), 1500*time.Millisecond)
	defer cancel()
	out, err := exec.CommandContext(ctx, "adb", "-s", target, "shell", "dumpsys display").Output()
	if err != nil {
		return 0
	}
	return ParseDisplayMaxRefreshRate(string(out))
}

// BuildScrcpyArgs constructs the scrcpy CLI arguments for the given device, user settings, and detected Hz.
func BuildScrcpyArgs(target string, cfg protocol.MirrorConfig, detectedPhoneHz int) []string {
	cfg = cfg.Sanitize()
	args := []string{
		"-s", target,
		"--window-title=PhoneDrop — Layar HP",
		fmt.Sprintf("--video-bit-rate=%dM", cfg.BitrateMbps),
	}
	if cfg.FPS > 0 {
		args = append(args, fmt.Sprintf("--max-fps=%d", cfg.FPS))
	} else if detectedPhoneHz > 0 {
		args = append(args, fmt.Sprintf("--max-fps=%d", detectedPhoneHz))
	}
	if cfg.MaxSize > 0 {
		args = append(args, fmt.Sprintf("--max-size=%d", cfg.MaxSize))
	}
	if !cfg.Audio {
		args = append(args, "--no-audio")
	} else {
		args = append(args, "--audio-codec=opus")
	}
	if cfg.Codec == "h265" {
		args = append(args, "--video-codec=h265")
	} else {
		args = append(args, "--video-codec=h264")
	}
	if cfg.StayAwake || cfg.TurnScreenOff {
		args = append(args, "--stay-awake")
	}
	if cfg.TurnScreenOff {
		args = append(args, "--turn-screen-off")
	}
	if cfg.AlwaysOnTop {
		args = append(args, "--always-on-top")
	}
	if cfg.Fullscreen {
		args = append(args, "--fullscreen")
	}
	if cfg.ShowTouches {
		args = append(args, "--show-touches")
	}
	return args
}

// ScrcpyManager launches and manages a standalone interactive scrcpy window
// providing high-refresh-rate Phone -> Linux screen mirroring with native mouse/keyboard control.
type ScrcpyManager struct {
	mu      sync.Mutex
	cmd     *exec.Cmd
	done    chan struct{}
	cfg     protocol.MirrorConfig
	running atomic.Bool
	onExit  func()
}

// NewScrcpyManager constructs a ScrcpyManager with default mirror settings.
func NewScrcpyManager(onExit func()) *ScrcpyManager {
	return &ScrcpyManager{
		cfg:    protocol.DefaultMirrorConfig(),
		onExit: onExit,
	}
}

// SetConfig stores sanitized mirror settings for future scrcpy launches.
func (m *ScrcpyManager) SetConfig(cfg protocol.MirrorConfig) {
	m.mu.Lock()
	m.cfg = cfg.Sanitize()
	m.mu.Unlock()
}

// IsRunning reports whether a managed scrcpy session is active.
func (m *ScrcpyManager) IsRunning() bool {
	return m.running.Load()
}

// ResolveActiveADBSerial returns preferred if non-empty, otherwise queries `adb devices`
// for the first authorized device (supporting both USB and wireless ADB serials).
func ResolveActiveADBSerial(preferred string) string {
	if strings.TrimSpace(preferred) != "" {
		return strings.TrimSpace(preferred)
	}
	out, err := exec.Command("adb", "devices").Output()
	if err != nil {
		return ""
	}
	for _, line := range strings.Split(string(out), "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "List of devices") {
			continue
		}
		fields := strings.Fields(line)
		if len(fields) >= 2 && fields[1] == "device" {
			return fields[0]
		}
	}
	return ""
}

// Start launches scrcpy targeting serial using the currently stored MirrorConfig.
func (m *ScrcpyManager) Start(serial string) error {
	m.mu.Lock()
	cfg := m.cfg
	m.mu.Unlock()
	return m.StartWithConfig(serial, cfg)
}

// StartWithConfig stores cfg and launches scrcpy targeting serial (or auto-discovered ADB device).
func (m *ScrcpyManager) StartWithConfig(serial string, cfg protocol.MirrorConfig) error {
	if _, err := exec.LookPath("scrcpy"); err != nil {
		return errors.New("scrcpy tidak ditemukan di sistem (/usr/bin/scrcpy)")
	}

	cfg = cfg.Sanitize()
	m.mu.Lock()
	m.cfg = cfg
	if m.running.Load() {
		m.mu.Unlock()
		return nil
	}

	target := ResolveActiveADBSerial(serial)
	if target == "" {
		m.mu.Unlock()
		return errors.New("tidak ada HP ADB yang terhubung (cek USB / Wireless Debugging)")
	}

	detectedHz := 0
	if cfg.FPS <= 0 {
		detectedHz = DetectPhoneMaxRefreshRate(target)
	}
	args := BuildScrcpyArgs(target, cfg, detectedHz)
	cmd := exec.Command("scrcpy", args...)
	if err := cmd.Start(); err != nil {
		m.mu.Unlock()
		return fmt.Errorf("gagal menjalankan scrcpy: %w", err)
	}

	done := make(chan struct{})
	m.cmd = cmd
	m.done = done
	m.running.Store(true)
	cb := m.onExit
	m.mu.Unlock()

	go func() {
		defer close(done)
		_ = cmd.Wait()
		shouldNotify := false
		m.mu.Lock()
		if m.cmd == cmd {
			m.cmd = nil
			m.done = nil
			m.running.Store(false)
			shouldNotify = true
		}
		m.mu.Unlock()
		if shouldNotify && cb != nil {
			cb()
		}
	}()
	return nil
}

// RestartIfRunning updates stored settings and restarts scrcpy cleanly if currently active.
func (m *ScrcpyManager) RestartIfRunning(serial string, cfg protocol.MirrorConfig) error {
	cfg = cfg.Sanitize()
	m.mu.Lock()
	m.cfg = cfg
	wasRunning := m.running.Load()
	cmd := m.cmd
	done := m.done
	if wasRunning && cmd != nil {
		// Prevent old cmd exit from clearing UI active badge during restart.
		m.cmd = nil
		m.done = nil
		m.running.Store(false)
	}
	m.mu.Unlock()

	if !wasRunning {
		return nil
	}
	if cmd != nil && cmd.Process != nil {
		_ = cmd.Process.Kill()
	}
	if done != nil {
		select {
		case <-done:
		case <-time.After(1500 * time.Millisecond):
		}
	}
	return m.StartWithConfig(serial, cfg)
}

// Stop terminates the running scrcpy window cleanly.
func (m *ScrcpyManager) Stop() {
	m.mu.Lock()
	cmd := m.cmd
	done := m.done
	m.mu.Unlock()
	if cmd != nil && cmd.Process != nil {
		_ = cmd.Process.Kill()
	}
	if done != nil {
		select {
		case <-done:
		case <-time.After(1500 * time.Millisecond):
		}
	}
}

// StartADBScreenStream captures an Android device's H.264 display stream directly over USB ADB
// (`adb exec-out screenrecord --output-format=h264 -`) and feeds it into mpv.
func StartADBScreenStream(ctx context.Context, serial string, player *MPVPlayer) error {
	if player == nil {
		return fmt.Errorf("scrcpy_bridge: nil MPVPlayer")
	}
	if err := player.Start(); err != nil {
		return err
	}

	args := []string{}
	if serial != "" {
		args = append(args, "-s", serial)
	}
	args = append(args, "exec-out", "screenrecord", "--output-format=h264", "--bit-rate", "4000000", "-")

	cmd := exec.CommandContext(ctx, "adb", args...)
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return err
	}
	if err := cmd.Start(); err != nil {
		return err
	}

	go func() {
		defer func() { _ = cmd.Wait() }()
		splitter := NewNALSplitter(protocol.CodecH264)
		buf := make([]byte, 32*1024)
		for {
			n, rerr := stdout.Read(buf)
			if n > 0 {
				splitter.Feed(buf[:n], func(u NALUnit) {
					player.WriteNAL(u.Data, u.IsConfig || u.IsKeyframe)
				})
			}
			if rerr != nil {
				if rerr == io.EOF {
					splitter.Flush(func(u NALUnit) {
						player.WriteNAL(u.Data, u.IsConfig || u.IsKeyframe)
					})
				}
				return
			}
		}
	}()
	return nil
}

// ForwardInputToADB executes normalized touch/text input on an ADB-connected phone when
// mirroring via raw ADB fallback.
func ForwardInputToADB(serial string, ev protocol.InputEventPayload, phoneW, phoneH int) {
	if phoneW <= 0 {
		phoneW = 1080
	}
	if phoneH <= 0 {
		phoneH = 2400
	}
	base := []string{}
	if serial != "" {
		base = append(base, "-s", serial)
	}

	switch ev.EventType {
	case protocol.InputTapAbs:
		px := clampInt(int(ev.X)*phoneW/65535, 0, phoneW)
		py := clampInt(int(ev.Y)*phoneH/65535, 0, phoneH)
		args := append(base, "shell", "input", "tap", strconv.Itoa(px), strconv.Itoa(py))
		_ = exec.Command("adb", args...).Run()
	case protocol.InputSwipeAbs:
		x1 := clampInt(int(ev.X)*phoneW/65535, 0, phoneW)
		y1 := clampInt(int(ev.Y)*phoneH/65535, 0, phoneH)
		x2 := clampInt(int(ev.Arg1)*phoneW/65535, 0, phoneW)
		y2 := clampInt(int(ev.Arg2)*phoneH/65535, 0, phoneH)
		args := append(base, "shell", "input", "swipe", strconv.Itoa(x1), strconv.Itoa(y1), strconv.Itoa(x2), strconv.Itoa(y2), "180")
		_ = exec.Command("adb", args...).Run()
	case protocol.InputTextUTF8:
		if ev.Text != "" {
			args := append(base, "shell", "input", "text", ev.Text)
			_ = exec.Command("adb", args...).Run()
		}
	}
}

func clampInt(v, lo, hi int) int {
	if v < lo {
		return lo
	}
	if v > hi {
		return hi
	}
	return v
}
