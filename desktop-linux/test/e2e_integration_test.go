package test

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"phonedrop/v2/desktop/core/mirror"
	"phonedrop/v2/desktop/core/protocol"
	"phonedrop/v2/desktop/core/transfer"
	"phonedrop/v2/desktop/core/transport"
)

func TestPhoneDropV2EndToEndEcosystem(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()

	recvDir := t.TempDir()
	sendDir := t.TempDir()

	clipRecvCh := make(chan protocol.ClipPayload, 2)
	videoRecvCh := make(chan protocol.VideoFramePayload, 8)
	inputRecvCh := make(chan protocol.InputEventPayload, 2)
	fileDoneCh := make(chan transfer.ProgressReport, 2)

	desktopEngine, err := transfer.NewEngine(recvDir, func(rep transfer.ProgressReport) {
		if rep.Verified || rep.Err != nil {
			fileDoneCh <- rep
		}
	})
	if err != nil {
		t.Fatalf("new desktop engine: %v", err)
	}

	phoneEngine, err := transfer.NewEngine(sendDir, nil)
	if err != nil {
		t.Fatalf("new phone engine: %v", err)
	}

	srv := transport.NewTunnelServer("127.0.0.1:0", transport.Handlers{
		OnClip: func(_ *transport.Session, clip protocol.ClipPayload) {
			clipRecvCh <- clip
		},
		OnFileInit: func(s *transport.Session, init protocol.FileInitPayload) {
			desktopEngine.HandleFileInit(s, init)
		},
		OnFileChunk: func(_ *transport.Session, chunk protocol.FileChunkPayload) {
			desktopEngine.HandleFileChunk(chunk)
		},
		OnFileDone: func(s *transport.Session, flags uint8, done protocol.FileDonePayload) {
			desktopEngine.HandleFileDone(s, flags, done)
		},
		OnInputEvent: func(_ *transport.Session, ev protocol.InputEventPayload) {
			inputRecvCh <- ev
		},
	})
	if _, err := srv.Listen(); err != nil {
		t.Fatalf("listen: %v", err)
	}
	defer srv.Close()
	go func() { _ = srv.Serve(ctx) }()

	// Simulated Android client session connected over 127.0.0.1 (USB ADB Reverse mode)
	phoneSess, err := transport.DialSession(ctx, srv.Addr(), transport.Handlers{
		OnFileDone: func(s *transport.Session, flags uint8, done protocol.FileDonePayload) {
			phoneEngine.HandleFileDone(s, flags, done)
		},
		OnVideoFrame: func(_ *transport.Session, _ uint8, vid protocol.VideoFramePayload) {
			cp := make([]byte, len(vid.NALData))
			copy(cp, vid.NALData)
			vid.NALData = cp
			videoRecvCh <- vid
		},
	})
	if err != nil {
		t.Fatalf("dial phone session: %v", err)
	}
	defer phoneSess.Close()

	// 1. Clipboard Sync (Phone -> Linux)
	clipBytes, err := (protocol.ClipPayload{
		TimestampMs: uint64(time.Now().UnixMilli()),
		OriginHash:  protocol.ComputeClipOriginHash("e2e-clipboard-sync"),
		Mime:        "text/plain",
		Text:        "e2e-clipboard-sync",
	}).Encode()
	if err != nil {
		t.Fatal(err)
	}
	if err := phoneSess.WriteFrame(protocol.PacketTypeClip, 0, clipBytes); err != nil {
		t.Fatal(err)
	}
	select {
	case got := <-clipRecvCh:
		if got.Text != "e2e-clipboard-sync" {
			t.Fatalf("unexpected clip: %+v", got)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("timed out waiting for clip")
	}

	// 2. 5 MiB Chunked File Transfer (80 x 64KB chunks) + Progress + SHA-256 ACK
	const fiveMB = 5 * 1024 * 1024
	rawFile := make([]byte, fiveMB)
	if _, err := rand.Read(rawFile); err != nil {
		t.Fatal(err)
	}
	wantSum := sha256.Sum256(rawFile)
	wantHex := hex.EncodeToString(wantSum[:])

	srcFile := filepath.Join(t.TempDir(), "e2e_5mb_video.mp4")
	if err := os.WriteFile(srcFile, rawFile, 0o644); err != nil {
		t.Fatal(err)
	}

	var mu sync.Mutex
	var progressTicks []transfer.ProgressReport
	if err := phoneEngine.SendFile(ctx, phoneSess, srcFile, func(r transfer.ProgressReport) {
		mu.Lock()
		progressTicks = append(progressTicks, r)
		mu.Unlock()
	}); err != nil {
		t.Fatalf("e2e SendFile failed: %v", err)
	}
	if len(progressTicks) < 2 {
		t.Fatalf("expected progress callbacks, got %d", len(progressTicks))
	}
	finalSender := progressTicks[len(progressTicks)-1]
	if !finalSender.Verified || finalSender.Percent != 100 || finalSender.SHA256Hex != wantHex {
		t.Fatalf("unexpected final sender report: %+v", finalSender)
	}

	select {
	case recvRep := <-fileDoneCh:
		if recvRep.Err != nil || !recvRep.Verified || recvRep.SHA256Hex != wantHex {
			t.Fatalf("unexpected receiver completion: %+v", recvRep)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("timed out waiting for receiver verified completion")
	}

	gotDisk, err := os.ReadFile(filepath.Join(recvDir, "e2e_5mb_video.mp4"))
	if err != nil || !bytes.Equal(gotDisk, rawFile) {
		t.Fatalf("received file mismatch (err=%v, len=%d)", err, len(gotDisk))
	}

	// 3. VideoFrame H.264 Annex-B streaming (Linux -> Phone) via NALSplitter
	sps := []byte{0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x80, 0x1E}
	idr := []byte{0x00, 0x00, 0x00, 0x01, 0x65, 0x88, 0x84, 0x21}
	splitter := mirror.NewNALSplitter(protocol.CodecH264)
	splitter.Feed(append(append([]byte{}, sps...), idr...), func(u mirror.NALUnit) {
		flags := protocol.FlagDirLinuxToPhone
		if u.IsConfig {
			flags |= protocol.FlagCodecConfig
		}
		if u.IsKeyframe {
			flags |= protocol.FlagKeyframe
		}
		srv.BroadcastVideoFrame(flags, protocol.VideoFrameHeader{
			StreamID: protocol.StreamLinuxToPhone,
			Codec:    protocol.CodecH264,
			Width:    1920,
			Height:   1080,
			PtsUs:    1000,
		}, u.Data)
	})
	splitter.Flush(func(u mirror.NALUnit) {
		srv.BroadcastVideoFrame(protocol.FlagDirLinuxToPhone|protocol.FlagKeyframe, protocol.VideoFrameHeader{
			StreamID: protocol.StreamLinuxToPhone,
			Codec:    protocol.CodecH264,
			Width:    1920,
			Height:   1080,
			PtsUs:    2000,
		}, u.Data)
	})

	var receivedNALs [][]byte
	for len(receivedNALs) < 2 {
		select {
		case v := <-videoRecvCh:
			receivedNALs = append(receivedNALs, v.NALData)
		case <-time.After(2 * time.Second):
			t.Fatalf("timed out waiting for video NALs (got %d)", len(receivedNALs))
		}
	}
	if !bytes.Equal(receivedNALs[0], sps) || !bytes.Equal(receivedNALs[1], idr) {
		t.Fatalf("unexpected video NALs: %x / %x", receivedNALs[0], receivedNALs[1])
	}

	// 4. InputEvent (Phone -> Linux)
	inpBytes, _ := (protocol.InputEventPayload{
		EventType:      protocol.InputTapAbs,
		ActionOrButton: 1,
		X:              32768,
		Y:              32768,
		Arg1:           2,
	}).Encode()
	if err := phoneSess.WriteFrame(protocol.PacketTypeInputEvent, 0, inpBytes); err != nil {
		t.Fatal(err)
	}
	select {
	case ev := <-inputRecvCh:
		if ev.EventType != protocol.InputTapAbs || ev.X != 32768 {
			t.Fatalf("unexpected input event: %+v", ev)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("timed out waiting for input event")
	}
}
