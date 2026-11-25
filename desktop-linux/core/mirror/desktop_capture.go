package mirror

import (
	"context"
	"fmt"
	"io"
	"os"
	"os/exec"
	"regexp"
	"strconv"
	"sync"
	"sync/atomic"
	"time"

	"phonedrop/v2/desktop/core/protocol"
)

// CaptureConfig controls Linux X11 desktop H.264 capture parameters.
type CaptureConfig struct {
	Display     string
	Width       int // source X11 capture width (auto-detected if 0)
	Height      int // source X11 capture height (auto-detected if 0)
	OutWidth    int // encoded output stream width (default 1280)
	OutHeight   int // encoded output stream height (default 720)
	FPS         int
	BitrateKbps int
	PreferVAAPI bool
}

// FrameSink receives encoded Annex-B VideoFrame packets ready for transport.
type FrameSink func(flags uint8, hdr protocol.VideoFrameHeader, nal []byte)

// DesktopCapture runs ffmpeg x11grab (libx264 baseline zerolatency) and emits
// combined SPS+PPS FlagCodecConfig frames plus IDR/P-frame VideoFrame packets.
type DesktopCapture struct {
	cfg     CaptureConfig
	sink    FrameSink
	mu      sync.Mutex
	cancel  context.CancelFunc
	done    chan struct{}
	running atomic.Bool
}

// NewDesktopCapture creates a DesktopCapture instance with auto-detected screen resolution.
func NewDesktopCapture(cfg CaptureConfig, sink FrameSink) *DesktopCapture {
	if cfg.Display == "" {
		cfg.Display = os.Getenv("DISPLAY")
		if cfg.Display == "" {
			cfg.Display = ":0.0"
		}
	}
	if cfg.Width <= 0 || cfg.Height <= 0 {
		w, h := DetectX11ScreenSize(cfg.Display)
		if cfg.Width <= 0 {
			cfg.Width = w
		}
		if cfg.Height <= 0 {
			cfg.Height = h
		}
	}
	if cfg.OutWidth <= 0 {
		cfg.OutWidth = 1280
	}
	if cfg.OutHeight <= 0 {
		cfg.OutHeight = 720
	}
	cfg.OutWidth &^= 1
	cfg.OutHeight &^= 1
	if cfg.FPS <= 0 {
		cfg.FPS = 30
	}
	if cfg.BitrateKbps <= 0 {
		cfg.BitrateKbps = 4000
	}
	return &DesktopCapture{cfg: cfg, sink: sink}
}

// ApplyMirrorConfig updates Linux->Phone desktop capture FPS, bitrate, and output dimensions.
func (d *DesktopCapture) ApplyMirrorConfig(mc protocol.MirrorConfig) {
	mc = mc.Sanitize()
	d.mu.Lock()
	defer d.mu.Unlock()

	if mc.FPS > 0 {
		fps := mc.FPS
		if fps < 15 {
			fps = 15
		} else if fps > 60 {
			fps = 60
		}
		d.cfg.FPS = fps
	} else {
		d.cfg.FPS = 30
	}

	kbps := mc.BitrateMbps * 1000
	if kbps < 1500 {
		kbps = 1500
	} else if kbps > 16000 {
		kbps = 16000
	}
	d.cfg.BitrateKbps = kbps

	srcW := d.cfg.Width
	srcH := d.cfg.Height
	if srcW <= 0 || srcH <= 0 {
		srcW, srcH = 1280, 720
	}
	if mc.MaxSize > 0 && (srcW > mc.MaxSize || srcH > mc.MaxSize) {
		if srcW >= srcH {
			d.cfg.OutWidth = mc.MaxSize
			d.cfg.OutHeight = (srcH * mc.MaxSize / srcW)
		} else {
			d.cfg.OutHeight = mc.MaxSize
			d.cfg.OutWidth = (srcW * mc.MaxSize / srcH)
		}
	} else {
		d.cfg.OutWidth = srcW
		d.cfg.OutHeight = srcH
	}
	d.cfg.OutWidth = (d.cfg.OutWidth &^ 1)
	d.cfg.OutHeight = (d.cfg.OutHeight &^ 1)
	if d.cfg.OutWidth < 320 {
		d.cfg.OutWidth = 320
	}
	if d.cfg.OutHeight < 240 {
		d.cfg.OutHeight = 240
	}
}

// ScreenSize returns the real X11 desktop width and height for accurate InputInjector mapping.
func (d *DesktopCapture) ScreenSize() (int, int) {
	return d.cfg.Width, d.cfg.Height
}

// StreamSize returns the encoded H.264 output video width and height.
func (d *DesktopCapture) StreamSize() (int, int) {
	return d.cfg.OutWidth, d.cfg.OutHeight
}

// IsRunning reports whether desktop capture is currently active.
func (d *DesktopCapture) IsRunning() bool {
	return d.running.Load()
}

// Start begins streaming Linux desktop H.264 NAL frames until Stop() or ctx cancellation.
func (d *DesktopCapture) Start(ctx context.Context) error {
	d.mu.Lock()
	if d.running.Load() {
		d.mu.Unlock()
		return nil
	}
	cctx, cancel := context.WithCancel(ctx)
	done := make(chan struct{})
	d.cancel = cancel
	d.done = done
	d.running.Store(true)
	d.mu.Unlock()

	go func() {
		defer close(done)
		defer d.running.Store(false)
		d.runFFmpeg(cctx)
	}()
	return nil
}

// Stop terminates desktop capture and waits briefly for ffmpeg cleanup.
func (d *DesktopCapture) Stop() {
	d.mu.Lock()
	cancel := d.cancel
	done := d.done
	d.mu.Unlock()
	if cancel != nil {
		cancel()
	}
	if done != nil {
		select {
		case <-done:
		case <-time.After(2 * time.Second):
		}
	}
}

func (d *DesktopCapture) runFFmpeg(ctx context.Context) {
	d.mu.Lock()
	srcW, srcH := d.cfg.Width, d.cfg.Height
	outW, outH := d.cfg.OutWidth, d.cfg.OutHeight
	fpsVal := d.cfg.FPS
	kbps := d.cfg.BitrateKbps
	display := d.cfg.Display
	d.mu.Unlock()
	if fpsVal <= 0 {
		fpsVal = 30
	}
	if kbps <= 0 {
		kbps = 4000
	}

	inSize := fmt.Sprintf("%dx%d", srcW, srcH)
	vf := fmt.Sprintf("scale=%d:%d,format=yuv420p", outW, outH)
	fpsArg := strconv.Itoa(fpsVal)
	minKey := fpsVal / 2
	if minKey < 5 {
		minKey = 5
	}
	x264Params := fmt.Sprintf("repeat-headers=1:sliced-threads=0:slices=1:annexb=1:keyint=%d:min-keyint=%d:bframes=0", fpsVal, minKey)
	bv := fmt.Sprintf("%dk", kbps)
	maxRate := fmt.Sprintf("%dk", kbps+kbps/4)
	bufSize := fmt.Sprintf("%dk", kbps/2)

	args := []string{
		"-hide_banner", "-loglevel", "error",
		"-f", "x11grab", "-framerate", fpsArg, "-video_size", inSize, "-i", display,
		"-vf", vf,
		"-c:v", "libx264", "-preset", "ultrafast", "-tune", "zerolatency",
		"-profile:v", "baseline", "-level", "4.2",
		"-b:v", bv, "-maxrate", maxRate, "-bufsize", bufSize,
		"-x264-params", x264Params,
		"-f", "h264", "pipe:1",
	}

	cmd := exec.CommandContext(ctx, "ffmpeg", args...)
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return
	}
	if err := cmd.Start(); err != nil {
		return
	}

	splitter := NewNALSplitter(protocol.CodecH264)
	readBuf := make([]byte, 32*1024)
	startNs := time.Now()
	var cachedSPS []byte

	emitUnit := func(u NALUnit) {
		if d.sink == nil || len(u.Data) == 0 {
			return
		}
		norm := normalizeStartCode4(u.Data)
		if len(norm) <= 4 {
			return
		}
		nalType := norm[4] & 0x1F
		// Skip SEI (6) and AUD (9) metadata units; Android MediaCodec prefers clean SPS/PPS + VCL slices.
		if nalType == 6 || nalType == 9 {
			return
		}

		ptsUs := uint64(time.Since(startNs).Microseconds())
		hdr := protocol.VideoFrameHeader{
			StreamID: protocol.StreamLinuxToPhone,
			Codec:    protocol.CodecH264,
			Width:    uint16(outW),
			Height:   uint16(outH),
			PtsUs:    ptsUs,
		}

		if nalType == 7 { // SPS — cache and hold until PPS arrives so both cache/send together
			cachedSPS = append([]byte(nil), norm...)
			return
		}
		if nalType == 8 { // PPS -> combine [SPS][PPS]
			if len(cachedSPS) > 0 {
				combined := make([]byte, 0, len(cachedSPS)+len(norm))
				combined = append(combined, cachedSPS...)
				combined = append(combined, norm...)
				d.sink(protocol.FlagDirLinuxToPhone|protocol.FlagCodecConfig, hdr, combined)
			} else {
				d.sink(protocol.FlagDirLinuxToPhone|protocol.FlagCodecConfig, hdr, norm)
			}
			return
		}

		flags := protocol.FlagDirLinuxToPhone
		if nalType == 5 || u.IsKeyframe {
			flags |= protocol.FlagKeyframe
		}
		d.sink(flags, hdr, norm)
	}

	for {
		n, readErr := stdout.Read(readBuf)
		if n > 0 {
			splitter.Feed(readBuf[:n], emitUnit)
		}
		if readErr != nil {
			if readErr == io.EOF {
				splitter.Flush(emitUnit)
			}
			break
		}
	}

	_ = cmd.Wait()
}

func normalizeStartCode4(nal []byte) []byte {
	if len(nal) >= 4 && nal[0] == 0x00 && nal[1] == 0x00 && nal[2] == 0x00 && nal[3] == 0x01 {
		return nal
	}
	if len(nal) >= 3 && nal[0] == 0x00 && nal[1] == 0x00 && nal[2] == 0x01 {
		out := make([]byte, len(nal)+1)
		out[0] = 0x00
		copy(out[1:], nal)
		return out
	}
	return nal
}

var xrandrCurrentRe = regexp.MustCompile(`current\s+(\d+)\s*x\s*(\d+)`)

// DetectX11ScreenSize parses xrandr output to determine active X11 desktop resolution.
func DetectX11ScreenSize(display string) (int, int) {
	if _, err := exec.LookPath("xrandr"); err != nil {
		return 1280, 720
	}
	cmd := exec.Command("xrandr", "--query")
	if display != "" {
		cmd.Env = append(os.Environ(), "DISPLAY="+display)
	}
	out, err := cmd.Output()
	if err != nil {
		return 1280, 720
	}
	m := xrandrCurrentRe.FindStringSubmatch(string(out))
	if len(m) == 3 {
		w, _ := strconv.Atoi(m[1])
		h, _ := strconv.Atoi(m[2])
		if w > 0 && h > 0 {
			return w &^ 1, h &^ 1
		}
	}
	return 1280, 720
}
