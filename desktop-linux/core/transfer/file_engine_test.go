package transfer

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

	"phonedrop/v2/desktop/core/protocol"
	"phonedrop/v2/desktop/core/transport"
)

func setupConnectedEngines(t *testing.T) (sender *Engine, receiver *Engine, clientSess *transport.Session, recvReports chan ProgressReport, cancel func()) {
	t.Helper()
	ctx, ctxCancel := context.WithCancel(context.Background())

	recvDir := t.TempDir()
	recvReports = make(chan ProgressReport, 512)

	var err error
	receiver, err = NewEngine(recvDir, func(r ProgressReport) {
		recvReports <- r
	})
	if err != nil {
		t.Fatalf("new receiver engine: %v", err)
	}
	sender, err = NewEngine(t.TempDir(), nil)
	if err != nil {
		t.Fatalf("new sender engine: %v", err)
	}

	srv := transport.NewTunnelServer("127.0.0.1:0", transport.Handlers{
		OnFileInit: func(s *transport.Session, init protocol.FileInitPayload) {
			receiver.HandleFileInit(s, init)
		},
		OnFileChunk: func(s *transport.Session, chunk protocol.FileChunkPayload) {
			receiver.HandleFileChunk(chunk)
		},
		OnFileDone: func(s *transport.Session, flags uint8, done protocol.FileDonePayload) {
			receiver.HandleFileDone(s, flags, done)
		},
	})
	if _, err := srv.Listen(); err != nil {
		t.Fatalf("server listen: %v", err)
	}
	go func() {
		_ = srv.Serve(ctx)
	}()

	clientSess, err = transport.DialSession(ctx, srv.Addr(), transport.Handlers{
		OnFileDone: func(s *transport.Session, flags uint8, done protocol.FileDonePayload) {
			sender.HandleFileDone(s, flags, done)
		},
	})
	if err != nil {
		t.Fatalf("dial client: %v", err)
	}

	return sender, receiver, clientSess, recvReports, func() {
		_ = clientSess.Close()
		_ = srv.Close()
		ctxCancel()
	}
}

func TestChunkedFileTransferAndSHA256Verify(t *testing.T) {
	sender, receiver, clientSess, recvReports, cleanup := setupConnectedEngines(t)
	defer cleanup()

	// 3.5 MiB + 123 bytes = 56 full 64KB chunks + 1 remainder chunk
	const fileSize = 3*1024*1024 + 512*1024 + 123
	srcData := make([]byte, fileSize)
	if _, err := rand.Read(srcData); err != nil {
		t.Fatal(err)
	}
	wantSum := sha256.Sum256(srcData)
	wantHex := hex.EncodeToString(wantSum[:])

	srcPath := filepath.Join(t.TempDir(), "backup_payload.bin")
	if err := os.WriteFile(srcPath, srcData, 0o644); err != nil {
		t.Fatal(err)
	}

	var sendMu sync.Mutex
	var sendReports []ProgressReport

	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()

	err := sender.SendFile(ctx, clientSess, srcPath, func(r ProgressReport) {
		sendMu.Lock()
		sendReports = append(sendReports, r)
		sendMu.Unlock()
	})
	if err != nil {
		t.Fatalf("SendFile failed: %v", err)
	}

	if len(sendReports) < 2 {
		t.Fatalf("expected multiple progress reports, got %d", len(sendReports))
	}
	lastSend := sendReports[len(sendReports)-1]
	if !lastSend.Verified || lastSend.Percent != 100.0 || lastSend.SHA256Hex != wantHex {
		t.Fatalf("unexpected final sender report: %+v", lastSend)
	}
	for i := 1; i < len(sendReports); i++ {
		if sendReports[i].Percent < sendReports[i-1].Percent {
			t.Fatalf("non-monotonic sender percent: %.2f -> %.2f", sendReports[i-1].Percent, sendReports[i].Percent)
		}
	}

	var finalRecv ProgressReport
	deadline := time.After(5 * time.Second)
waitRecv:
	for {
		select {
		case r := <-recvReports:
			if r.Err != nil {
				t.Fatalf("unexpected receiver error: %v", r.Err)
			}
			if r.Verified {
				finalRecv = r
				break waitRecv
			}
		case <-deadline:
			t.Fatal("timed out waiting for verified receiver completion report")
		}
	}

	if finalRecv.SHA256Hex != wantHex || finalRecv.Percent != 100.0 || finalRecv.BytesTransferred != fileSize {
		t.Fatalf("unexpected final receiver report: %+v", finalRecv)
	}

	destBytes, err := os.ReadFile(filepath.Join(receiver.DestDir(), "backup_payload.bin"))
	if err != nil {
		t.Fatalf("read received file: %v", err)
	}
	if !bytes.Equal(destBytes, srcData) {
		t.Fatalf("received file bytes differ from source (len %d vs %d)", len(destBytes), len(srcData))
	}
}

func TestZeroByteFileTransfer(t *testing.T) {
	sender, receiver, clientSess, _, cleanup := setupConnectedEngines(t)
	defer cleanup()

	srcPath := filepath.Join(t.TempDir(), "empty.txt")
	if err := os.WriteFile(srcPath, nil, 0o644); err != nil {
		t.Fatal(err)
	}

	var final ProgressReport
	err := sender.SendFile(context.Background(), clientSess, srcPath, func(r ProgressReport) {
		final = r
	})
	if err != nil {
		t.Fatalf("SendFile 0-byte failed: %v", err)
	}
	if !final.Verified || final.Percent != 100.0 || final.TotalBytes != 0 {
		t.Fatalf("unexpected 0-byte final report: %+v", final)
	}
	st, err := os.Stat(filepath.Join(receiver.DestDir(), "empty.txt"))
	if err != nil || st.Size() != 0 {
		t.Fatalf("expected 0-byte file at receiver, got st=%v err=%v", st, err)
	}
}

func TestCorruptedChunkRejectedByReceiver(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	recvDir := t.TempDir()
	recvErrCh := make(chan error, 1)
	receiver, err := NewEngine(recvDir, func(r ProgressReport) {
		if r.Err != nil {
			recvErrCh <- r.Err
		}
	})
	if err != nil {
		t.Fatal(err)
	}

	sender, err := NewEngine(t.TempDir(), nil)
	if err != nil {
		t.Fatal(err)
	}

	srv := transport.NewTunnelServer("127.0.0.1:0", transport.Handlers{
		OnFileInit: func(s *transport.Session, init protocol.FileInitPayload) {
			receiver.HandleFileInit(s, init)
		},
		OnFileChunk: func(s *transport.Session, chunk protocol.FileChunkPayload) {
			// Simulate wire bit-flip before writing chunk
			corrupt := append([]byte(nil), chunk.Data...)
			corrupt[0] ^= 0xFF
			chunk.Data = corrupt
			receiver.HandleFileChunk(chunk)
		},
		OnFileDone: func(s *transport.Session, flags uint8, done protocol.FileDonePayload) {
			receiver.HandleFileDone(s, flags, done)
		},
	})
	if _, err := srv.Listen(); err != nil {
		t.Fatal(err)
	}
	go func() {
		_ = srv.Serve(ctx)
	}()

	sess, err := transport.DialSession(ctx, srv.Addr(), transport.Handlers{
		OnFileDone: func(s *transport.Session, flags uint8, done protocol.FileDonePayload) {
			sender.HandleFileDone(s, flags, done)
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	defer sess.Close()

	srcPath := filepath.Join(t.TempDir(), "corrupt_me.bin")
	if err := os.WriteFile(srcPath, bytes.Repeat([]byte{0x5A}, 80000), 0o644); err != nil {
		t.Fatal(err)
	}

	err = sender.SendFile(ctx, sess, srcPath, nil)
	if err == nil {
		t.Fatal("expected SendFile to fail on corrupted chunk ACK")
	}

	select {
	case rErr := <-recvErrCh:
		if rErr == nil {
			t.Fatal("expected receiver error")
		}
	case <-time.After(2 * time.Second):
		t.Fatal("timed out waiting for receiver integrity failure report")
	}

	if _, err := os.Stat(filepath.Join(recvDir, "corrupt_me.bin")); !os.IsNotExist(err) {
		t.Fatalf("corrupt final file should not exist, err=%v", err)
	}
}
