package mirror

import (
	"sync"

	"phonedrop/v2/desktop/core/protocol"
)

// PhoneReceiver ingests incoming 0x05 PacketVideoFrame packets (StreamPhoneToLinux)
// and feeds them into an embedded MPVPlayer instance.
type PhoneReceiver struct {
	mu           sync.Mutex
	player       *MPVPlayer
	cachedConfig []byte
	seenConfig   bool
	onDimensions func(w, h uint16)
}

// NewPhoneReceiver creates a receiver targeting player.
func NewPhoneReceiver(player *MPVPlayer, onDimensions func(w, h uint16)) *PhoneReceiver {
	return &PhoneReceiver{
		player:       player,
		onDimensions: onDimensions,
	}
}

// HandleVideoFrame processes a decoded VideoFramePayload from the TCP tunnel.
func (r *PhoneReceiver) HandleVideoFrame(flags uint8, vid protocol.VideoFramePayload) {
	if vid.Header.StreamID != protocol.StreamPhoneToLinux || len(vid.NALData) == 0 {
		return
	}
	isCfg := flags&protocol.FlagCodecConfig != 0
	isKey := flags&protocol.FlagKeyframe != 0

	r.mu.Lock()
	player := r.player
	if isCfg {
		r.cachedConfig = append(r.cachedConfig[:0], vid.NALData...)
		r.seenConfig = true
	}
	cached := r.cachedConfig
	seen := r.seenConfig
	r.mu.Unlock()

	if r.onDimensions != nil && vid.Header.Width > 0 && vid.Header.Height > 0 {
		r.onDimensions(vid.Header.Width, vid.Header.Height)
	}

	if player == nil {
		return
	}
	if !player.IsRunning() {
		if err := player.Start(); err != nil {
			return
		}
		if !isCfg && seen && len(cached) > 0 {
			player.WriteNAL(cached, true)
		}
	}
	player.WriteNAL(vid.NALData, isCfg || isKey)
}
