package mirror

import "testing"

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
