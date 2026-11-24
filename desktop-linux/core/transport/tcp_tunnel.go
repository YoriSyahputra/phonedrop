// Package transport manages persistent multiplexed PDP2 TCP tunnels on port 28200
// with TCP_NODELAY, keepalive heartbeats, and late-joiner SPS/PPS caching.
package transport

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"sync"
	"sync/atomic"
	"time"

	"phonedrop/v2/desktop/core/protocol"
)

// TransportMode describes how the peer connected.
type TransportMode string

const (
	ModeUSBLoopback TransportMode = "USB (ADB Reverse)"
	ModeWiFiLAN     TransportMode = "Wi-Fi LAN"
)

const defaultHeartbeatInterval = 10 * time.Second

// Handlers receives decoded frames and lifecycle events for a Session.
type Handlers struct {
	OnConnect    func(s *Session)
	OnDisconnect func(s *Session, err error)
	OnClip       func(s *Session, clip protocol.ClipPayload)
	OnFileInit   func(s *Session, init protocol.FileInitPayload)
	OnFileChunk  func(s *Session, chunk protocol.FileChunkPayload)
	OnFileDone   func(s *Session, flags uint8, done protocol.FileDonePayload)
	OnVideoFrame func(s *Session, flags uint8, vid protocol.VideoFramePayload)
	OnInputEvent func(s *Session, ev protocol.InputEventPayload)
}

type cachedCodecConfig struct {
	flags uint8
	hdr   protocol.VideoFrameHeader
	nal   []byte
}

// Session represents a single active PDP2 TCP connection.
type Session struct {
	ID         string
	RemoteAddr string
	Mode       TransportMode

	conn      net.Conn
	framer    *protocol.Framer
	handlers  Handlers
	done      chan struct{}
	closeOnce sync.Once
	closeErr  error
}

func newSession(id string, conn net.Conn, handlers Handlers) *Session {
	configureTCPSocket(conn)
	mode := ModeWiFiLAN
	if host, _, err := net.SplitHostPort(conn.RemoteAddr().String()); err == nil {
		if ip := net.ParseIP(host); ip != nil && ip.IsLoopback() {
			mode = ModeUSBLoopback
		}
	}
	return &Session{
		ID:         id,
		RemoteAddr: conn.RemoteAddr().String(),
		Mode:       mode,
		conn:       conn,
		framer:     protocol.NewFramer(),
		handlers:   handlers,
		done:       make(chan struct{}),
	}
}

func configureTCPSocket(conn net.Conn) {
	tc, ok := conn.(*net.TCPConn)
	if !ok {
		return
	}
	_ = tc.SetNoDelay(true)
	_ = tc.SetKeepAlive(true)
	_ = tc.SetKeepAlivePeriod(15 * time.Second)
}

// Done is closed when the session terminates.
func (s *Session) Done() <-chan struct{} {
	return s.done
}

// Close closes the underlying TCP socket cleanly.
func (s *Session) Close() error {
	s.closeOnce.Do(func() {
		s.closeErr = s.conn.Close()
		close(s.done)
	})
	return s.closeErr
}

// WriteFrame writes an encoded payload frame over the session socket.
func (s *Session) WriteFrame(pktType, flags uint8, payload []byte) error {
	return s.framer.WriteFrame(s.conn, pktType, flags, payload)
}

// WriteChunkFrame streams a 64KB file chunk with zero copy of data.
func (s *Session) WriteChunkFrame(flags uint8, transferID [16]byte, chunkIndex uint32, data []byte) error {
	return s.framer.WriteChunkFrame(s.conn, flags, transferID, chunkIndex, data)
}

// WriteVideoFrame sends a VideoFrame packet.
func (s *Session) WriteVideoFrame(flags uint8, hdr protocol.VideoFrameHeader, nalData []byte) error {
	return s.framer.WriteVideoFrame(s.conn, flags, hdr, nalData)
}

func (s *Session) run(ctx context.Context, heartbeatInterval time.Duration) {
	defer s.Close()

	if s.handlers.OnConnect != nil {
		s.handlers.OnConnect(s)
	}

	hbCtx, hbCancel := context.WithCancel(ctx)
	defer hbCancel()

	go func() {
		select {
		case <-hbCtx.Done():
			_ = s.Close()
		case <-s.done:
		}
	}()

	if heartbeatInterval > 0 {
		go s.heartbeatLoop(hbCtx, heartbeatInterval)
	}

	var readErr error
	var buf []byte
	for {
		var frame *protocol.Frame
		frame, buf, readErr = protocol.ReadFrameInto(s.conn, buf)
		if readErr != nil {
			break
		}
		if frame.Header.Flags&protocol.FlagHeartbeat != 0 {
			continue
		}
		s.dispatch(frame)
	}

	if errors.Is(readErr, io.EOF) || errors.Is(readErr, net.ErrClosed) {
		readErr = nil
	}
	if s.handlers.OnDisconnect != nil {
		s.handlers.OnDisconnect(s, readErr)
	}
}

func (s *Session) heartbeatLoop(ctx context.Context, interval time.Duration) {
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-s.done:
			return
		case <-ticker.C:
			if err := s.WriteFrame(protocol.PacketTypeClip, protocol.FlagHeartbeat, nil); err != nil {
				return
			}
		}
	}
}

func (s *Session) dispatch(frame *protocol.Frame) {
	switch frame.Header.Type {
	case protocol.PacketTypeClip:
		if s.handlers.OnClip == nil {
			return
		}
		clip, err := protocol.DecodeClipPayload(frame.Payload)
		if err == nil {
			s.handlers.OnClip(s, clip)
		}
	case protocol.PacketTypeFileInit:
		if s.handlers.OnFileInit == nil {
			return
		}
		init, err := protocol.DecodeFileInitPayload(frame.Payload)
		if err == nil {
			s.handlers.OnFileInit(s, init)
		}
	case protocol.PacketTypeFileChunk:
		if s.handlers.OnFileChunk == nil {
			return
		}
		chunk, err := protocol.DecodeFileChunkPayload(frame.Payload)
		if err == nil {
			s.handlers.OnFileChunk(s, chunk)
		}
	case protocol.PacketTypeFileDone:
		if s.handlers.OnFileDone == nil {
			return
		}
		done, err := protocol.DecodeFileDonePayload(frame.Payload)
		if err == nil {
			s.handlers.OnFileDone(s, frame.Header.Flags, done)
		}
	case protocol.PacketTypeVideoFrame:
		if s.handlers.OnVideoFrame == nil {
			return
		}
		vid, err := protocol.DecodeVideoFramePayload(frame.Payload)
		if err == nil {
			s.handlers.OnVideoFrame(s, frame.Header.Flags, vid)
		}
	case protocol.PacketTypeInputEvent:
		if s.handlers.OnInputEvent == nil {
			return
		}
		ev, err := protocol.DecodeInputEventPayload(frame.Payload)
		if err == nil {
			s.handlers.OnInputEvent(s, ev)
		}
	}
}

// TunnelServer listens on a TCP port (default :28200) and manages connected Sessions.
type TunnelServer struct {
	listenAddr string
	handlers   Handlers
	hbInterval time.Duration

	lnMu     sync.RWMutex
	listener net.Listener

	sessMu      sync.RWMutex
	sessions    map[string]*Session
	codecConfig map[uint8]cachedCodecConfig
	nextID      atomic.Uint64
}

// NewTunnelServer initializes a TunnelServer configured for listenAddr (e.g. ":28200").
func NewTunnelServer(listenAddr string, handlers Handlers) *TunnelServer {
	if listenAddr == "" {
		listenAddr = fmt.Sprintf(":%d", protocol.DefaultPort)
	}
	return &TunnelServer{
		listenAddr:  listenAddr,
		handlers:    handlers,
		hbInterval:  defaultHeartbeatInterval,
		sessions:    make(map[string]*Session),
		codecConfig: make(map[uint8]cachedCodecConfig),
	}
}

// Listen binds the server socket synchronously so Addr() is immediately valid.
func (ts *TunnelServer) Listen() (net.Listener, error) {
	ln, err := net.Listen("tcp", ts.listenAddr)
	if err != nil {
		return nil, err
	}
	ts.lnMu.Lock()
	ts.listener = ln
	ts.lnMu.Unlock()
	return ln, nil
}

// Addr returns the bound listener address, or listenAddr if not yet listening.
func (ts *TunnelServer) Addr() string {
	ts.lnMu.RLock()
	defer ts.lnMu.RUnlock()
	if ts.listener != nil {
		return ts.listener.Addr().String()
	}
	return ts.listenAddr
}

// Serve accepts connections on an already-bound listener (or binds Listen first) until ctx finishes.
func (ts *TunnelServer) Serve(ctx context.Context) error {
	ts.lnMu.RLock()
	ln := ts.listener
	ts.lnMu.RUnlock()
	if ln == nil {
		var err error
		ln, err = ts.Listen()
		if err != nil {
			return err
		}
	}

	go func() {
		<-ctx.Done()
		_ = ts.Close()
	}()

	for {
		conn, err := ln.Accept()
		if err != nil {
			if ctx.Err() != nil || errors.Is(err, net.ErrClosed) {
				return nil
			}
			return err
		}

		id := fmt.Sprintf("sess-%d", ts.nextID.Add(1))
		wrappedHandlers := ts.handlers
		origConnect := ts.handlers.OnConnect
		origDisconnect := ts.handlers.OnDisconnect

		wrappedHandlers.OnConnect = func(s *Session) {
			ts.addSession(s)
			ts.replayCachedCodecConfig(s)
			if origConnect != nil {
				origConnect(s)
			}
		}
		wrappedHandlers.OnDisconnect = func(s *Session, derr error) {
			ts.removeSession(s.ID)
			if origDisconnect != nil {
				origDisconnect(s, derr)
			}
		}

		sess := newSession(id, conn, wrappedHandlers)
		go sess.run(ctx, ts.hbInterval)
	}
}

// Close closes the server listener and all active sessions.
func (ts *TunnelServer) Close() error {
	ts.lnMu.RLock()
	ln := ts.listener
	ts.lnMu.RUnlock()
	var err error
	if ln != nil {
		err = ln.Close()
	}
	for _, s := range ts.Sessions() {
		_ = s.Close()
	}
	return err
}

func (ts *TunnelServer) addSession(s *Session) {
	ts.sessMu.Lock()
	ts.sessions[s.ID] = s
	ts.sessMu.Unlock()
}

func (ts *TunnelServer) removeSession(id string) {
	ts.sessMu.Lock()
	delete(ts.sessions, id)
	ts.sessMu.Unlock()
}

func (ts *TunnelServer) replayCachedCodecConfig(s *Session) {
	ts.sessMu.RLock()
	configs := make([]cachedCodecConfig, 0, len(ts.codecConfig))
	for _, cfg := range ts.codecConfig {
		configs = append(configs, cfg)
	}
	ts.sessMu.RUnlock()

	for _, cfg := range configs {
		_ = s.WriteVideoFrame(cfg.flags, cfg.hdr, cfg.nal)
	}
}

// Sessions returns a snapshot slice of currently connected sessions.
func (ts *TunnelServer) Sessions() []*Session {
	ts.sessMu.RLock()
	defer ts.sessMu.RUnlock()
	out := make([]*Session, 0, len(ts.sessions))
	for _, s := range ts.sessions {
		out = append(out, s)
	}
	return out
}

// PrimarySession returns the first active session (preferring USB loopback), or nil.
func (ts *TunnelServer) PrimarySession() *Session {
	sessions := ts.Sessions()
	if len(sessions) == 0 {
		return nil
	}
	for _, s := range sessions {
		if s.Mode == ModeUSBLoopback {
			return s
		}
	}
	return sessions[0]
}

// BroadcastFrame sends a binary frame to all currently connected sessions.
func (ts *TunnelServer) BroadcastFrame(pktType, flags uint8, payload []byte) {
	for _, s := range ts.Sessions() {
		_ = s.WriteFrame(pktType, flags, payload)
	}
}

// BroadcastVideoFrame sends a VideoFrame packet to all peers and caches SPS/PPS configs.
func (ts *TunnelServer) BroadcastVideoFrame(flags uint8, hdr protocol.VideoFrameHeader, nalData []byte) {
	if flags&protocol.FlagCodecConfig != 0 && len(nalData) > 0 {
		cp := make([]byte, len(nalData))
		copy(cp, nalData)
		ts.sessMu.Lock()
		ts.codecConfig[hdr.StreamID] = cachedCodecConfig{
			flags: flags,
			hdr:   hdr,
			nal:   cp,
		}
		ts.sessMu.Unlock()
	}
	for _, s := range ts.Sessions() {
		_ = s.WriteVideoFrame(flags, hdr, nalData)
	}
}

// DialSession connects to a remote PhoneDrop PDP2 TCP server and starts its session loop.
func DialSession(ctx context.Context, addr string, handlers Handlers) (*Session, error) {
	dialer := net.Dialer{Timeout: 5 * time.Second}
	conn, err := dialer.DialContext(ctx, "tcp", addr)
	if err != nil {
		return nil, err
	}
	sess := newSession("client-1", conn, handlers)
	go sess.run(ctx, defaultHeartbeatInterval)
	return sess, nil
}
