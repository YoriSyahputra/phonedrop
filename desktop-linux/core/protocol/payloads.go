package protocol

import (
	"crypto/sha256"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"path/filepath"
	"strings"
)

const (
	// FileDonePayloadSize is the fixed 57-byte size of PacketFileDone.
	FileDonePayloadSize  = 57
	videoFrameHeaderSize = 14
	inputHeaderMinSize   = 22
	fileChunkHeaderSize  = 24
)

// Video stream identifiers and codec constants for PacketTypeVideoFrame.
const (
	StreamPhoneToLinux uint8 = 0x01
	StreamLinuxToPhone uint8 = 0x02

	CodecH264 uint8 = 0x01
	CodecH265 uint8 = 0x02
)

// Input event types for PacketTypeInputEvent.
const (
	InputMoveRel     uint8 = 0x01
	InputTapAbs      uint8 = 0x02
	InputMouseButton uint8 = 0x03
	InputScroll      uint8 = 0x04
	InputKeyCode     uint8 = 0x05
	InputTextUTF8    uint8 = 0x06
	InputSwipeAbs    uint8 = 0x07
)

var ErrMalformedPayload = errors.New("protocol: malformed payload")

// ClipPayload represents a 0x01 PacketClip payload.
type ClipPayload struct {
	TimestampMs uint64
	OriginHash  [8]byte
	Mime        string
	Text        string
}

// ComputeClipOriginHash computes the deterministic 8-byte SHA-256 prefix for anti-loop suppression.
func ComputeClipOriginHash(text string) [8]byte {
	sum := sha256.Sum256([]byte(text))
	var out [8]byte
	copy(out[:], sum[:8])
	return out
}

// Encode serializes ClipPayload into Big-Endian wire bytes.
func (p ClipPayload) Encode() ([]byte, error) {
	if len(p.Mime) > math.MaxUint16 {
		return nil, fmt.Errorf("%w: clip mime too long", ErrMalformedPayload)
	}
	total := 8 + 8 + 2 + len(p.Mime) + 4 + len(p.Text)
	if uint64(total) > uint64(MaxPayloadSize) {
		return nil, ErrPayloadTooLarge
	}
	buf := make([]byte, total)
	binary.BigEndian.PutUint64(buf[0:8], p.TimestampMs)
	copy(buf[8:16], p.OriginHash[:])
	binary.BigEndian.PutUint16(buf[16:18], uint16(len(p.Mime)))
	off := 18
	copy(buf[off:off+len(p.Mime)], p.Mime)
	off += len(p.Mime)
	binary.BigEndian.PutUint32(buf[off:off+4], uint32(len(p.Text)))
	off += 4
	copy(buf[off:], p.Text)
	return buf, nil
}

// DecodeClipPayload parses a 0x01 PacketClip payload.
func DecodeClipPayload(b []byte) (ClipPayload, error) {
	if len(b) < 22 {
		return ClipPayload{}, fmt.Errorf("%w: clip too short (%d)", ErrMalformedPayload, len(b))
	}
	var p ClipPayload
	p.TimestampMs = binary.BigEndian.Uint64(b[0:8])
	copy(p.OriginHash[:], b[8:16])
	mimeLen := int(binary.BigEndian.Uint16(b[16:18]))
	off := 18
	if len(b) < off+mimeLen+4 {
		return ClipPayload{}, fmt.Errorf("%w: clip mime bounds", ErrMalformedPayload)
	}
	p.Mime = string(b[off : off+mimeLen])
	off += mimeLen
	textLen := binary.BigEndian.Uint32(b[off : off+4])
	off += 4
	if uint64(off)+uint64(textLen) != uint64(len(b)) {
		return ClipPayload{}, fmt.Errorf("%w: clip text bounds", ErrMalformedPayload)
	}
	p.Text = string(b[off:])
	return p, nil
}

// FileInitPayload represents a 0x02 PacketFileInit payload.
type FileInitPayload struct {
	TransferID  [16]byte
	TotalSize   uint64
	ChunkSize   uint32
	TotalChunks uint32
	FileName    string
	MimeType    string
}

// SanitizeFileName strips directory traversal and returns a safe basename.
func SanitizeFileName(name string) string {
	name = strings.ReplaceAll(name, "\\", "/")
	base := filepath.Base(strings.TrimSpace(name))
	if base == "" || base == "." || base == "/" || base == ".." {
		return "phonedrop-file.bin"
	}
	return base
}

// Encode serializes FileInitPayload into Big-Endian wire bytes.
func (p FileInitPayload) Encode() ([]byte, error) {
	name := SanitizeFileName(p.FileName)
	if len(name) > math.MaxUint16 || len(p.MimeType) > math.MaxUint16 {
		return nil, fmt.Errorf("%w: filename/mime exceeds uint16", ErrMalformedPayload)
	}
	total := 16 + 8 + 4 + 4 + 2 + len(name) + 2 + len(p.MimeType)
	buf := make([]byte, total)
	copy(buf[0:16], p.TransferID[:])
	binary.BigEndian.PutUint64(buf[16:24], p.TotalSize)
	binary.BigEndian.PutUint32(buf[24:28], p.ChunkSize)
	binary.BigEndian.PutUint32(buf[28:32], p.TotalChunks)
	binary.BigEndian.PutUint16(buf[32:34], uint16(len(name)))
	off := 34
	copy(buf[off:off+len(name)], name)
	off += len(name)
	binary.BigEndian.PutUint16(buf[off:off+2], uint16(len(p.MimeType)))
	off += 2
	copy(buf[off:], p.MimeType)
	return buf, nil
}

// DecodeFileInitPayload parses a 0x02 PacketFileInit payload.
func DecodeFileInitPayload(b []byte) (FileInitPayload, error) {
	if len(b) < 36 {
		return FileInitPayload{}, fmt.Errorf("%w: file init too short (%d)", ErrMalformedPayload, len(b))
	}
	var p FileInitPayload
	copy(p.TransferID[:], b[0:16])
	p.TotalSize = binary.BigEndian.Uint64(b[16:24])
	p.ChunkSize = binary.BigEndian.Uint32(b[24:28])
	p.TotalChunks = binary.BigEndian.Uint32(b[28:32])
	nameLen := int(binary.BigEndian.Uint16(b[32:34]))
	off := 34
	if len(b) < off+nameLen+2 {
		return FileInitPayload{}, fmt.Errorf("%w: file init name bounds", ErrMalformedPayload)
	}
	p.FileName = SanitizeFileName(string(b[off : off+nameLen]))
	off += nameLen
	mimeLen := int(binary.BigEndian.Uint16(b[off : off+2]))
	off += 2
	if len(b) != off+mimeLen {
		return FileInitPayload{}, fmt.Errorf("%w: file init mime bounds", ErrMalformedPayload)
	}
	p.MimeType = string(b[off:])
	return p, nil
}

// FileChunkPayload represents a decoded 0x03 PacketFileChunk view referencing the frame slice.
type FileChunkPayload struct {
	TransferID [16]byte
	ChunkIndex uint32
	Data       []byte
}

// DecodeFileChunkPayload parses a 0x03 PacketFileChunk payload without copying chunk bytes.
func DecodeFileChunkPayload(b []byte) (FileChunkPayload, error) {
	if len(b) < fileChunkHeaderSize {
		return FileChunkPayload{}, fmt.Errorf("%w: file chunk too short (%d)", ErrMalformedPayload, len(b))
	}
	var p FileChunkPayload
	copy(p.TransferID[:], b[0:16])
	p.ChunkIndex = binary.BigEndian.Uint32(b[16:20])
	dataLen := binary.BigEndian.Uint32(b[20:24])
	if dataLen == 0 || dataLen > ChunkSize {
		return FileChunkPayload{}, fmt.Errorf("%w: invalid chunk dataLen=%d", ErrMalformedPayload, dataLen)
	}
	if uint64(fileChunkHeaderSize)+uint64(dataLen) != uint64(len(b)) {
		return FileChunkPayload{}, fmt.Errorf("%w: chunk data length mismatch (%d vs %d)", ErrMalformedPayload, dataLen, len(b)-fileChunkHeaderSize)
	}
	p.Data = b[fileChunkHeaderSize:]
	return p, nil
}

// FileDonePayload represents a 0x04 PacketFileDone payload (57 bytes fixed).
type FileDonePayload struct {
	TransferID [16]byte
	TotalBytes uint64
	SHA256     [32]byte
	StatusCode uint8
}

// Encode serializes FileDonePayload into fixed 57-byte Big-Endian wire format.
func (p FileDonePayload) Encode() []byte {
	buf := make([]byte, FileDonePayloadSize)
	copy(buf[0:16], p.TransferID[:])
	binary.BigEndian.PutUint64(buf[16:24], p.TotalBytes)
	copy(buf[24:56], p.SHA256[:])
	buf[56] = p.StatusCode
	return buf
}

// DecodeFileDonePayload parses a 0x04 PacketFileDone payload.
func DecodeFileDonePayload(b []byte) (FileDonePayload, error) {
	if len(b) != FileDonePayloadSize {
		return FileDonePayload{}, fmt.Errorf("%w: file done length=%d expected=%d", ErrMalformedPayload, len(b), FileDonePayloadSize)
	}
	var p FileDonePayload
	copy(p.TransferID[:], b[0:16])
	p.TotalBytes = binary.BigEndian.Uint64(b[16:24])
	copy(p.SHA256[:], b[24:56])
	p.StatusCode = b[56]
	return p, nil
}

// VideoFrameHeader describes metadata preceding Annex-B NAL bytes in 0x05 PacketVideoFrame.
type VideoFrameHeader struct {
	StreamID uint8
	Codec    uint8
	Width    uint16
	Height   uint16
	PtsUs    uint64
}

// VideoFramePayload pairs VideoFrameHeader with a zero-copy NALData view.
type VideoFramePayload struct {
	Header  VideoFrameHeader
	NALData []byte
}

func encodeVideoHeader(dst []byte, h VideoFrameHeader) {
	dst[0] = h.StreamID
	dst[1] = h.Codec
	binary.BigEndian.PutUint16(dst[2:4], h.Width)
	binary.BigEndian.PutUint16(dst[4:6], h.Height)
	binary.BigEndian.PutUint64(dst[6:14], h.PtsUs)
}

// Encode serializes VideoFramePayload into wire bytes.
func (p VideoFramePayload) Encode() ([]byte, error) {
	if uint64(videoFrameHeaderSize)+uint64(len(p.NALData)) > uint64(MaxPayloadSize) {
		return nil, ErrPayloadTooLarge
	}
	buf := make([]byte, videoFrameHeaderSize+len(p.NALData))
	encodeVideoHeader(buf[:videoFrameHeaderSize], p.Header)
	copy(buf[videoFrameHeaderSize:], p.NALData)
	return buf, nil
}

// DecodeVideoFramePayload parses a 0x05 PacketVideoFrame payload.
func DecodeVideoFramePayload(b []byte) (VideoFramePayload, error) {
	if len(b) < videoFrameHeaderSize {
		return VideoFramePayload{}, fmt.Errorf("%w: video frame too short (%d)", ErrMalformedPayload, len(b))
	}
	var p VideoFramePayload
	p.Header.StreamID = b[0]
	p.Header.Codec = b[1]
	p.Header.Width = binary.BigEndian.Uint16(b[2:4])
	p.Header.Height = binary.BigEndian.Uint16(b[4:6])
	p.Header.PtsUs = binary.BigEndian.Uint64(b[6:14])
	p.NALData = b[videoFrameHeaderSize:]
	return p, nil
}

// InputEventPayload represents a 0x06 PacketInputEvent payload.
type InputEventPayload struct {
	EventType      uint8
	ActionOrButton uint8
	X              int32
	Y              int32
	Arg1           int32
	Arg2           int32
	Text           string
}

// Encode serializes InputEventPayload into Big-Endian wire bytes.
func (p InputEventPayload) Encode() ([]byte, error) {
	if len(p.Text) > math.MaxUint16 {
		return nil, fmt.Errorf("%w: input text exceeds uint16", ErrMalformedPayload)
	}
	buf := make([]byte, inputHeaderMinSize+len(p.Text))
	buf[0] = p.EventType
	buf[1] = p.ActionOrButton
	binary.BigEndian.PutUint32(buf[2:6], uint32(p.X))
	binary.BigEndian.PutUint32(buf[6:10], uint32(p.Y))
	binary.BigEndian.PutUint32(buf[10:14], uint32(p.Arg1))
	binary.BigEndian.PutUint32(buf[14:18], uint32(p.Arg2))
	binary.BigEndian.PutUint16(buf[18:20], uint16(len(p.Text)))
	// Bytes 20..21 are reserved padding aligned to 22-byte header.
	copy(buf[inputHeaderMinSize:], p.Text)
	return buf, nil
}

// DecodeInputEventPayload parses a 0x06 PacketInputEvent payload.
func DecodeInputEventPayload(b []byte) (InputEventPayload, error) {
	if len(b) < inputHeaderMinSize {
		return InputEventPayload{}, fmt.Errorf("%w: input event too short (%d)", ErrMalformedPayload, len(b))
	}
	var p InputEventPayload
	p.EventType = b[0]
	p.ActionOrButton = b[1]
	p.X = int32(binary.BigEndian.Uint32(b[2:6]))
	p.Y = int32(binary.BigEndian.Uint32(b[6:10]))
	p.Arg1 = int32(binary.BigEndian.Uint32(b[10:14]))
	p.Arg2 = int32(binary.BigEndian.Uint32(b[14:18]))
	textLen := int(binary.BigEndian.Uint16(b[18:20]))
	if len(b) != inputHeaderMinSize+textLen {
		return InputEventPayload{}, fmt.Errorf("%w: input text bounds (%d vs %d)", ErrMalformedPayload, textLen, len(b)-inputHeaderMinSize)
	}
	if textLen > 0 {
		p.Text = string(b[inputHeaderMinSize:])
	}
	return p, nil
}

// Control operation names exchanged inside 0x07 PacketTypeControl JSON payloads.
const (
	ControlOpListFiles          = "list_files"
	ControlOpFileList           = "file_list"
	ControlOpPullFile           = "pull_file"
	ControlOpDeleteFile         = "delete_file"
	ControlOpStartDesktopStream = "start_desktop_stream"
	ControlOpStopDesktopStream  = "stop_desktop_stream"
	ControlOpStartPhoneScrcpy   = "start_phone_scrcpy"
	ControlOpStopPhoneScrcpy    = "stop_phone_scrcpy"
	ControlOpRequestKeyframe    = "request_keyframe"
	ControlOpUpdateMirrorConfig = "update_mirror_config"
	ControlOpConnInfo           = "conn_info"
	ControlOpError              = "error"
)

// MirrorConfig controls bi-directional screen mirroring quality, FPS, audio, and scrcpy flags.
type MirrorConfig struct {
	FPS           int    `json:"fps"`             // 0 = Auto, 24, 30, 60, 90, 120, 144
	BitrateMbps   int    `json:"bitrate_mbps"`    // 2, 4, 8, 12, 16, 24
	MaxSize       int    `json:"max_size"`        // 0 = Native/Asli, 720, 1024, 1280, 1600, 1920
	Audio         bool   `json:"audio"`           // true = Audio ON, false = --no-audio
	Codec         string `json:"codec,omitempty"` // "h264" | "h265"
	TurnScreenOff bool   `json:"turn_screen_off,omitempty"`
	StayAwake     bool   `json:"stay_awake"`
	AlwaysOnTop   bool   `json:"always_on_top,omitempty"`
	Fullscreen    bool   `json:"fullscreen,omitempty"`
	ShowTouches   bool   `json:"show_touches,omitempty"`
}

// DefaultMirrorConfig returns the default balanced low-latency mirroring configuration.
func DefaultMirrorConfig() MirrorConfig {
	return MirrorConfig{
		FPS:         0,
		BitrateMbps: 8,
		MaxSize:     0,
		Audio:       true,
		Codec:       "h264",
		StayAwake:   true,
	}
}

// Sanitize clamps numeric bounds and normalizes codec names safely.
func (m MirrorConfig) Sanitize() MirrorConfig {
	if m.FPS <= 0 {
		m.FPS = 0
	} else {
		if m.FPS < 15 {
			m.FPS = 15
		} else if m.FPS > 240 {
			m.FPS = 240
		}
	}
	if m.BitrateMbps <= 0 {
		m.BitrateMbps = 8
	} else if m.BitrateMbps > 50 {
		m.BitrateMbps = 50
	}
	if m.MaxSize <= 0 {
		m.MaxSize = 0
	} else if m.MaxSize < 480 {
		m.MaxSize = 480
	} else if m.MaxSize > 4096 {
		m.MaxSize = 4096
	}
	if m.Codec == "h265" || m.Codec == "hevc" {
		m.Codec = "h265"
	} else {
		m.Codec = "h264"
	}
	return m
}

// RemoteFileEntry describes a file in ~/Downloads/PhoneDrop for shared browsing.
type RemoteFileEntry struct {
	Name   string `json:"name"`
	Size   int64  `json:"size"`
	ModMs  int64  `json:"mod_ms"`
	Origin string `json:"origin,omitempty"` // "phone" | "laptop"
}

// ControlMessage represents a 0x07 PacketTypeControl JSON command/response.
type ControlMessage struct {
	Op       string            `json:"op"`
	Name     string            `json:"name,omitempty"`
	Files    []RemoteFileEntry `json:"files,omitempty"`
	Message  string            `json:"message,omitempty"`
	LinkType string            `json:"link_type,omitempty"` // "usb_cable" | "wireless_adb" | "wifi_lan"
	Width    int               `json:"width,omitempty"`
	Height   int               `json:"height,omitempty"`
	Mirror   *MirrorConfig     `json:"mirror,omitempty"`
}

// Encode serializes ControlMessage into UTF-8 JSON bytes.
func (c ControlMessage) Encode() ([]byte, error) {
	b, err := json.Marshal(c)
	if err != nil {
		return nil, err
	}
	if uint64(len(b)) > uint64(MaxPayloadSize) {
		return nil, ErrPayloadTooLarge
	}
	return b, nil
}

// DecodeControlMessage parses a 0x07 PacketTypeControl JSON payload.
func DecodeControlMessage(b []byte) (ControlMessage, error) {
	var c ControlMessage
	if err := json.Unmarshal(b, &c); err != nil {
		return ControlMessage{}, fmt.Errorf("%w: invalid control json: %v", ErrMalformedPayload, err)
	}
	if c.Op == "" {
		return ControlMessage{}, fmt.Errorf("%w: missing control op", ErrMalformedPayload)
	}
	return c, nil
}

