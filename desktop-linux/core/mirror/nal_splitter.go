// Package mirror implements bi-directional H.264/H.265 screen capture,
// Annex-B NAL unit splitting, embedded mpv IPC playback, and ADB scrcpy fallback.
package mirror

import (
	"bytes"

	"phonedrop/v2/desktop/core/protocol"
)

// NALUnit represents one complete Annex-B NAL unit with its start code prefix.
type NALUnit struct {
	Data       []byte
	IsConfig   bool
	IsKeyframe bool
}

// NALSplitter incrementally parses raw Annex-B H.264/H.265 byte streams (e.g., from ffmpeg stdout)
// and emits complete start-code-prefixed NAL units.
type NALSplitter struct {
	codec uint8
	buf   []byte
}

// NewNALSplitter creates a splitter for protocol.CodecH264 or protocol.CodecH265.
func NewNALSplitter(codec uint8) *NALSplitter {
	return &NALSplitter{
		codec: codec,
		buf:   make([]byte, 0, 256*1024),
	}
}

// Feed appends raw Annex-B bytes and invokes onNAL for each completed NAL unit.
func (s *NALSplitter) Feed(data []byte, onNAL func(NALUnit)) {
	if len(data) == 0 {
		return
	}
	s.buf = append(s.buf, data...)

	firstIdx, firstLen := findStartCode(s.buf, 0)
	if firstIdx < 0 {
		if len(s.buf) > 4 {
			// Retain trailing 3 bytes in case a start code spans across Feed calls.
			copy(s.buf, s.buf[len(s.buf)-3:])
			s.buf = s.buf[:3]
		}
		return
	}

	if firstIdx > 0 {
		// Discard leading pre-start-code garbage.
		copy(s.buf, s.buf[firstIdx:])
		s.buf = s.buf[:len(s.buf)-firstIdx]
		firstIdx = 0
	}

	cursor := firstIdx + firstLen
	for {
		nextIdx, nextLen := findStartCode(s.buf, cursor)
		if nextIdx < 0 {
			break
		}
		nalBytes := s.buf[firstIdx:nextIdx]
		s.emit(nalBytes, firstLen, onNAL)

		firstIdx = nextIdx
		firstLen = nextLen
		cursor = firstIdx + firstLen
	}

	if firstIdx > 0 {
		rem := len(s.buf) - firstIdx
		copy(s.buf, s.buf[firstIdx:])
		s.buf = s.buf[:rem]
	}
}

// Flush emits any remaining buffered NAL unit at EOF.
func (s *NALSplitter) Flush(onNAL func(NALUnit)) {
	idx, scLen := findStartCode(s.buf, 0)
	if idx >= 0 && idx+scLen < len(s.buf) {
		s.emit(s.buf[idx:], scLen, onNAL)
	}
	s.buf = s.buf[:0]
}

func (s *NALSplitter) emit(nal []byte, scLen int, onNAL func(NALUnit)) {
	if len(nal) <= scLen || onNAL == nil {
		return
	}
	cp := make([]byte, len(nal))
	copy(cp, nal)
	isCfg, isKey := classifyNAL(s.codec, cp[scLen])
	onNAL(NALUnit{
		Data:       cp,
		IsConfig:   isCfg,
		IsKeyframe: isKey,
	})
}

func classifyNAL(codec uint8, nalHeader byte) (isConfig bool, isKeyframe bool) {
	if codec == protocol.CodecH265 {
		t := (nalHeader >> 1) & 0x3F
		return t == 32 || t == 33 || t == 34, t == 19 || t == 20 || t == 21
	}
	t := nalHeader & 0x1F
	return t == 7 || t == 8, t == 5
}

func findStartCode(b []byte, from int) (idx int, scLen int) {
	if from < 0 {
		from = 0
	}
	var sc3 = []byte{0x00, 0x00, 0x01}
	pos := bytes.Index(b[from:], sc3)
	if pos < 0 {
		return -1, 0
	}
	abs := from + pos
	if abs > 0 && b[abs-1] == 0x00 {
		return abs - 1, 4
	}
	return abs, 3
}
