package input

import (
	"os/exec"
	"strconv"
)

type Engine string

const (
	EngineXdotool Engine = "xdotool"
	EngineYdotool Engine = "ydotool"
	EngineNone    Engine = "none"
)

type Controller struct {
	engine Engine
}

type EventPayload struct {
	SubType string `json:"sub_type"` // "move", "click", "key", "text"
	DX      int    `json:"dx"`
	DY      int    `json:"dy"`
	Button  string `json:"button"` // "left", "right", "scroll_up", "scroll_down"
	Text    string `json:"text"`
	Key     string `json:"key"` // "Return", "BackSpace", "Escape"
}

func NewController() *Controller {
	if _, err := exec.LookPath("xdotool"); err == nil {
		return &Controller{engine: EngineXdotool}
	}
	if _, err := exec.LookPath("ydotool"); err == nil {
		return &Controller{engine: EngineYdotool}
	}
	return &Controller{engine: EngineNone}
}

func (c *Controller) EngineName() string {
	return string(c.engine)
}

func (c *Controller) Execute(ev EventPayload) {
	if c.engine == EngineNone {
		return
	}

	switch ev.SubType {
	case "move":
		c.moveRelative(ev.DX, ev.DY)
	case "click":
		c.mouseClick(ev.Button)
	case "text":
		c.typeString(ev.Text)
	case "key":
		c.pressKey(ev.Key)
	}
}

func (c *Controller) moveRelative(dx, dy int) {
	if dx == 0 && dy == 0 {
		return
	}
	sx := strconv.Itoa(dx)
	sy := strconv.Itoa(dy)

	if c.engine == EngineXdotool {
		_ = exec.Command("xdotool", "mousemove_relative", "--", sx, sy).Run()
		return
	}
	_ = exec.Command("ydotool", "mousemove", "-x", sx, "-y", sy).Run()
}

func (c *Controller) mouseClick(btn string) {
	if c.engine == EngineXdotool {
		code := "1"
		switch btn {
		case "right":
			code = "3"
		case "scroll_up":
			code = "4"
		case "scroll_down":
			code = "5"
		}
		_ = exec.Command("xdotool", "click", code).Run()
		return
	}

	// Fallback untuk ydotool
	switch btn {
	case "right":
		_ = exec.Command("ydotool", "click", "0xC1").Run()
	default:
		_ = exec.Command("ydotool", "click", "0xC0").Run()
	}
}

func (c *Controller) typeString(text string) {
	if text == "" {
		return
	}
	if c.engine == EngineXdotool {
		_ = exec.Command("xdotool", "type", "--delay", "0", "--", text).Run()
		return
	}
	_ = exec.Command("ydotool", "type", "--", text).Run()
}

func (c *Controller) pressKey(key string) {
	if key == "" {
		return
	}
	if c.engine == EngineXdotool {
		_ = exec.Command("xdotool", "key", key).Run()
		return
	}
	// Pemetaan kode tombol dasar ydotool (28=Enter, 14=Backspace, 1=Esc)
	code := "28"
	if key == "BackSpace" {
		code = "14"
	} else if key == "Escape" {
		code = "1"
	}
	_ = exec.Command("ydotool", "key", code+":1", code+":0").Run()
}
