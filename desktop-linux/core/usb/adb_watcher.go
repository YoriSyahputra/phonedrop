// Package usb monitors Android USB hotplug events via ADB's host:track-devices
// protocol (127.0.0.1:5037) and automates `adb reverse tcp:28200 tcp:28200`.
package usb

import (
	"context"
	"fmt"
	"io"
	"net"
	"os/exec"
	"strconv"
	"strings"
	"sync"
	"time"
)

// DeviceState enumerates the ADB hotplug and tunnel-forwarding state machine.
type DeviceState string

const (
	StateDisconnected DeviceState = "DISCONNECTED"
	StateUnauthorized DeviceState = "DETECTED_UNAUTHORIZED"
	StateAuthorized   DeviceState = "AUTHORIZED"
	StateForwarding   DeviceState = "FORWARDING"
	StateTunnelReady  DeviceState = "TUNNEL_READY"
	StateError        DeviceState = "ERROR"
)

// DeviceEvent describes a state transition emitted by Watcher.
type DeviceEvent struct {
	State     DeviceState
	Serial    string
	Err       string
	UpdatedAt time.Time
}

// CommandRunner abstracts process execution for testing ADB commands.
type CommandRunner interface {
	Run(ctx context.Context, name string, args ...string) ([]byte, error)
}

type execRunner struct{}

func (execRunner) Run(ctx context.Context, name string, args ...string) ([]byte, error) {
	cmd := exec.CommandContext(ctx, name, args...)
	return cmd.CombinedOutput()
}

type adbDevice struct {
	serial string
	status string
}

// Watcher maintains a persistent host:track-devices stream to the ADB server
// and configures `adb reverse tcp:<Port> tcp:<Port>` whenever a device authorizes.
type Watcher struct {
	ADBAddr         string
	Port            int
	Runner          CommandRunner
	SkipStartServer bool
	OnState         func(DeviceEvent)

	mu            sync.Mutex
	lastState     DeviceState
	forwardedSer  string
	startTried    bool
}

// NewWatcher creates an ADB hotplug watcher targeting port (default 28200).
func NewWatcher(port int, onState func(DeviceEvent)) *Watcher {
	if port <= 0 {
		port = 28200
	}
	return &Watcher{
		ADBAddr:   "127.0.0.1:5037",
		Port:      port,
		Runner:    execRunner{},
		OnState:   onState,
		lastState: StateDisconnected,
	}
}

// State returns the latest DeviceState observed by the watcher.
func (w *Watcher) State() (DeviceState, string) {
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.lastState, w.forwardedSer
}

func (w *Watcher) emit(state DeviceState, serial, errMsg string) {
	w.mu.Lock()
	changed := w.lastState != state || w.forwardedSer != serial || errMsg != ""
	w.lastState = state
	if state == StateDisconnected {
		w.forwardedSer = ""
	} else if state == StateTunnelReady {
		w.forwardedSer = serial
	}
	w.mu.Unlock()

	if changed && w.OnState != nil {
		w.OnState(DeviceEvent{
			State:     state,
			Serial:    serial,
			Err:       errMsg,
			UpdatedAt: time.Now(),
		})
	}
}

// Watch runs the event-driven ADB hotplug loop until ctx is canceled.
func (w *Watcher) Watch(ctx context.Context) {
	backoff := 300 * time.Millisecond
	for {
		if ctx.Err() != nil {
			return
		}
		err := w.trackSession(ctx)
		if ctx.Err() != nil {
			return
		}

		if err != nil && !w.SkipStartServer {
			w.mu.Lock()
			tried := w.startTried
			w.startTried = true
			w.mu.Unlock()
			if !tried {
				_, _ = w.Runner.Run(ctx, "adb", "start-server")
			}
		}

		w.emit(StateDisconnected, "", "")
		select {
		case <-ctx.Done():
			return
		case <-time.After(backoff):
			if backoff < 3*time.Second {
				backoff *= 2
			}
		}
	}
}

func (w *Watcher) trackSession(ctx context.Context) error {
	dialer := net.Dialer{Timeout: 2 * time.Second}
	conn, err := dialer.DialContext(ctx, "tcp", w.ADBAddr)
	if err != nil {
		return err
	}
	defer conn.Close()

	go func() {
		<-ctx.Done()
		_ = conn.Close()
	}()

	const req = "host:track-devices"
	cmd := fmt.Sprintf("%04x%s", len(req), req)
	if _, err := io.WriteString(conn, cmd); err != nil {
		return err
	}

	var status [4]byte
	if _, err := io.ReadFull(conn, status[:]); err != nil {
		return err
	}
	if string(status[:]) != "OKAY" {
		return fmt.Errorf("adb track-devices rejected: %s", string(status[:]))
	}

	for {
		payload, err := readADBTrackPacket(conn)
		if err != nil {
			return err
		}
		w.handleDeviceList(ctx, parseTrackDevicesPayload(payload))
	}
}

func (w *Watcher) handleDeviceList(ctx context.Context, devices []adbDevice) {
	if len(devices) == 0 {
		w.emit(StateDisconnected, "", "")
		return
	}

	var authorized *adbDevice
	var unauthorized *adbDevice
	for i := range devices {
		switch devices[i].status {
		case "device":
			if authorized == nil {
				authorized = &devices[i]
			}
		case "unauthorized", "offline":
			if unauthorized == nil {
				unauthorized = &devices[i]
			}
		}
	}

	if authorized == nil {
		if unauthorized != nil {
			w.emit(StateUnauthorized, unauthorized.serial, "USB debugging authorization pending on phone")
		} else {
			w.emit(StateDisconnected, "", "")
		}
		return
	}

	w.mu.Lock()
	alreadyReady := w.lastState == StateTunnelReady && w.forwardedSer == authorized.serial
	w.mu.Unlock()
	if alreadyReady {
		return
	}

	w.emit(StateAuthorized, authorized.serial, "")
	w.emit(StateForwarding, authorized.serial, "")

	spec := fmt.Sprintf("tcp:%d", w.Port)
	out, err := w.Runner.Run(ctx, "adb", "-s", authorized.serial, "reverse", spec, spec)
	if err != nil {
		w.emit(StateError, authorized.serial, fmt.Sprintf("adb reverse failed: %v (%s)", err, strings.TrimSpace(string(out))))
		return
	}

	if listOut, listErr := w.Runner.Run(ctx, "adb", "-s", authorized.serial, "reverse", "--list"); listErr == nil {
		if !strings.Contains(string(listOut), spec) {
			w.emit(StateError, authorized.serial, "adb reverse rule not confirmed in reverse --list")
			return
		}
	}

	w.emit(StateTunnelReady, authorized.serial, "")
}

func readADBTrackPacket(r io.Reader) (string, error) {
	var lenHex [4]byte
	if _, err := io.ReadFull(r, lenHex[:]); err != nil {
		return "", err
	}
	n, err := strconv.ParseInt(string(lenHex[:]), 16, 32)
	if err != nil || n < 0 || n > 1024*1024 {
		return "", fmt.Errorf("invalid adb length prefix %q: %w", string(lenHex[:]), err)
	}
	if n == 0 {
		return "", nil
	}
	buf := make([]byte, n)
	if _, err := io.ReadFull(r, buf); err != nil {
		return "", err
	}
	return string(buf), nil
}

func parseTrackDevicesPayload(payload string) []adbDevice {
	var out []adbDevice
	for _, line := range strings.Split(payload, "\n") {
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		fields := strings.Fields(line)
		if len(fields) >= 2 {
			out = append(out, adbDevice{
				serial: fields[0],
				status: fields[1],
			})
		}
	}
	return out
}
