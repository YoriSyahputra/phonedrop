package transport

import (
	"bytes"
	"context"
	"testing"
	"time"

	"phonedrop/v2/desktop/core/protocol"
)

func TestTunnelServerAndCodecReplay(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	clipCh := make(chan protocol.ClipPayload, 1)
	connectedCh := make(chan *Session, 2)

	srv := NewTunnelServer("127.0.0.1:0", Handlers{
		OnConnect: func(s *Session) {
			connectedCh <- s
		},
		OnClip: func(s *Session, clip protocol.ClipPayload) {
			clipCh <- clip
		},
	})
	if _, err := srv.Listen(); err != nil {
		t.Fatalf("listen: %v", err)
	}
	go func() {
		_ = srv.Serve(ctx)
	}()

	// Pre-populate cached SPS/PPS before client connects
	sps := []byte{0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x80, 0x1E}
	srv.BroadcastVideoFrame(protocol.FlagDirLinuxToPhone|protocol.FlagCodecConfig, protocol.VideoFrameHeader{
		StreamID: protocol.StreamLinuxToPhone,
		Codec:    protocol.CodecH264,
		Width:    1280,
		Height:   720,
		PtsUs:    100,
	}, sps)

	vidCh := make(chan protocol.VideoFramePayload, 1)
	client, err := DialSession(ctx, srv.Addr(), Handlers{
		OnVideoFrame: func(s *Session, flags uint8, vid protocol.VideoFramePayload) {
			cp := make([]byte, len(vid.NALData))
			copy(cp, vid.NALData)
			vid.NALData = cp
			vidCh <- vid
		},
	})
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer client.Close()

	select {
	case s := <-connectedCh:
		if s.Mode != ModeUSBLoopback {
			t.Fatalf("expected loopback USB mode on 127.0.0.1, got %s", s.Mode)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("timed out waiting for server OnConnect")
	}

	// Late joiner should receive cached SPS/PPS automatically
	select {
	case vid := <-vidCh:
		if !bytes.Equal(vid.NALData, sps) || vid.Header.Width != 1280 {
			t.Fatalf("unexpected cached codec config: %+v", vid)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("timed out waiting for replayed SPS/PPS")
	}

	// Send clip from client to server
	clip := protocol.ClipPayload{
		TimestampMs: 12345,
		OriginHash:  protocol.ComputeClipOriginHash("hello tunnel"),
		Mime:        "text/plain",
		Text:        "hello tunnel",
	}
	b, _ := clip.Encode()
	if err := client.WriteFrame(protocol.PacketTypeClip, 0, b); err != nil {
		t.Fatalf("write clip: %v", err)
	}

	select {
	case got := <-clipCh:
		if got.Text != "hello tunnel" {
			t.Fatalf("unexpected clip: %+v", got)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("timed out waiting for clip on server")
	}
}
