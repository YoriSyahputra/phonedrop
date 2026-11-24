// Package protocol implements the PhoneDrop v2 ("PDP2") Big-Endian binary wire
// protocol, frame serialization/deserialization, and pooled zero-copy framing.
package protocol

import (
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"sync"
	"sync/atomic"
)

const (
	// ProtocolVersion is the current PDP2 wire protocol version byte.
	ProtocolVersion uint8 = 0x02

	// HeaderSize is the fixed 16-byte Big-Endian frame header length.
	HeaderSize = 16

	// ChunkSize is the fixed 64 KiB payload size used for streaming file transfers.
	ChunkSize uint32 = 64 * 1024

	// MaxPayloadSize caps individual frames to 4 MiB to bound memory allocation.
	MaxPayloadSize uint32 = 4 * 1024 * 1024

	// DefaultPort is the unified PhoneDrop v2 TCP port for ADB reverse and LAN mDNS.
	DefaultPort = 28200

	pooledBufSize = int(ChunkSize) + 256
)

// Packet types defined by PhoneDrop v2.
const (
	PacketTypeClip       uint8 = 0x01
	PacketTypeFileInit   uint8 = 0x02
	PacketTypeFileChunk  uint8 = 0x03
	PacketTypeFileDone   uint8 = 0x04
	PacketTypeVideoFrame uint8 = 0x05
	PacketTypeInputEvent uint8 = 0x06
	PacketTypeControl    uint8 = 0x07
)

// Frame header flags bitmask.
const (
	FlagAck             uint8 = 0x01
	FlagError           uint8 = 0x02
	FlagKeyframe        uint8 = 0x04
	FlagCodecConfig     uint8 = 0x08
	FlagDirLinuxToPhone uint8 = 0x10
	FlagHeartbeat       uint8 = 0x80
)

var (
	// MagicBytes identifies PhoneDrop v2 wire frames ("PDP2").
	MagicBytes = [4]byte{'P', 'D', 'P', '2'}

	ErrInvalidMagic    = errors.New("protocol: invalid frame magic bytes")
	ErrUnsupportedVer  = errors.New("protocol: unsupported protocol version")
	ErrUnknownType     = errors.New("protocol: unknown packet type")
	ErrPayloadTooLarge = errors.New("protocol: payload length exceeds MaxPayloadSize")
	ErrTruncated       = errors.New("protocol: unexpected truncated payload")
)

var framePool = sync.Pool{
	New: func() any {
		b := make([]byte, pooledBufSize)
		return &b
	},
}

// Header represents the fixed 16-byte PDP2 wire header.
type Header struct {
	Magic      [4]byte
	Version    uint8
	Type       uint8
	Flags      uint8
	Reserved   uint8
	SeqID      uint32
	PayloadLen uint32
}

// Frame holds a decoded PDP2 frame header and its raw payload slice.
// Call Release when finished to return pooled buffers for small/chunk frames.
type Frame struct {
	Header  Header
	Payload []byte
	poolBuf *[]byte
}

// Release returns the underlying pooled slice (if any) back to framePool.
func (f *Frame) Release() {
	if f == nil || f.poolBuf == nil {
		return
	}
	b := f.poolBuf
	f.poolBuf = nil
	f.Payload = nil
	framePool.Put(b)
}

// DetachPayload copies the payload onto a fresh slice and releases the pool buffer,
// making it safe to retain payload bytes across async goroutines.
func (f *Frame) DetachPayload() []byte {
	if len(f.Payload) == 0 {
		return nil
	}
	cp := make([]byte, len(f.Payload))
	copy(cp, f.Payload)
	return cp
}

// IsValidPacketType reports whether t is a defined PDP2 packet type (0x01..0x07).
func IsValidPacketType(t uint8) bool {
	return t >= PacketTypeClip && t <= PacketTypeControl
}

// EncodeHeader writes h into dst, which must be at least HeaderSize bytes long.
func EncodeHeader(dst []byte, h Header) {
	copy(dst[0:4], MagicBytes[:])
	dst[4] = h.Version
	dst[5] = h.Type
	dst[6] = h.Flags
	dst[7] = h.Reserved
	binary.BigEndian.PutUint32(dst[8:12], h.SeqID)
	binary.BigEndian.PutUint32(dst[12:16], h.PayloadLen)
}

// DecodeHeader parses and validates a 16-byte Big-Endian PDP2 frame header.
func DecodeHeader(src []byte) (Header, error) {
	if len(src) < HeaderSize {
		return Header{}, ErrTruncated
	}
	var h Header
	copy(h.Magic[:], src[0:4])
	if h.Magic != MagicBytes {
		return Header{}, fmt.Errorf("%w: got %q", ErrInvalidMagic, h.Magic[:])
	}
	h.Version = src[4]
	if h.Version != ProtocolVersion {
		return Header{}, fmt.Errorf("%w: got 0x%02x", ErrUnsupportedVer, h.Version)
	}
	h.Type = src[5]
	if !IsValidPacketType(h.Type) {
		return Header{}, fmt.Errorf("%w: got 0x%02x", ErrUnknownType, h.Type)
	}
	h.Flags = src[6]
	h.Reserved = src[7]
	h.SeqID = binary.BigEndian.Uint32(src[8:12])
	h.PayloadLen = binary.BigEndian.Uint32(src[12:16])
	if h.PayloadLen > MaxPayloadSize {
		return Header{}, fmt.Errorf("%w: %d > %d", ErrPayloadTooLarge, h.PayloadLen, MaxPayloadSize)
	}
	return h, nil
}

// Framer provides thread-safe monotonic sequence numbering and scatter/gather frame writes.
type Framer struct {
	writeMu sync.Mutex
	seq     atomic.Uint32
}

// NewFramer creates a new PDP2 binary framer.
func NewFramer() *Framer {
	return &Framer{}
}

// NextSeq returns the next monotonic non-zero sequence identifier.
func (f *Framer) NextSeq() uint32 {
	return f.seq.Add(1)
}

// WriteFrame writes a complete PDP2 frame atomically under the write mutex.
func (f *Framer) WriteFrame(w io.Writer, pktType, flags uint8, payload []byte) error {
	return f.WriteHeaderAndParts(w, pktType, flags, payload, nil)
}

// WriteHeaderAndParts writes a 16-byte header followed by up to two payload parts
// using scatter/gather writev when supported by the underlying writer.
func (f *Framer) WriteHeaderAndParts(w io.Writer, pktType, flags uint8, part1, part2 []byte) error {
	if !IsValidPacketType(pktType) {
		return fmt.Errorf("%w: 0x%02x", ErrUnknownType, pktType)
	}
	totalLen := uint64(len(part1)) + uint64(len(part2))
	if totalLen > uint64(MaxPayloadSize) {
		return fmt.Errorf("%w: %d > %d", ErrPayloadTooLarge, totalLen, MaxPayloadSize)
	}

	var hdrBuf [HeaderSize]byte
	EncodeHeader(hdrBuf[:], Header{
		Magic:      MagicBytes,
		Version:    ProtocolVersion,
		Type:       pktType,
		Flags:      flags,
		Reserved:   0,
		SeqID:      f.NextSeq(),
		PayloadLen: uint32(totalLen),
	})

	bufs := make(net.Buffers, 0, 3)
	bufs = append(bufs, hdrBuf[:])
	if len(part1) > 0 {
		bufs = append(bufs, part1)
	}
	if len(part2) > 0 {
		bufs = append(bufs, part2)
	}

	f.writeMu.Lock()
	defer f.writeMu.Unlock()
	_, err := bufs.WriteTo(w)
	return err
}

// WriteChunkFrame writes a 0x03 FileChunk frame with zero copy of the data slice.
func (f *Framer) WriteChunkFrame(w io.Writer, flags uint8, transferID [16]byte, chunkIndex uint32, data []byte) error {
	if uint32(len(data)) > ChunkSize {
		return fmt.Errorf("%w: chunk data %d > %d", ErrPayloadTooLarge, len(data), ChunkSize)
	}
	var chunkHdr [24]byte
	copy(chunkHdr[0:16], transferID[:])
	binary.BigEndian.PutUint32(chunkHdr[16:20], chunkIndex)
	binary.BigEndian.PutUint32(chunkHdr[20:24], uint32(len(data)))
	return f.WriteHeaderAndParts(w, PacketTypeFileChunk, flags, chunkHdr[:], data)
}

// WriteVideoFrame writes a 0x05 VideoFrame header + Annex-B NAL payload without copying NALData.
func (f *Framer) WriteVideoFrame(w io.Writer, flags uint8, hdr VideoFrameHeader, nalData []byte) error {
	var meta [videoFrameHeaderSize]byte
	encodeVideoHeader(meta[:], hdr)
	return f.WriteHeaderAndParts(w, PacketTypeVideoFrame, flags, meta[:], nalData)
}

// ReadFrame reads the next PDP2 frame from r using framePool for payloads up to pooledBufSize.
// Callers should invoke frame.Release() once finished inspecting Payload.
func ReadFrame(r io.Reader) (*Frame, error) {
	var hdrBuf [HeaderSize]byte
	if _, err := io.ReadFull(r, hdrBuf[:]); err != nil {
		return nil, err
	}
	h, err := DecodeHeader(hdrBuf[:])
	if err != nil {
		return nil, err
	}

	frame := &Frame{Header: h}
	if h.PayloadLen == 0 {
		return frame, nil
	}

	n := int(h.PayloadLen)
	if n <= pooledBufSize {
		pb := framePool.Get().(*[]byte)
		buf := (*pb)[:n]
		if _, err := io.ReadFull(r, buf); err != nil {
			framePool.Put(pb)
			return nil, err
		}
		frame.Payload = buf
		frame.poolBuf = pb
		return frame, nil
	}

	buf := make([]byte, n)
	if _, err := io.ReadFull(r, buf); err != nil {
		return nil, err
	}
	frame.Payload = buf
	return frame, nil
}

// ReadFrameInto reads the next PDP2 frame into caller-provided buf (growing if necessary).
func ReadFrameInto(r io.Reader, buf []byte) (*Frame, []byte, error) {
	var hdrBuf [HeaderSize]byte
	if _, err := io.ReadFull(r, hdrBuf[:]); err != nil {
		return nil, buf, err
	}
	h, err := DecodeHeader(hdrBuf[:])
	if err != nil {
		return nil, buf, err
	}
	n := int(h.PayloadLen)
	if cap(buf) < n {
		buf = make([]byte, n)
	} else {
		buf = buf[:n]
	}
	if n > 0 {
		if _, err := io.ReadFull(r, buf); err != nil {
			return nil, buf, err
		}
	}
	return &Frame{Header: h, Payload: buf}, buf, nil
}
