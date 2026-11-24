//go:build !linux || !cgo || nogui

package ui

import "phonedrop/v2/desktop/core/transfer"

// FileRow represents one entry displayed in Ruang File Bersama.
type FileRow struct {
	Name   string
	Size   int64
	ModMs  int64
	Origin string // "phone" | "laptop" | ""
}

// Callbacks connects native UI events back into Go application logic.
type Callbacks struct {
	OnSendFilePath      func(path string)
	OnSyncClipboard     func()
	OnTogglePhoneMirror func(active bool)
	OnToggleLinuxStream func(active bool)
	OnOpenFile          func(name string)
	OnSendSharedFile    func(name string)
	OnDeleteSharedFile  func(name string)
	OnOpenFolder        func()
	OnRefreshFiles      func()
	OnQuit              func()
}

type Controller struct{}

func NewController(_ Callbacks) *Controller                     { return &Controller{} }
func (c *Controller) Run()                                      {}
func (c *Controller) SetConnectionBadge(_ string, _ string, _ bool) {}
func (c *Controller) SetActivityStatus(_ string)                {}
func (c *Controller) SetClipboardPreview(_ string)              {}
func (c *Controller) UpdateProgress(_ transfer.ProgressReport)  {}
func (c *Controller) ClearProgress()                            {}
func (c *Controller) SetPhoneMirrorActive(_ bool)               {}
func (c *Controller) SetLinuxStreamActive(_ bool)               {}
func (c *Controller) SetFileList(_ []FileRow)                   {}
func (c *Controller) Quit()                                     {}
