package mirror

import (
	"bytes"
	"testing"

	"phonedrop/v2/desktop/core/protocol"
)

func TestNALSplitterH264CrossBoundary(t *testing.T) {
	sps := []byte{0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0xC0, 0x1F}
	pps := []byte{0x00, 0x00, 0x00, 0x01, 0x68, 0xCE, 0x3C, 0x80}
	idr := []byte{0x00, 0x00, 0x01, 0x65, 0x88, 0x84, 0x21}
	pframe := []byte{0x00, 0x00, 0x00, 0x01, 0x41, 0x9A, 0x11}

	stream := append(append(append(append([]byte{}, sps...), pps...), idr...), pframe...)
	splitter := NewNALSplitter(protocol.CodecH264)

	var got []NALUnit
	// Feed in small irregular byte slices to test split start codes across chunks
	for i := 0; i < len(stream); i += 3 {
		end := i + 3
		if end > len(stream) {
			end = len(stream)
		}
		splitter.Feed(stream[i:end], func(u NALUnit) {
			got = append(got, u)
		})
	}
	splitter.Flush(func(u NALUnit) {
		got = append(got, u)
	})

	if len(got) != 4 {
		t.Fatalf("expected 4 NAL units, got %d", len(got))
	}
	if !got[0].IsConfig || got[0].IsKeyframe || !bytes.Equal(got[0].Data, sps) {
		t.Fatalf("unexpected SPS: %+v", got[0])
	}
	if !got[1].IsConfig || !bytes.Equal(got[1].Data, pps) {
		t.Fatalf("unexpected PPS: %+v", got[1])
	}
	if got[2].IsConfig || !got[2].IsKeyframe || !bytes.Equal(got[2].Data, idr) {
		t.Fatalf("unexpected IDR: %+v", got[2])
	}
	if got[3].IsConfig || got[3].IsKeyframe || !bytes.Equal(got[3].Data, pframe) {
		t.Fatalf("unexpected P-frame: %+v", got[3])
	}
}
