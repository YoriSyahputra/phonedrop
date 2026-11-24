package ws

import (
	"encoding/json"
	"github.com/gorilla/websocket"
	"log"
	"net/http"
	"phonedrop/internal/input"
	"sync"
	"time"
)

const (
	writeWait      = 10 * time.Second
	pongWait       = 60 * time.Second
	pingPeriod     = (pongWait * 9) / 10
	maxMessageSize = 512 * 1024 // Batas pesan teks 512 KB agar hemat RAM
)

var upgrader = websocket.Upgrader{
	ReadBufferSize:  1024,
	WriteBufferSize: 1024,
	CheckOrigin: func(r *http.Request) bool {
		return true // Izinkan koneksi dari jaringan lokal
	},
}

// Ubah definisi Message agar menampung data input remote dan Direct Send:
type Message struct {
	Type       string             `json:"type"` // "clipboard", "files_updated", "input", "direct_send", "direct_status"
	Content    string             `json:"content,omitempty"`
	Files      []string           `json:"files,omitempty"`
	SenderID   string             `json:"sender_id,omitempty"`
	APKClients int                `json:"apk_clients,omitempty"`
	Input      input.EventPayload `json:"input,omitempty"`
	Timestamp  int64              `json:"timestamp"`
}

type Client struct {
	hub  *Hub
	conn *websocket.Conn
	send chan []byte
}

type Hub struct {
	mu              sync.RWMutex
	clients         map[*Client]bool
	broadcast       chan []byte
	register        chan *Client
	unregister      chan *Client
	onClipboardRecv func(text string)
	onInputRecv     func(ev input.EventPayload)
	getInitialClip  func() string
}

func NewHub(
	onClipboardRecv func(string),
	onInputRecv func(input.EventPayload),
	getInitialClip func() string,
) *Hub {
	return &Hub{
		clients:         make(map[*Client]bool),
		broadcast:       make(chan []byte, 32),
		register:        make(chan *Client),
		unregister:      make(chan *Client),
		onClipboardRecv: onClipboardRecv,
		onInputRecv:     onInputRecv,
		getInitialClip:  getInitialClip,
	}
}

func (h *Hub) Run() {
	for {
		select {
		case client := <-h.register:
			h.mu.Lock()
			h.clients[client] = true
			h.mu.Unlock()

			// Kirim isi clipboard laptop saat ini ke HP yang baru terhubung
			if h.getInitialClip != nil {
				if clip := h.getInitialClip(); clip != "" {
					msg, err := json.Marshal(Message{
						Type:      "clipboard",
						Content:   clip,
						Timestamp: time.Now().UnixMilli(),
					})
					if err == nil {
						client.send <- msg
					}
				}
			}

		case client := <-h.unregister:
			h.mu.Lock()
			if _, ok := h.clients[client]; ok {
				delete(h.clients, client)
				close(client.send)
			}
			h.mu.Unlock()

		case message := <-h.broadcast:
			h.mu.Lock()
			for client := range h.clients {
				select {
				case client.send <- message:
				default:
					close(client.send)
					delete(h.clients, client)
				}
			}
			h.mu.Unlock()
		}
	}
}

// BroadcastClipboard mengirim teks clipboard terbaru ke semua klien WebSocket.
func (h *Hub) BroadcastClipboard(text string) {
	payload, err := json.Marshal(Message{
		Type:      "clipboard",
		Content:   text,
		Timestamp: time.Now().UnixMilli(),
	})
	if err != nil {
		return
	}
	h.broadcast <- payload
}

// NotifyFilesUpdated memberi tahu seluruh HP bahwa daftar file di folder berubah.
func (h *Hub) NotifyFilesUpdated() {
	payload, err := json.Marshal(Message{
		Type:      "files_updated",
		Timestamp: time.Now().UnixMilli(),
	})
	if err != nil {
		return
	}
	h.broadcast <- payload
}

// BroadcastDirectSend memberi tahu klien PWA yang sedang aktif untuk mengunduh file secara otomatis.
func (h *Hub) BroadcastDirectSend(files []string, senderID string) {
	payload, err := json.Marshal(Message{
		Type:      "direct_send",
		Files:     files,
		SenderID:  senderID,
		Timestamp: time.Now().UnixMilli(),
	})
	if err != nil {
		return
	}
	h.broadcast <- payload
}

// BroadcastDirectStatus memberi tahu UI tentang jumlah APK Android Background Service yang terhubung.
func (h *Hub) BroadcastDirectStatus(apkClients int) {
	payload, err := json.Marshal(Message{
		Type:       "direct_status",
		APKClients: apkClients,
		Timestamp:  time.Now().UnixMilli(),
	})
	if err != nil {
		return
	}
	h.broadcast <- payload
}

// ServeWS menangani permintaan upgrade HTTP ke WebSocket.
func (h *Hub) ServeWS(w http.ResponseWriter, r *http.Request) {
	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		log.Printf("[WS] Gagal upgrade koneksi: %v", err)
		return
	}

	client := &Client{
		hub:  h,
		conn: conn,
		send: make(chan []byte, 16),
	}
	h.register <- client

	go client.writePump()
	go client.readPump()
}

func (c *Client) readPump() {
	defer func() {
		c.hub.unregister <- c
		c.conn.Close()
	}()

	c.conn.SetReadLimit(maxMessageSize)
	_ = c.conn.SetReadDeadline(time.Now().Add(pongWait))
	c.conn.SetPongHandler(func(string) error {
		_ = c.conn.SetReadDeadline(time.Now().Add(pongWait))
		return nil
	})

	for {
		_, rawMsg, err := c.conn.ReadMessage()
		if err != nil {
			break
		}

		var msg Message
		if err := json.Unmarshal(rawMsg, &msg); err != nil {
			continue
		}

		switch msg.Type {
		case "clipboard":
			if msg.Content != "" {
				if c.hub.onClipboardRecv != nil {
					c.hub.onClipboardRecv(msg.Content)
				}
				c.hub.BroadcastClipboard(msg.Content)
			}
		case "input":
			if c.hub.onInputRecv != nil {
				c.hub.onInputRecv(msg.Input)
			}
		}
	}
}

func (c *Client) writePump() {
	ticker := time.NewTicker(pingPeriod)
	defer func() {
		ticker.Stop()
		c.conn.Close()
	}()

	for {
		select {
		case message, ok := <-c.send:
			_ = c.conn.SetWriteDeadline(time.Now().Add(writeWait))
			if !ok {
				_ = c.conn.WriteMessage(websocket.CloseMessage, []byte{})
				return
			}

			w, err := c.conn.NextWriter(websocket.TextMessage)
			if err != nil {
				return
			}
			_, _ = w.Write(message)
			if err := w.Close(); err != nil {
				return
			}

		case <-ticker.C:
			_ = c.conn.SetWriteDeadline(time.Now().Add(writeWait))
			if err := c.conn.WriteMessage(websocket.PingMessage, nil); err != nil {
				return
			}
		}
	}
}
