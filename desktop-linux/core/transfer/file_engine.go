// Package transfer implements PhoneDrop v2's zero-buffering 64KB chunked file
// transfer engine with live MB/s progress tracking and SHA-256 FileDone ACKs.
package transfer

import (
	"context"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"hash"
	"io"
	"mime"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"phonedrop/v2/desktop/core/protocol"
)

// Direction identifies whether a transfer is outbound or inbound.
type Direction string

const (
	DirectionSend    Direction = "send"
	DirectionReceive Direction = "receive"
)

// ProgressReport reports live chunk progress, throughput, and SHA-256 verification status.
type ProgressReport struct {
	TransferID       [16]byte
	TransferIDHex    string
	FileName         string
	FilePath         string
	Direction        Direction
	BytesTransferred uint64
	TotalBytes       uint64
	Percent          float64
	SpeedMBps        float64
	ChunkIndex       uint32
	TotalChunks      uint32
	Verified         bool
	SHA256Hex        string
	Err              error
}

// FrameWriter abstracts transport.Session for streaming file packets.
type FrameWriter interface {
	WriteFrame(pktType, flags uint8, payload []byte) error
	WriteChunkFrame(flags uint8, transferID [16]byte, chunkIndex uint32, data []byte) error
}

type speedTracker struct {
	startTime  time.Time
	lastSample time.Time
	lastBytes  uint64
	emaMBps    float64
}

func newSpeedTracker(now time.Time) speedTracker {
	return speedTracker{startTime: now, lastSample: now}
}

func (st *speedTracker) update(totalBytesTransferred uint64, now time.Time) (speedMBps float64, shouldReport bool) {
	elapsed := now.Sub(st.lastSample)
	if elapsed < 50*time.Millisecond {
		return st.emaMBps, false
	}
	delta := totalBytesTransferred - st.lastBytes
	instMBps := (float64(delta) / (1024.0 * 1024.0)) / elapsed.Seconds()
	if st.emaMBps == 0 {
		st.emaMBps = instMBps
	} else {
		st.emaMBps = 0.35*instMBps + 0.65*st.emaMBps
	}
	st.lastSample = now
	st.lastBytes = totalBytesTransferred
	return st.emaMBps, true
}

func (st *speedTracker) finalSpeed(totalBytes uint64, now time.Time) float64 {
	if st.emaMBps > 0 {
		return st.emaMBps
	}
	secs := now.Sub(st.startTime).Seconds()
	if secs <= 0 {
		return 0
	}
	return (float64(totalBytes) / (1024.0 * 1024.0)) / secs
}

type inboundTransfer struct {
	mu           sync.Mutex
	id           [16]byte
	idHex        string
	fileName     string
	partPath     string
	file         *os.File
	hasher       hash.Hash
	totalBytes   uint64
	bytesWritten uint64
	totalChunks  uint32
	lastChunkIdx uint32
	speed        speedTracker
}

// Engine manages concurrent streaming file sends and receives into destDir.
type Engine struct {
	destDir     string
	ackTimeout  time.Duration
	onProgress  func(ProgressReport)
	chunkPool   sync.Pool
	mu          sync.Mutex
	inbound     map[[16]byte]*inboundTransfer
	pendingAcks map[[16]byte]chan doneAckResult
}

type doneAckResult struct {
	flags uint8
	done  protocol.FileDonePayload
}

// NewEngine creates a file transfer Engine saving received files into destDir.
func NewEngine(destDir string, onProgress func(ProgressReport)) (*Engine, error) {
	if destDir == "" {
		home, err := os.UserHomeDir()
		if err != nil {
			return nil, err
		}
		destDir = filepath.Join(home, "Downloads", "PhoneDrop")
	}
	if err := os.MkdirAll(destDir, 0o755); err != nil {
		return nil, fmt.Errorf("transfer: create destDir: %w", err)
	}
	return &Engine{
		destDir:    destDir,
		ackTimeout: 30 * time.Second,
		onProgress: onProgress,
		chunkPool: sync.Pool{
			New: func() any {
				b := make([]byte, protocol.ChunkSize)
				return &b
			},
		},
		inbound:     make(map[[16]byte]*inboundTransfer),
		pendingAcks: make(map[[16]byte]chan doneAckResult),
	}, nil
}

// DestDir returns the directory where completed incoming files are stored.
func (e *Engine) DestDir() string {
	return e.destDir
}

func (e *Engine) notify(localCb func(ProgressReport), rep ProgressReport) {
	if localCb != nil {
		localCb(rep)
	}
	if e.onProgress != nil {
		e.onProgress(rep)
	}
}

// SendFile streams filePath in fixed 64KB chunks over w, reporting live progress and
// waiting for the receiver's SHA-256 FileDone acknowledgment before completing.
func (e *Engine) SendFile(ctx context.Context, w FrameWriter, filePath string, onProgress func(ProgressReport)) error {
	f, err := os.Open(filePath)
	if err != nil {
		return err
	}
	defer f.Close()

	info, err := f.Stat()
	if err != nil {
		return err
	}
	if info.IsDir() {
		return errors.New("transfer: cannot send directory")
	}

	var tid [16]byte
	if _, err := rand.Read(tid[:]); err != nil {
		return err
	}
	tidHex := hex.EncodeToString(tid[:])
	totalSize := uint64(info.Size())
	chunkSize := uint64(protocol.ChunkSize)
	totalChunks := uint32((totalSize + chunkSize - 1) / chunkSize)
	baseName := protocol.SanitizeFileName(filepath.Base(filePath))
	mimeType := mime.TypeByExtension(filepath.Ext(baseName))
	if mimeType == "" {
		mimeType = "application/octet-stream"
	}

	ackCh := make(chan doneAckResult, 1)
	e.mu.Lock()
	e.pendingAcks[tid] = ackCh
	e.mu.Unlock()
	defer func() {
		e.mu.Lock()
		delete(e.pendingAcks, tid)
		e.mu.Unlock()
	}()

	initBytes, err := (protocol.FileInitPayload{
		TransferID:  tid,
		TotalSize:   totalSize,
		ChunkSize:   protocol.ChunkSize,
		TotalChunks: totalChunks,
		FileName:    baseName,
		MimeType:    mimeType,
	}).Encode()
	if err != nil {
		return err
	}
	if err := w.WriteFrame(protocol.PacketTypeFileInit, 0, initBytes); err != nil {
		return err
	}

	e.notify(onProgress, ProgressReport{
		TransferID:    tid,
		TransferIDHex: tidHex,
		FileName:      baseName,
		FilePath:      filePath,
		Direction:     DirectionSend,
		TotalBytes:    totalSize,
		TotalChunks:   totalChunks,
	})

	bufPtr := e.chunkPool.Get().(*[]byte)
	buf := *bufPtr
	defer e.chunkPool.Put(bufPtr)

	hasher := sha256.New()
	tracker := newSpeedTracker(time.Now())
	var sent uint64
	var chunkIdx uint32

	for {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		n, readErr := io.ReadFull(f, buf)
		if readErr != nil && !errors.Is(readErr, io.EOF) && !errors.Is(readErr, io.ErrUnexpectedEOF) {
			return readErr
		}
		if n > 0 {
			slice := buf[:n]
			_, _ = hasher.Write(slice)
			if err := w.WriteChunkFrame(0, tid, chunkIdx, slice); err != nil {
				return err
			}
			sent += uint64(n)
			chunkIdx++

			speed, shouldReport := tracker.update(sent, time.Now())
			if shouldReport || sent == totalSize {
				pct := computeIntermediatePercent(sent, totalSize)
				e.notify(onProgress, ProgressReport{
					TransferID:       tid,
					TransferIDHex:    tidHex,
					FileName:         baseName,
					FilePath:         filePath,
					Direction:        DirectionSend,
					BytesTransferred: sent,
					TotalBytes:       totalSize,
					Percent:          pct,
					SpeedMBps:        speed,
					ChunkIndex:       chunkIdx,
					TotalChunks:      totalChunks,
				})
			}
		}
		if errors.Is(readErr, io.EOF) || errors.Is(readErr, io.ErrUnexpectedEOF) {
			break
		}
	}

	var sum [32]byte
	copy(sum[:], hasher.Sum(nil))
	sumHex := hex.EncodeToString(sum[:])

	doneBytes := (protocol.FileDonePayload{
		TransferID: tid,
		TotalBytes: sent,
		SHA256:     sum,
		StatusCode: 0,
	}).Encode()
	if err := w.WriteFrame(protocol.PacketTypeFileDone, 0, doneBytes); err != nil {
		return err
	}

	timer := time.NewTimer(e.ackTimeout)
	defer timer.Stop()

	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-timer.C:
		err := errors.New("transfer: timed out waiting for receiver SHA-256 FileDone ACK")
		e.notify(onProgress, ProgressReport{
			TransferID:       tid,
			TransferIDHex:    tidHex,
			FileName:         baseName,
			Direction:        DirectionSend,
			BytesTransferred: sent,
			TotalBytes:       totalSize,
			SHA256Hex:        sumHex,
			Err:              err,
		})
		return err
	case ack := <-ackCh:
		if ack.flags&protocol.FlagError != 0 || ack.done.StatusCode != 0 || !hmac.Equal(ack.done.SHA256[:], sum[:]) {
			err := fmt.Errorf("transfer: receiver reported integrity failure (status=%d)", ack.done.StatusCode)
			e.notify(onProgress, ProgressReport{
				TransferID:       tid,
				TransferIDHex:    tidHex,
				FileName:         baseName,
				Direction:        DirectionSend,
				BytesTransferred: sent,
				TotalBytes:       totalSize,
				SHA256Hex:        sumHex,
				Err:              err,
			})
			return err
		}
		e.notify(onProgress, ProgressReport{
			TransferID:       tid,
			TransferIDHex:    tidHex,
			FileName:         baseName,
			FilePath:         filePath,
			Direction:        DirectionSend,
			BytesTransferred: sent,
			TotalBytes:       totalSize,
			Percent:          100.0,
			SpeedMBps:        tracker.finalSpeed(sent, time.Now()),
			ChunkIndex:       chunkIdx,
			TotalChunks:      totalChunks,
			Verified:         true,
			SHA256Hex:        sumHex,
		})
		return nil
	}
}

// HandleFileInit initializes a streaming .part file on disk for an incoming transfer.
func (e *Engine) HandleFileInit(_ FrameWriter, init protocol.FileInitPayload) {
	tidHex := hex.EncodeToString(init.TransferID[:])
	safeName := protocol.SanitizeFileName(init.FileName)
	partPath := filepath.Join(e.destDir, fmt.Sprintf(".phonedrop-%s.part", tidHex))

	f, err := os.OpenFile(partPath, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o644)
	if err != nil {
		e.notify(nil, ProgressReport{
			TransferID:    init.TransferID,
			TransferIDHex: tidHex,
			FileName:      safeName,
			Direction:     DirectionReceive,
			TotalBytes:    init.TotalSize,
			Err:           err,
		})
		return
	}

	tr := &inboundTransfer{
		id:          init.TransferID,
		idHex:       tidHex,
		fileName:    safeName,
		partPath:    partPath,
		file:        f,
		hasher:      sha256.New(),
		totalBytes:  init.TotalSize,
		totalChunks: init.TotalChunks,
		speed:       newSpeedTracker(time.Now()),
	}

	e.mu.Lock()
	if prev, exists := e.inbound[init.TransferID]; exists {
		_ = prev.file.Close()
		_ = os.Remove(prev.partPath)
	}
	e.inbound[init.TransferID] = tr
	e.mu.Unlock()

	e.notify(nil, ProgressReport{
		TransferID:    init.TransferID,
		TransferIDHex: tidHex,
		FileName:      safeName,
		Direction:     DirectionReceive,
		TotalBytes:    init.TotalSize,
		TotalChunks:   init.TotalChunks,
	})
}

// HandleFileChunk writes a 64KB chunk directly to disk and updates incremental SHA-256.
func (e *Engine) HandleFileChunk(chunk protocol.FileChunkPayload) {
	e.mu.Lock()
	tr := e.inbound[chunk.TransferID]
	e.mu.Unlock()
	if tr == nil {
		return
	}

	tr.mu.Lock()
	defer tr.mu.Unlock()
	if tr.file == nil {
		return
	}

	if _, err := tr.file.Write(chunk.Data); err != nil {
		e.notify(nil, ProgressReport{
			TransferID:    tr.id,
			TransferIDHex: tr.idHex,
			FileName:      tr.fileName,
			Direction:     DirectionReceive,
			TotalBytes:    tr.totalBytes,
			Err:           err,
		})
		return
	}
	_, _ = tr.hasher.Write(chunk.Data)
	tr.bytesWritten += uint64(len(chunk.Data))
	tr.lastChunkIdx = chunk.ChunkIndex + 1

	speed, shouldReport := tr.speed.update(tr.bytesWritten, time.Now())
	if shouldReport || tr.bytesWritten == tr.totalBytes {
		e.notify(nil, ProgressReport{
			TransferID:       tr.id,
			TransferIDHex:    tr.idHex,
			FileName:         tr.fileName,
			Direction:        DirectionReceive,
			BytesTransferred: tr.bytesWritten,
			TotalBytes:       tr.totalBytes,
			Percent:          computeIntermediatePercent(tr.bytesWritten, tr.totalBytes),
			SpeedMBps:        speed,
			ChunkIndex:       tr.lastChunkIdx,
			TotalChunks:      tr.totalChunks,
		})
	}
}

// HandleFileDone either resolves a sender's pending ACK or verifies and finalizes an inbound file.
func (e *Engine) HandleFileDone(w FrameWriter, flags uint8, done protocol.FileDonePayload) {
	if flags&protocol.FlagAck != 0 {
		e.mu.Lock()
		ch := e.pendingAcks[done.TransferID]
		e.mu.Unlock()
		if ch != nil {
			select {
			case ch <- doneAckResult{flags: flags, done: done}:
			default:
			}
		}
		return
	}

	e.mu.Lock()
	tr := e.inbound[done.TransferID]
	delete(e.inbound, done.TransferID)
	e.mu.Unlock()

	if tr == nil {
		if w != nil {
			ack := protocol.FileDonePayload{TransferID: done.TransferID, StatusCode: 2}
			_ = w.WriteFrame(protocol.PacketTypeFileDone, protocol.FlagAck|protocol.FlagError, ack.Encode())
		}
		return
	}

	tr.mu.Lock()
	var syncErr error
	if tr.file != nil {
		syncErr = tr.file.Sync()
		if closeErr := tr.file.Close(); closeErr != nil && syncErr == nil {
			syncErr = closeErr
		}
		tr.file = nil
	}
	var computed [32]byte
	copy(computed[:], tr.hasher.Sum(nil))
	bytesWritten := tr.bytesWritten
	partPath := tr.partPath
	fileName := tr.fileName
	totalBytes := tr.totalBytes
	totalChunks := tr.totalChunks
	lastChunkIdx := tr.lastChunkIdx
	finalSpeed := tr.speed.finalSpeed(bytesWritten, time.Now())
	tr.mu.Unlock()

	valid := syncErr == nil && bytesWritten == done.TotalBytes && hmac.Equal(computed[:], done.SHA256[:])
	computedHex := hex.EncodeToString(computed[:])

	if !valid {
		_ = os.Remove(partPath)
		if w != nil {
			ack := protocol.FileDonePayload{
				TransferID: done.TransferID,
				TotalBytes: bytesWritten,
				SHA256:     computed,
				StatusCode: 1,
			}
			_ = w.WriteFrame(protocol.PacketTypeFileDone, protocol.FlagAck|protocol.FlagError, ack.Encode())
		}
		e.notify(nil, ProgressReport{
			TransferID:       done.TransferID,
			TransferIDHex:    hex.EncodeToString(done.TransferID[:]),
			FileName:         fileName,
			Direction:        DirectionReceive,
			BytesTransferred: bytesWritten,
			TotalBytes:       totalBytes,
			SHA256Hex:        computedHex,
			Err:              fmt.Errorf("transfer: integrity check failed (bytes %d/%d)", bytesWritten, done.TotalBytes),
		})
		return
	}

	finalPath := uniqueDestPath(e.destDir, fileName)
	if err := os.Rename(partPath, finalPath); err != nil {
		_ = os.Remove(partPath)
		if w != nil {
			ack := protocol.FileDonePayload{TransferID: done.TransferID, TotalBytes: bytesWritten, SHA256: computed, StatusCode: 2}
			_ = w.WriteFrame(protocol.PacketTypeFileDone, protocol.FlagAck|protocol.FlagError, ack.Encode())
		}
		e.notify(nil, ProgressReport{
			TransferID:    done.TransferID,
			TransferIDHex: hex.EncodeToString(done.TransferID[:]),
			FileName:      fileName,
			Direction:     DirectionReceive,
			Err:           err,
		})
		return
	}

	if w != nil {
		ack := protocol.FileDonePayload{
			TransferID: done.TransferID,
			TotalBytes: bytesWritten,
			SHA256:     computed,
			StatusCode: 0,
		}
		_ = w.WriteFrame(protocol.PacketTypeFileDone, protocol.FlagAck, ack.Encode())
	}

	e.notify(nil, ProgressReport{
		TransferID:       done.TransferID,
		TransferIDHex:    hex.EncodeToString(done.TransferID[:]),
		FileName:         filepath.Base(finalPath),
		FilePath:         finalPath,
		Direction:        DirectionReceive,
		BytesTransferred: bytesWritten,
		TotalBytes:       totalBytes,
		Percent:          100.0,
		SpeedMBps:        finalSpeed,
		ChunkIndex:       lastChunkIdx,
		TotalChunks:      totalChunks,
		Verified:         true,
		SHA256Hex:        computedHex,
	})
}

func computeIntermediatePercent(done, total uint64) float64 {
	if total == 0 {
		return 99.9
	}
	pct := (float64(done) * 100.0) / float64(total)
	if pct >= 99.9 {
		return 99.9
	}
	return pct
}

func uniqueDestPath(dir, name string) string {
	candidate := filepath.Join(dir, name)
	if _, err := os.Stat(candidate); errors.Is(err, os.ErrNotExist) {
		return candidate
	}
	ext := filepath.Ext(name)
	stem := strings.TrimSuffix(name, ext)
	for i := 1; i < 10000; i++ {
		alt := filepath.Join(dir, fmt.Sprintf("%s (%d)%s", stem, i, ext))
		if _, err := os.Stat(alt); errors.Is(err, os.ErrNotExist) {
			return alt
		}
	}
	return filepath.Join(dir, fmt.Sprintf("%d-%s", time.Now().UnixNano(), name))
}
