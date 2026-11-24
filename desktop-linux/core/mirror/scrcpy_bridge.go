package mirror

import (
	"context"
	"fmt"
	"io"
	"os/exec"
	"strconv"

	"phonedrop/v2/desktop/core/protocol"
)

// StartADBScreenStream captures an Android device's H.264 display stream directly over USB ADB
// (`adb exec-out screenrecord --output-format=h264 -`) and feeds it into mpv.
// Used as a zero-prompt fallback when MediaProjection APK capture is not active.
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
// mirroring via USB fallback.
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
