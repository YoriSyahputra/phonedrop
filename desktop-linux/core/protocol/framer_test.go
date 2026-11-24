package protocol

import (
	"bytes"
	"crypto/sha256"
	"encoding/binary"
	"errors"
	"io"
	"testing"
)

func TestAllPacketTypesRoundTrip(t *testing.T) {
	framer := NewFramer()
	var wire bytes.Buffer

	// 0x01 Clip
	clipText := "Halo PhoneDrop v2 — UTF-8 clipboard test 🚀"
	clipIn := ClipPayload{
		TimestampMs: 1732490000123,
		OriginHash:  ComputeClipOriginHash(clipText),
		Mime:        "text/plain;charset=utf-8",
		Text:        clipText,
	}
	clipBytes, err := clipIn.Encode()
	if err != nil {
		t.Fatalf("encode clip: %v", err)
	}
	if err := framer.WriteFrame(&wire, PacketTypeClip, FlagDirLinuxToPhone, clipBytes); err != nil {
		t.Fatalf("write clip: %v", err)
	}

	// 0x02 FileInit
	var tid [16]byte
	for i := range tid {
		tid[i] = byte(i + 1)
	}
	initIn := FileInitPayload{
		TransferID:  tid,
		TotalSize:   131072,
		ChunkSize:   ChunkSize,
		TotalChunks: 2,
		FileName:    "../../etc/passwd_backup.tar.gz",
		MimeType:    "application/gzip",
	}
	initBytes, err := initIn.Encode()
	if err != nil {
		t.Fatalf("encode file init: %v", err)
	}
	if err := framer.WriteFrame(&wire, PacketTypeFileInit, 0, initBytes); err != nil {
		t.Fatalf("write file init: %v", err)
	}

	// 0x03 FileChunk (64 KiB)
	chunkData := bytes.Repeat([]byte{0xAB}, int(ChunkSize))
	if err := framer.WriteChunkFrame(&wire, 0, tid, 0, chunkData); err != nil {
		t.Fatalf("write chunk: %v", err)
	}

	// 0x04 FileDone
	sum := sha256.Sum256(chunkData)
	doneIn := FileDonePayload{
		TransferID: tid,
		TotalBytes: uint64(len(chunkData)),
		SHA256:     sum,
		StatusCode: 0,
	}
	if err := framer.WriteFrame(&wire, PacketTypeFileDone, FlagAck, doneIn.Encode()); err != nil {
		t.Fatalf("write file done: %v", err)
	}

	// 0x05 VideoFrame
	nal := []byte{0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0xC0, 0x1F}
	vidHdr := VideoFrameHeader{
		StreamID: StreamLinuxToPhone,
		Codec:    CodecH264,
		Width:    1920,
		Height:   1080,
		PtsUs:    987654321,
	}
	if err := framer.WriteVideoFrame(&wire, FlagDirLinuxToPhone|FlagCodecConfig, vidHdr, nal); err != nil {
		t.Fatalf("write video frame: %v", err)
	}

	// 0x06 InputEvent
	inpIn := InputEventPayload{
		EventType:      InputTapAbs,
		ActionOrButton: 1,
		X:              32768,
		Y:              16384,
		Arg1:           1,
		Arg2:           0,
		Text:           "Enter",
	}
	inpBytes, err := inpIn.Encode()
	if err != nil {
		t.Fatalf("encode input: %v", err)
	}
	if err := framer.WriteFrame(&wire, PacketTypeInputEvent, 0, inpBytes); err != nil {
		t.Fatalf("write input: %v", err)
	}

	// 0x07 Control
	ctrlIn := ControlMessage{
		Op: ControlOpFileList,
		Files: []RemoteFileEntry{
			{Name: "notes.pdf", Size: 2048, ModMs: 1732490000000},
		},
	}
	ctrlBytes, err := ctrlIn.Encode()
	if err != nil {
		t.Fatalf("encode control: %v", err)
	}
	if err := framer.WriteFrame(&wire, PacketTypeControl, FlagDirLinuxToPhone, ctrlBytes); err != nil {
		t.Fatalf("write control: %v", err)
	}

	// Verify reads sequentially
	r := bytes.NewReader(wire.Bytes())

	// 1. Clip
	f1, err := ReadFrame(r)
	if err != nil {
		t.Fatalf("read clip: %v", err)
	}
	defer f1.Release()
	if f1.Header.Type != PacketTypeClip || f1.Header.SeqID != 1 {
		t.Fatalf("unexpected clip header: %+v", f1.Header)
	}
	clipOut, err := DecodeClipPayload(f1.Payload)
	if err != nil || clipOut != clipIn {
		t.Fatalf("clip mismatch: %+v vs %+v (err=%v)", clipOut, clipIn, err)
	}

	// 2. FileInit
	f2, err := ReadFrame(r)
	if err != nil {
		t.Fatalf("read file init: %v", err)
	}
	defer f2.Release()
	initOut, err := DecodeFileInitPayload(f2.Payload)
	if err != nil {
		t.Fatalf("decode file init: %v", err)
	}
	if initOut.FileName != "passwd_backup.tar.gz" || initOut.ChunkSize != ChunkSize || initOut.TransferID != tid {
		t.Fatalf("unexpected file init decoded: %+v", initOut)
	}

	// 3. FileChunk
	f3, err := ReadFrame(r)
	if err != nil {
		t.Fatalf("read chunk: %v", err)
	}
	chunkOut, err := DecodeFileChunkPayload(f3.Payload)
	if err != nil {
		t.Fatalf("decode chunk: %v", err)
	}
	if chunkOut.TransferID != tid || chunkOut.ChunkIndex != 0 || !bytes.Equal(chunkOut.Data, chunkData) {
		t.Fatalf("chunk mismatch: idx=%d len=%d", chunkOut.ChunkIndex, len(chunkOut.Data))
	}
	f3.Release()

	// 4. FileDone
	f4, err := ReadFrame(r)
	if err != nil {
		t.Fatalf("read done: %v", err)
	}
	defer f4.Release()
	if f4.Header.Flags&FlagAck == 0 {
		t.Fatalf("expected FlagAck on FileDone")
	}
	doneOut, err := DecodeFileDonePayload(f4.Payload)
	if err != nil || doneOut != doneIn {
		t.Fatalf("done mismatch: %+v vs %+v (err=%v)", doneOut, doneIn, err)
	}

	// 5. VideoFrame
	f5, err := ReadFrame(r)
	if err != nil {
		t.Fatalf("read video: %v", err)
	}
	defer f5.Release()
	vidOut, err := DecodeVideoFramePayload(f5.Payload)
	if err != nil {
		t.Fatalf("decode video: %v", err)
	}
	if vidOut.Header != vidHdr || !bytes.Equal(vidOut.NALData, nal) {
		t.Fatalf("video mismatch: %+v", vidOut)
	}

	// 6. InputEvent
	f6, err := ReadFrame(r)
	if err != nil {
		t.Fatalf("read input: %v", err)
	}
	defer f6.Release()
	inpOut, err := DecodeInputEventPayload(f6.Payload)
	if err != nil || inpOut != inpIn {
		t.Fatalf("input mismatch: %+v vs %+v (err=%v)", inpOut, inpIn, err)
	}

	// 7. Control
	f7, err := ReadFrame(r)
	if err != nil {
		t.Fatalf("read control: %v", err)
	}
	defer f7.Release()
	ctrlOut, err := DecodeControlMessage(f7.Payload)
	if err != nil || ctrlOut.Op != ControlOpFileList || len(ctrlOut.Files) != 1 || ctrlOut.Files[0].Name != "notes.pdf" {
		t.Fatalf("control mismatch: %+v (err=%v)", ctrlOut, err)
	}

	if _, err := ReadFrame(r); !errors.Is(err, io.EOF) {
		t.Fatalf("expected EOF, got %v", err)
	}
}

func TestHeaderValidationErrors(t *testing.T) {
	var raw [HeaderSize]byte
	EncodeHeader(raw[:], Header{
		Version:    ProtocolVersion,
		Type:       PacketTypeClip,
		SeqID:      1,
		PayloadLen: 10,
	})

	// Bad magic
	badMagic := raw
	badMagic[0] = 'X'
	if _, err := ReadFrame(bytes.NewReader(badMagic[:])); !errors.Is(err, ErrInvalidMagic) {
		t.Fatalf("expected ErrInvalidMagic, got %v", err)
	}

	// Bad version
	badVer := raw
	badVer[4] = 0x99
	if _, err := ReadFrame(bytes.NewReader(badVer[:])); !errors.Is(err, ErrUnsupportedVer) {
		t.Fatalf("expected ErrUnsupportedVer, got %v", err)
	}

	// Bad type
	badType := raw
	badType[5] = 0x77
	if _, err := ReadFrame(bytes.NewReader(badType[:])); !errors.Is(err, ErrUnknownType) {
		t.Fatalf("expected ErrUnknownType, got %v", err)
	}

	// Oversized payload
	oversize := raw
	binary.BigEndian.PutUint32(oversize[12:16], MaxPayloadSize+1)
	if _, err := ReadFrame(bytes.NewReader(oversize[:])); !errors.Is(err, ErrPayloadTooLarge) {
		t.Fatalf("expected ErrPayloadTooLarge, got %v", err)
	}

	// Truncated payload
	trunc := append(raw[:], []byte{1, 2, 3}...)
	if _, err := ReadFrame(bytes.NewReader(trunc)); !errors.Is(err, io.ErrUnexpectedEOF) {
		t.Fatalf("expected ErrUnexpectedEOF, got %v", err)
	}
}

func TestReadFramePooledAllocs(t *testing.T) {
	framer := NewFramer()
	var tid [16]byte
	chunk := bytes.Repeat([]byte{0x42}, int(ChunkSize))

	var wire bytes.Buffer
	if err := framer.WriteChunkFrame(&wire, 0, tid, 7, chunk); err != nil {
		t.Fatal(err)
	}
	payloadBytes := wire.Bytes()

	// Warm up pool
	f, err := ReadFrame(bytes.NewReader(payloadBytes))
	if err != nil {
		t.Fatal(err)
	}
	f.Release()

	r := bytes.NewReader(payloadBytes)
	buf := make([]byte, ChunkSize+256)
	origPtr := &buf[0]
	allocs := testing.AllocsPerRun(20, func() {
		r.Reset(payloadBytes)
		var fr *Frame
		fr, buf, err = ReadFrameInto(r, buf)
		if err != nil {
			t.Fatal(err)
		}
		if len(fr.Payload) == 0 {
			t.Fatal("empty payload")
		}
	})
	if &buf[0] != origPtr {
		t.Fatalf("expected ReadFrameInto to reuse backing 64KB buffer without reallocation")
	}
	if allocs > 4 {
		t.Fatalf("expected minimal allocs for ReadFrameInto (zero payload realloc), got %.1f", allocs)
	}
}
