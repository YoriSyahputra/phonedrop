package mirror

import (
	"slices"
	"testing"

	"phonedrop/v2/desktop/core/protocol"
)

func TestParseDisplayMaxRefreshRate(t *testing.T) {
	infinixDump := `
  DisplayDeviceInfo{"Built-in Screen": uniqueId="local:4627039422300187648", 1080 x 2436, modeId 2,
    supportedModes [
      {id=1, width=1080, height=2436, fps=144.00002, vsync=144.00002},
      {id=2, width=1080, height=2436, fps=120.00001, vsync=120.00001},
      {id=3, width=1080, height=2436, fps=60.0, vsync=60.0}
    ]}`
	if got := ParseDisplayMaxRefreshRate(infinixDump); got != 144 {
		t.Fatalf("ParseDisplayMaxRefreshRate(infinix) = %d, want 144", got)
	}

	pixel90Dump := `DisplayMode{id=0, width=1080, height=2400, peakRefreshRate=90.0, vsyncRate=90.0}`
	if got := ParseDisplayMaxRefreshRate(pixel90Dump); got != 90 {
		t.Fatalf("ParseDisplayMaxRefreshRate(pixel90) = %d, want 90", got)
	}

	if got := ParseDisplayMaxRefreshRate("no refresh modes here"); got != 0 {
		t.Fatalf("ParseDisplayMaxRefreshRate(empty) = %d, want 0", got)
	}
}

func TestBuildScrcpyArgs(t *testing.T) {
	custom := protocol.MirrorConfig{
		FPS:           120,
		BitrateMbps:   16,
		MaxSize:       1280,
		Audio:         false,
		Codec:         "h265",
		TurnScreenOff: true,
		StayAwake:     true,
		AlwaysOnTop:   true,
		Fullscreen:    true,
		ShowTouches:   true,
	}
	args := BuildScrcpyArgs("192.168.1.3:37449", custom, 144)
	for _, want := range []string{
		"--video-bit-rate=16M",
		"--max-fps=120",
		"--max-size=1280",
		"--no-audio",
		"--video-codec=h265",
		"--turn-screen-off",
		"--stay-awake",
		"--always-on-top",
		"--fullscreen",
		"--show-touches",
	} {
		if !slices.Contains(args, want) {
			t.Fatalf("expected scrcpy arg %q in %v", want, args)
		}
	}

	autoCfg := protocol.DefaultMirrorConfig()
	autoArgs := BuildScrcpyArgs("ABC123", autoCfg, 144)
	for _, want := range []string{
		"--video-bit-rate=8M",
		"--max-fps=144",
		"--audio-codec=opus",
		"--video-codec=h264",
		"--stay-awake",
	} {
		if !slices.Contains(autoArgs, want) {
			t.Fatalf("expected auto scrcpy arg %q in %v", want, autoArgs)
		}
	}
	if slices.Contains(autoArgs, "--no-audio") {
		t.Fatalf("did not expect --no-audio in %v", autoArgs)
	}
}
