//go:build !linux || !cgo || nogui

package ui

import "phonedrop/v2/desktop/core/transfer"

type Callbacks struct {
	OnSendFilePath      func(path string)
	OnSyncClipboard     func()
	OnTogglePhoneMirror func(active bool)
	OnToggleLinuxStream func(active bool)
	OnMirrorCanvasReady func(xid uint64)
	OnMirrorPointer     func(evType uint8, btn uint8, nx, ny int32)
	OnQuit              func()
}

type Controller struct{}

func NewController(_ Callbacks) *Controller { return &Controller{} }
func (c *Controller) Run()                  {}
func (c *Controller) SetStatus(_ string)    {}
func (c *Controller) SetClipboardPreview(_ string) {}
func (c *Controller) UpdateProgress(_ transfer.ProgressReport) {}
func (c *Controller) Quit()                 {}
