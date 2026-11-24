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
	Width       int
	Height      int
	FPS         int
	PreferVAAPI bool
}

// FrameSink receives encoded Annex-B VideoFrame packets ready for transport.
type FrameSink func(flags uint8, hdr protocol.VideoFrameHeader, nal []byte)

// DesktopCapture runs ffmpeg x11grab (h264_vaapi with libx264 zerolatency fallback)
// and streams parsed H.264 NAL units to FrameSink.
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
	if cfg.FPS <= 0 {
		cfg.FPS = 30
	}
	return &DesktopCapture{cfg: cfg, sink: sink}
}

// ScreenSize returns the configured capture width and height.
func (d *DesktopCapture) ScreenSize() (int, int) {
	return d.cfg.Width, d.cfg.Height
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
		d.runLoop(cctx)
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

func (d *DesktopCapture) runLoop(ctx context.Context) {
	useVAAPI := d.cfg.PreferVAAPI && hasRenderNode("/dev/dri/renderD128")
	if useVAAPI {
		emitted := d.runFFmpeg(ctx, true)
		if ctx.Err() != nil || emitted {
			return
		}
	}
	if ctx.Err() == nil {
		_ = d.runFFmpeg(ctx, false)
	}
}

func (d *DesktopCapture) runFFmpeg(ctx context.Context, vaapi bool) (emittedFrames bool) {
	sizeArg := fmt.Sprintf("%dx%d", d.cfg.Width, d.cfg.Height)
	fpsArg := strconv.Itoa(d.cfg.FPS)

	var args []string
	if vaapi {
		args = []string{
			"-hide_banner", "-loglevel", "error",
			"-vaapi_device", "/dev/dri/renderD128",
			"-f", "x11grab", "-framerate", fpsArg, "-video_size", sizeArg, "-i", d.cfg.Display,
			"-vf", "format=nv12,hwupload",
			"-c:v", "h264_vaapi", "-qp", "26", "-bf", "0", "-g", fpsArg,
			"-f", "h264", "pipe:1",
		}
	} else {
		args = []string{
			"-hide_banner", "-loglevel", "error",
			"-f", "x11grab", "-framerate", fpsArg, "-video_size", sizeArg, "-i", d.cfg.Display,
			"-pix_fmt", "yuv420p",
			"-c:v", "libx264", "-preset", "ultrafast", "-tune", "zerolatency",
			"-profile:v", "baseline", "-bf", "0", "-g", fpsArg,
			"-f", "h264", "pipe:1",
		}
	}

	cmd := exec.CommandContext(ctx, "ffmpeg", args...)
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return false
	}
	if err := cmd.Start(); err != nil {
		return false
	}

	splitter := NewNALSplitter(protocol.CodecH264)
	readBuf := make([]byte, 32*1024)
	startNs := time.Now()
	var frameCount uint64

	for {
		n, readErr := stdout.Read(readBuf)
		if n > 0 {
			splitter.Feed(readBuf[:n], func(u NALUnit) {
				emittedFrames = true
				frameCount++
				flags := protocol.FlagDirLinuxToPhone
				if u.IsConfig {
					flags |= protocol.FlagCodecConfig
				}
				if u.IsKeyframe {
					flags |= protocol.FlagKeyframe
				}
				ptsUs := uint64(time.Since(startNs).Microseconds())
				if d.sink != nil {
					d.sink(flags, protocol.VideoFrameHeader{
						StreamID: protocol.StreamLinuxToPhone,
						Codec:    protocol.CodecH264,
						Width:    uint16(d.cfg.Width),
						Height:   uint16(d.cfg.Height),
						PtsUs:    ptsUs,
					}, u.Data)
				}
			})
		}
		if readErr != nil {
			if readErr == io.EOF {
				splitter.Flush(func(u NALUnit) {
					flags := protocol.FlagDirLinuxToPhone
					if u.IsConfig {
						flags |= protocol.FlagCodecConfig
					}
					if u.IsKeyframe {
						flags |= protocol.FlagKeyframe
					}
					if d.sink != nil {
						d.sink(flags, protocol.VideoFrameHeader{
							StreamID: protocol.StreamLinuxToPhone,
							Codec:    protocol.CodecH264,
							Width:    uint16(d.cfg.Width),
							Height:   uint16(d.cfg.Height),
							PtsUs:    uint64(time.Since(startNs).Microseconds()),
						}, u.Data)
					}
				})
			}
			break
		}
	}

	_ = cmd.Wait()
	return emittedFrames || frameCount > 0
}

func hasRenderNode(path string) bool {
	st, err := os.Stat(path)
	return err == nil && !st.IsDir()
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
			// Ensure even dimensions required by H.264 encoders.
			return w &^ 1, h &^ 1
		}
	}
	return 1280, 720
}
