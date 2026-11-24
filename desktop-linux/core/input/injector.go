// Package input executes incoming PDP2 InputEvent packets on the Linux desktop
// via xdotool (X11) or ydotool (Wayland).
package input

import (
	"os/exec"
	"strconv"

	"phonedrop/v2/desktop/core/protocol"
)

// Engine identifies the active input injection tool.
type Engine string

const (
	EngineXdotool Engine = "xdotool"
	EngineYdotool Engine = "ydotool"
	EngineNone    Engine = "none"
)

// Injector translates InputEventPayload packets into OS mouse/keyboard actions.
type Injector struct {
	engine       Engine
	screenWidth  int
	screenHeight int
}

// NewInjector detects xdotool/ydotool and configures screen dimensions for normalized taps.
func NewInjector(screenWidth, screenHeight int) *Injector {
	if screenWidth <= 0 {
		screenWidth = 1920
	}
	if screenHeight <= 0 {
		screenHeight = 1080
	}
	eng := EngineNone
	if _, err := exec.LookPath("xdotool"); err == nil {
		eng = EngineXdotool
	} else if _, err := exec.LookPath("ydotool"); err == nil {
		eng = EngineYdotool
	}
	return &Injector{
		engine:       eng,
		screenWidth:  screenWidth,
		screenHeight: screenHeight,
	}
}

// EngineName returns the detected injector backend name.
func (i *Injector) EngineName() string {
	return string(i.engine)
}

// Execute dispatches a decoded InputEventPayload to the Linux display server.
func (i *Injector) Execute(ev protocol.InputEventPayload) {
	if i.engine == EngineNone {
		return
	}

	switch ev.EventType {
	case protocol.InputMoveRel:
		i.moveRelative(int(ev.X), int(ev.Y))
	case protocol.InputTapAbs:
		px, py := i.denormalize(ev.X, ev.Y)
		i.moveAbsoluteAndAction(px, py, ev.ActionOrButton, ev.Arg1)
	case protocol.InputMouseButton:
		i.mouseButton(ev.ActionOrButton, ev.Arg1)
	case protocol.InputScroll:
		i.scroll(int(ev.Y))
	case protocol.InputKeyCode:
		i.pressKey(ev.Arg1, ev.Text)
	case protocol.InputTextUTF8:
		i.typeText(ev.Text)
	case protocol.InputSwipeAbs:
		px, py := i.denormalize(ev.X, ev.Y)
		i.moveAbsoluteAndAction(px, py, 1, 2)
	}
}

func (i *Injector) denormalize(nx, ny int32) (int, int) {
	if nx < 0 {
		nx = 0
	}
	if nx > 65535 {
		nx = 65535
	}
	if ny < 0 {
		ny = 0
	}
	if ny > 65535 {
		ny = 65535
	}
	px := int(nx) * i.screenWidth / 65535
	py := int(ny) * i.screenHeight / 65535
	return px, py
}

func (i *Injector) moveRelative(dx, dy int) {
	if dx == 0 && dy == 0 {
		return
	}
	sx := strconv.Itoa(dx)
	sy := strconv.Itoa(dy)
	if i.engine == EngineXdotool {
		_ = exec.Command("xdotool", "mousemove_relative", "--", sx, sy).Run()
		return
	}
	_ = exec.Command("ydotool", "mousemove", "-x", sx, "-y", sy).Run()
}

func (i *Injector) moveAbsoluteAndAction(x, y int, button uint8, action int32) {
	if i.engine != EngineXdotool {
		return
	}
	sx := strconv.Itoa(x)
	sy := strconv.Itoa(y)
	_ = exec.Command("xdotool", "mousemove", "--sync", sx, sy).Run()
	btnStr := buttonCode(button)
	switch action {
	case 0: // up
		_ = exec.Command("xdotool", "mouseup", btnStr).Run()
	case 1: // down
		_ = exec.Command("xdotool", "mousedown", btnStr).Run()
	case 2: // tap/click
		_ = exec.Command("xdotool", "click", btnStr).Run()
	}
}

func (i *Injector) mouseButton(button uint8, action int32) {
	if i.engine == EngineXdotool {
		btnStr := buttonCode(button)
		switch action {
		case 0:
			_ = exec.Command("xdotool", "mouseup", btnStr).Run()
		case 1:
			_ = exec.Command("xdotool", "mousedown", btnStr).Run()
		default:
			_ = exec.Command("xdotool", "click", btnStr).Run()
		}
		return
	}
	code := "0xC0"
	if button == 3 {
		code = "0xC1"
	}
	_ = exec.Command("ydotool", "click", code).Run()
}

func (i *Injector) scroll(dy int) {
	if dy == 0 || i.engine != EngineXdotool {
		return
	}
	btn := "5"
	if dy > 0 {
		btn = "4"
	}
	_ = exec.Command("xdotool", "click", btn).Run()
}

func (i *Injector) typeText(text string) {
	if text == "" {
		return
	}
	if i.engine == EngineXdotool {
		_ = exec.Command("xdotool", "type", "--delay", "0", "--", text).Run()
		return
	}
	_ = exec.Command("ydotool", "type", "--", text).Run()
}

func (i *Injector) pressKey(keyCode int32, keyName string) {
	if i.engine == EngineXdotool {
		if keyName != "" {
			_ = exec.Command("xdotool", "key", keyName).Run()
			return
		}
		if keyCode > 0 {
			_ = exec.Command("xdotool", "key", strconv.Itoa(int(keyCode))).Run()
		}
		return
	}
	if keyCode > 0 {
		s := strconv.Itoa(int(keyCode))
		_ = exec.Command("ydotool", "key", s+":1", s+":0").Run()
	}
}

func buttonCode(btn uint8) string {
	switch btn {
	case 2:
		return "2"
	case 3:
		return "3"
	default:
		return "1"
	}
}
