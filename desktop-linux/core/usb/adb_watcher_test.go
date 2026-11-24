package usb

import (
	"context"
	"fmt"
	"io"
	"net"
	"strings"
	"sync"
	"testing"
	"time"
)

type mockRunner struct {
	mu       sync.Mutex
	calls    []string
	failNext bool
}

func (m *mockRunner) Run(_ context.Context, name string, args ...string) ([]byte, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	cmd := name + " " + strings.Join(args, " ")
	m.calls = append(m.calls, cmd)
	if strings.Contains(cmd, "reverse --list") {
		return []byte("UsbFfs tcp:28200 tcp:28200\n"), nil
	}
	if m.failNext && strings.Contains(cmd, "reverse tcp:") {
		m.failNext = false
		return []byte("error: closed"), fmt.Errorf("exit status 1")
	}
	return []byte("28200\n"), nil
}

func writeADBMsg(w io.Writer, payload string) error {
	_, err := io.WriteString(w, fmt.Sprintf("%04x%s", len(payload), payload))
	return err
}

func TestADBWatcherHotplugStateMachine(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen mock adb: %v", err)
	}
	defer ln.Close()

	pushCh := make(chan string, 4)
	go func() {
		conn, err := ln.Accept()
		if err != nil {
			return
		}
		defer conn.Close()

		var hdr [4]byte
		if _, err := io.ReadFull(conn, hdr[:]); err != nil {
			return
		}
		var n int
		if _, err := fmt.Sscanf(string(hdr[:]), "%04x", &n); err != nil {
			return
		}
		cmd := make([]byte, n)
		if _, err := io.ReadFull(conn, cmd); err != nil {
			return
		}
		_, _ = io.WriteString(conn, "OKAY")

		for msg := range pushCh {
			if err := writeADBMsg(conn, msg); err != nil {
				return
			}
		}
	}()

	states := make(chan DeviceEvent, 16)
	runner := &mockRunner{}
	w := NewWatcher(28200, func(ev DeviceEvent) {
		states <- ev
	})
	w.ADBAddr = ln.Addr().String()
	w.Runner = runner
	w.SkipStartServer = true

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go w.Watch(ctx)

	expectState := func(want DeviceState) DeviceEvent {
		t.Helper()
		select {
		case ev := <-states:
			if ev.State != want {
				t.Fatalf("expected state %s, got %s (err=%q)", want, ev.State, ev.Err)
			}
			return ev
		case <-time.After(2 * time.Second):
			t.Fatalf("timed out waiting for state %s", want)
			return DeviceEvent{}
		}
	}

	// 1. Plugged in unauthorized
	pushCh <- "R58N123456\tunauthorized\n"
	ev := expectState(StateUnauthorized)
	if ev.Serial != "R58N123456" {
		t.Fatalf("unexpected serial: %s", ev.Serial)
	}

	// 2. User authorizes RSA prompt -> AUTHORIZED -> FORWARDING -> TUNNEL_READY
	pushCh <- "R58N123456\tdevice\n"
	expectState(StateAuthorized)
	expectState(StateForwarding)
	expectState(StateTunnelReady)

	// 3. Unplug USB -> DISCONNECTED
	pushCh <- ""
	expectState(StateDisconnected)
	close(pushCh)

	runner.mu.Lock()
	defer runner.mu.Unlock()
	if len(runner.calls) < 2 || !strings.Contains(runner.calls[0], "adb -s R58N123456 reverse tcp:28200 tcp:28200") {
		t.Fatalf("expected adb reverse command, got %v", runner.calls)
	}
}
