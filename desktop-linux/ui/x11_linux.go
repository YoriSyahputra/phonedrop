//go:build linux && cgo && !nogui

package ui

/*
#cgo pkg-config: x11 cairo-xlib
#include <X11/Xlib.h>
#include <X11/Xutil.h>
#include <X11/Xatom.h>
#include <cairo/cairo.h>
#include <cairo/cairo-xlib.h>
#include <fcntl.h>
#include <poll.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

extern void goOnSendFileClicked();
extern void goOnSyncClipClicked();
extern void goOnTogglePhoneMirror(int active);
extern void goOnToggleLinuxStream(int active);
extern void goOnCanvasRealized(uint64_t xid);
extern void goOnCanvasPointer(uint8_t evType, uint8_t btn, int32_t nx, int32_t ny);
extern void goOnQuitRequested();

#define SYSTEM_TRAY_REQUEST_DOCK 0

typedef struct {
    int x, y, w, h;
} Rect;

static struct {
    pthread_mutex_t mu;
    int wakeup_fd[2];
    Display *dpy;
    int screen;
    Window main_win;
    Window mirror_win;
    Window tray_win;
    Atom wm_delete;
    int win_w;
    int win_h;
    int mirror_w;
    int mirror_h;
    int visible;
    int tray_docked;
    int quit_flag;

    char *status_text;
    char *clip_text;
    char *progress_text;
    double progress_frac;
    int phone_mirror_active;
    int linux_stream_active;

    Rect btn_send;
    Rect btn_clip;
    Rect btn_phone_mirror;
    Rect btn_linux_stream;
} g_x11 = {
    .mu = PTHREAD_MUTEX_INITIALIZER,
    .wakeup_fd = {-1, -1},
    .win_w = 480,
    .win_h = 620,
    .mirror_w = 448,
    .mirror_h = 376,
    .visible = 1
};

static void pd_wake(void) {
    if (g_x11.wakeup_fd[1] >= 0) {
        char b = 1;
        ssize_t ignored = write(g_x11.wakeup_fd[1], &b, 1);
        (void)ignored;
    }
}

static void pd_post_status(const char *txt) {
    pthread_mutex_lock(&g_x11.mu);
    free(g_x11.status_text);
    g_x11.status_text = strdup(txt ? txt : "");
    pthread_mutex_unlock(&g_x11.mu);
    pd_wake();
}

static void pd_post_clip(const char *txt) {
    pthread_mutex_lock(&g_x11.mu);
    free(g_x11.clip_text);
    g_x11.clip_text = strdup(txt ? txt : "");
    pthread_mutex_unlock(&g_x11.mu);
    pd_wake();
}

static void pd_post_progress(double frac, const char *txt) {
    pthread_mutex_lock(&g_x11.mu);
    g_x11.progress_frac = frac;
    free(g_x11.progress_text);
    g_x11.progress_text = strdup(txt ? txt : "");
    pthread_mutex_unlock(&g_x11.mu);
    pd_wake();
}

static void pd_post_quit(void) {
    pthread_mutex_lock(&g_x11.mu);
    g_x11.quit_flag = 1;
    pthread_mutex_unlock(&g_x11.mu);
    pd_wake();
}

static void draw_rounded_rect(cairo_t *cr, double x, double y, double w, double h, double r) {
    double pi = 3.141592653589793;
    cairo_new_sub_path(cr);
    cairo_arc(cr, x + w - r, y + r, r, -pi / 2.0, 0);
    cairo_arc(cr, x + w - r, y + h - r, r, 0, pi / 2.0);
    cairo_arc(cr, x + r, y + h - r, r, pi / 2.0, pi);
    cairo_arc(cr, x + r, y + r, r, pi, 3.0 * pi / 2.0);
    cairo_close_path(cr);
}

static void draw_button(cairo_t *cr, Rect r, const char *label, int active) {
    draw_rounded_rect(cr, r.x, r.y, r.w, r.h, 6.0);
    if (active) {
        cairo_set_source_rgb(cr, 0.18, 0.56, 0.34);
    } else {
        cairo_set_source_rgb(cr, 0.14, 0.19, 0.27);
    }
    cairo_fill_preserve(cr);
    cairo_set_source_rgb(cr, 0.25, 0.33, 0.45);
    cairo_set_line_width(cr, 1.0);
    cairo_stroke(cr);

    cairo_select_font_face(cr, "Sans", CAIRO_FONT_SLANT_NORMAL, CAIRO_FONT_WEIGHT_BOLD);
    cairo_set_font_size(cr, 12.5);
    cairo_text_extents_t ext;
    cairo_text_extents(cr, label, &ext);
    cairo_set_source_rgb(cr, 0.92, 0.95, 0.98);
    cairo_move_to(cr, r.x + (r.w - ext.width) / 2.0 - ext.x_bearing, r.y + (r.h + ext.height) / 2.0 - 1.0);
    cairo_show_text(cr, label);
}

static void redraw_main_window(void) {
    if (!g_x11.dpy || !g_x11.main_win || !g_x11.visible) return;

    pthread_mutex_lock(&g_x11.mu);
    int w = g_x11.win_w;
    int h = g_x11.win_h;
    char *status = strdup(g_x11.status_text ? g_x11.status_text : "Listening on TCP :28200 (USB ADB + LAN mDNS)");
    char *clip = strdup(g_x11.clip_text ? g_x11.clip_text : "Clipboard: ready");
    char *prog = strdup(g_x11.progress_text ? g_x11.progress_text : "Transfer idle (64KB streaming + SHA-256)");
    double frac = g_x11.progress_frac;
    int pm = g_x11.phone_mirror_active;
    int ls = g_x11.linux_stream_active;

    int pad = 16;
    int halfW = (w - pad * 3) / 2;
    if (halfW < 80) halfW = 80;
    g_x11.btn_send = (Rect){pad, 56, halfW, 34};
    g_x11.btn_clip = (Rect){pad * 2 + halfW, 56, halfW, 34};
    g_x11.btn_phone_mirror = (Rect){pad, 174, halfW, 34};
    g_x11.btn_linux_stream = (Rect){pad * 2 + halfW, 174, halfW, 34};
    Rect bSend = g_x11.btn_send;
    Rect bClip = g_x11.btn_clip;
    Rect bPM = g_x11.btn_phone_mirror;
    Rect bLS = g_x11.btn_linux_stream;
    pthread_mutex_unlock(&g_x11.mu);

    Visual *vis = DefaultVisual(g_x11.dpy, g_x11.screen);
    cairo_surface_t *surf = cairo_xlib_surface_create(g_x11.dpy, g_x11.main_win, vis, w, h);
    cairo_t *cr = cairo_create(surf);

    // Background
    cairo_set_source_rgb(cr, 0.063, 0.075, 0.094);
    cairo_rectangle(cr, 0, 0, w, 224);
    cairo_fill(cr);
    cairo_rectangle(cr, 0, 224, w, h - 224);
    cairo_fill(cr);

    // Header Title + Status
    cairo_select_font_face(cr, "Sans", CAIRO_FONT_SLANT_NORMAL, CAIRO_FONT_WEIGHT_BOLD);
    cairo_set_font_size(cr, 15.0);
    cairo_set_source_rgb(cr, 0.90, 0.93, 0.96);
    cairo_move_to(cr, pad, 24);
    cairo_show_text(cr, "PhoneDrop v2");

    cairo_select_font_face(cr, "Sans", CAIRO_FONT_SLANT_NORMAL, CAIRO_FONT_WEIGHT_NORMAL);
    cairo_set_font_size(cr, 12.0);
    cairo_set_source_rgb(cr, 0.44, 0.76, 0.56);
    cairo_move_to(cr, pad, 44);
    cairo_show_text(cr, status);

    draw_button(cr, bSend, "Send File...", 0);
    draw_button(cr, bClip, "Push Clipboard", 0);

    // Progress Bar
    int barW = w - pad * 2;
    draw_rounded_rect(cr, pad, 102, barW, 12, 5.0);
    cairo_set_source_rgb(cr, 0.13, 0.16, 0.21);
    cairo_fill(cr);
    if (frac > 0.0) {
        double fw = barW * (frac > 1.0 ? 1.0 : frac);
        if (fw < 8.0) fw = 8.0;
        draw_rounded_rect(cr, pad, 102, fw, 12, 5.0);
        cairo_set_source_rgb(cr, 0.22, 0.55, 0.97);
        cairo_fill(cr);
    }

    cairo_set_font_size(cr, 11.5);
    cairo_set_source_rgb(cr, 0.80, 0.84, 0.88);
    cairo_move_to(cr, pad, 132);
    cairo_show_text(cr, prog);

    cairo_set_source_rgb(cr, 0.56, 0.62, 0.68);
    cairo_move_to(cr, pad, 154);
    cairo_show_text(cr, clip);

    draw_button(cr, bPM, pm ? "Stop Phone Mirror" : "Mirror Phone -> Linux", pm);
    draw_button(cr, bLS, ls ? "Stop Desktop Stream" : "Stream Linux -> Phone", ls);

    cairo_destroy(cr);
    cairo_surface_destroy(surf);
    XFlush(g_x11.dpy);

    free(status);
    free(clip);
    free(prog);
}

static void redraw_tray_icon(void) {
    if (!g_x11.dpy || !g_x11.tray_win) return;
    Visual *vis = DefaultVisual(g_x11.dpy, g_x11.screen);
    cairo_surface_t *surf = cairo_xlib_surface_create(g_x11.dpy, g_x11.tray_win, vis, 24, 24);
    cairo_t *cr = cairo_create(surf);

    cairo_set_source_rgb(cr, 0.08, 0.10, 0.14);
    cairo_paint(cr);
    draw_rounded_rect(cr, 2, 2, 20, 20, 5.0);
    cairo_set_source_rgb(cr, 0.22, 0.55, 0.97);
    cairo_fill(cr);

    cairo_select_font_face(cr, "Sans", CAIRO_FONT_SLANT_NORMAL, CAIRO_FONT_WEIGHT_BOLD);
    cairo_set_font_size(cr, 11.0);
    cairo_set_source_rgb(cr, 1.0, 1.0, 1.0);
    cairo_move_to(cr, 5.0, 16.0);
    cairo_show_text(cr, "PD");

    cairo_destroy(cr);
    cairo_surface_destroy(surf);
    XFlush(g_x11.dpy);
}

static void dock_tray_icon(void) {
    char atom_name[64];
    snprintf(atom_name, sizeof(atom_name), "_NET_SYSTEM_TRAY_S%d", g_x11.screen);
    Atom tray_atom = XInternAtom(g_x11.dpy, atom_name, False);
    Window tray_owner = XGetSelectionOwner(g_x11.dpy, tray_atom);
    if (tray_owner == None) {
        return;
    }

    g_x11.tray_win = XCreateSimpleWindow(
        g_x11.dpy,
        RootWindow(g_x11.dpy, g_x11.screen),
        0, 0, 24, 24, 0, 0, 0x101318
    );
    XSelectInput(g_x11.dpy, g_x11.tray_win, ExposureMask | ButtonPressMask);

    Atom xembed_info = XInternAtom(g_x11.dpy, "_XEMBED_INFO", False);
    unsigned long info[2] = {0, 1}; // version 0, XEMBED_MAPPED = 1
    XChangeProperty(g_x11.dpy, g_x11.tray_win, xembed_info, xembed_info, 32, PropModeReplace, (unsigned char *)info, 2);

    XEvent ev;
    memset(&ev, 0, sizeof(ev));
    ev.xclient.type = ClientMessage;
    ev.xclient.window = tray_owner;
    ev.xclient.message_type = XInternAtom(g_x11.dpy, "_NET_SYSTEM_TRAY_OPCODE", False);
    ev.xclient.format = 32;
    ev.xclient.data.l[0] = CurrentTime;
    ev.xclient.data.l[1] = SYSTEM_TRAY_REQUEST_DOCK;
    ev.xclient.data.l[2] = (long)g_x11.tray_win;
    XSendEvent(g_x11.dpy, tray_owner, False, NoEventMask, &ev);
    XMapRaised(g_x11.dpy, g_x11.tray_win);
    g_x11.tray_docked = 1;
}

static int point_in_rect(int x, int y, Rect r) {
    return x >= r.x && x <= (r.x + r.w) && y >= r.y && y <= (r.y + r.h);
}

static void pd_init_and_run(void) {
    XInitThreads();
    Display *dpy = XOpenDisplay(NULL);
    if (!dpy) {
        return;
    }

    if (pipe(g_x11.wakeup_fd) == 0) {
        fcntl(g_x11.wakeup_fd[0], F_SETFL, O_NONBLOCK);
        fcntl(g_x11.wakeup_fd[1], F_SETFL, O_NONBLOCK);
    }

    g_x11.dpy = dpy;
    g_x11.screen = DefaultScreen(dpy);
    Window root = RootWindow(dpy, g_x11.screen);

    g_x11.main_win = XCreateSimpleWindow(dpy, root, 80, 80, g_x11.win_w, g_x11.win_h, 0, 0, 0x101318);
    XStoreName(dpy, g_x11.main_win, "PhoneDrop v2");
    XSelectInput(dpy, g_x11.main_win, ExposureMask | ButtonPressMask | StructureNotifyMask);

    g_x11.wm_delete = XInternAtom(dpy, "WM_DELETE_WINDOW", False);
    XSetWMProtocols(dpy, g_x11.main_win, &g_x11.wm_delete, 1);

    g_x11.mirror_win = XCreateSimpleWindow(dpy, g_x11.main_win, 16, 222, g_x11.mirror_w, g_x11.mirror_h, 0, 0, 0x080A0E);
    XSelectInput(dpy, g_x11.mirror_win, ButtonPressMask | ExposureMask);
    XMapWindow(dpy, g_x11.mirror_win);
    XMapWindow(dpy, g_x11.main_win);

    dock_tray_icon();
    XFlush(dpy);

    goOnCanvasRealized((uint64_t)g_x11.mirror_win);
    redraw_main_window();
    redraw_tray_icon();

    int xfd = ConnectionNumber(dpy);
    for (;;) {
        pthread_mutex_lock(&g_x11.mu);
        int should_quit = g_x11.quit_flag;
        pthread_mutex_unlock(&g_x11.mu);
        if (should_quit) break;

        while (XPending(dpy) > 0) {
            XEvent ev;
            XNextEvent(dpy, &ev);

            if (ev.type == ClientMessage && (Atom)ev.xclient.data.l[0] == g_x11.wm_delete) {
                if (g_x11.tray_docked) {
                    pthread_mutex_lock(&g_x11.mu);
                    g_x11.visible = 0;
                    pthread_mutex_unlock(&g_x11.mu);
                    XUnmapWindow(dpy, g_x11.main_win);
                } else {
                    goOnQuitRequested();
                    pd_post_quit();
                }
            } else if (ev.type == ConfigureNotify && ev.xconfigure.window == g_x11.main_win) {
                int nw = ev.xconfigure.width;
                int nh = ev.xconfigure.height;
                if (nw != g_x11.win_w || nh != g_x11.win_h) {
                    g_x11.win_w = nw;
                    g_x11.win_h = nh;
                    int mw = nw - 32;
                    int mh = nh - 238;
                    if (mw < 120) mw = 120;
                    if (mh < 120) mh = 120;
                    g_x11.mirror_w = mw;
                    g_x11.mirror_h = mh;
                    XMoveResizeWindow(dpy, g_x11.mirror_win, 16, 222, (unsigned int)mw, (unsigned int)mh);
                    redraw_main_window();
                }
            } else if (ev.type == Expose) {
                if (ev.xexpose.window == g_x11.main_win && ev.xexpose.count == 0) {
                    redraw_main_window();
                } else if (ev.xexpose.window == g_x11.tray_win && ev.xexpose.count == 0) {
                    redraw_tray_icon();
                }
            } else if (ev.type == ButtonPress) {
                if (ev.xbutton.window == g_x11.tray_win) {
                    if (ev.xbutton.button == Button1) {
                        pthread_mutex_lock(&g_x11.mu);
                        int vis = !g_x11.visible;
                        g_x11.visible = vis;
                        pthread_mutex_unlock(&g_x11.mu);
                        if (vis) {
                            XMapRaised(dpy, g_x11.main_win);
                            redraw_main_window();
                        } else {
                            XUnmapWindow(dpy, g_x11.main_win);
                        }
                    } else if (ev.xbutton.button == Button2) {
                        goOnSendFileClicked();
                    } else if (ev.xbutton.button == Button3) {
                        goOnQuitRequested();
                        pd_post_quit();
                    }
                } else if (ev.xbutton.window == g_x11.mirror_win) {
                    int mw = g_x11.mirror_w > 0 ? g_x11.mirror_w : 1;
                    int mh = g_x11.mirror_h > 0 ? g_x11.mirror_h : 1;
                    int32_t nx = (int32_t)(((double)ev.xbutton.x / (double)mw) * 65535.0);
                    int32_t ny = (int32_t)(((double)ev.xbutton.y / (double)mh) * 65535.0);
                    if (nx < 0) nx = 0; if (nx > 65535) nx = 65535;
                    if (ny < 0) ny = 0; if (ny > 65535) ny = 65535;
                    goOnCanvasPointer(2, (uint8_t)ev.xbutton.button, nx, ny);
                } else if (ev.xbutton.window == g_x11.main_win) {
                    int cx = ev.xbutton.x;
                    int cy = ev.xbutton.y;
                    pthread_mutex_lock(&g_x11.mu);
                    Rect bSend = g_x11.btn_send;
                    Rect bClip = g_x11.btn_clip;
                    Rect bPM = g_x11.btn_phone_mirror;
                    Rect bLS = g_x11.btn_linux_stream;
                    pthread_mutex_unlock(&g_x11.mu);

                    if (point_in_rect(cx, cy, bSend)) {
                        goOnSendFileClicked();
                    } else if (point_in_rect(cx, cy, bClip)) {
                        goOnSyncClipClicked();
                    } else if (point_in_rect(cx, cy, bPM)) {
                        pthread_mutex_lock(&g_x11.mu);
                        g_x11.phone_mirror_active = !g_x11.phone_mirror_active;
                        int state = g_x11.phone_mirror_active;
                        pthread_mutex_unlock(&g_x11.mu);
                        goOnTogglePhoneMirror(state);
                        redraw_main_window();
                    } else if (point_in_rect(cx, cy, bLS)) {
                        pthread_mutex_lock(&g_x11.mu);
                        g_x11.linux_stream_active = !g_x11.linux_stream_active;
                        int state = g_x11.linux_stream_active;
                        pthread_mutex_unlock(&g_x11.mu);
                        goOnToggleLinuxStream(state);
                        redraw_main_window();
                    }
                }
            }
        }

        struct pollfd fds[2];
        fds[0].fd = xfd;
        fds[0].events = POLLIN;
        fds[1].fd = g_x11.wakeup_fd[0];
        fds[1].events = POLLIN;
        if (poll(fds, g_x11.wakeup_fd[0] >= 0 ? 2 : 1, -1) > 0) {
            if (fds[1].revents & POLLIN) {
                char buf[64];
                while (read(g_x11.wakeup_fd[0], buf, sizeof(buf)) > 0) {}
                redraw_main_window();
            }
        }
    }

    if (g_x11.tray_win) XDestroyWindow(dpy, g_x11.tray_win);
    XDestroyWindow(dpy, g_x11.main_win);
    XCloseDisplay(dpy);
    g_x11.dpy = NULL;
    if (g_x11.wakeup_fd[0] >= 0) close(g_x11.wakeup_fd[0]);
    if (g_x11.wakeup_fd[1] >= 0) close(g_x11.wakeup_fd[1]);
}
*/
import "C"

import (
	"fmt"
	"os/exec"
	"runtime"
	"strings"
	"sync"
	"unsafe"

	"phonedrop/v2/desktop/core/protocol"
	"phonedrop/v2/desktop/core/transfer"
)

// Callbacks connects native X11 UI events back into Go application logic.
type Callbacks struct {
	OnSendFilePath      func(path string)
	OnSyncClipboard     func()
	OnTogglePhoneMirror func(active bool)
	OnToggleLinuxStream func(active bool)
	OnMirrorCanvasReady func(xid uint64)
	OnMirrorPointer     func(evType uint8, btn uint8, nx, ny int32)
	OnQuit              func()
}

var (
	cbMu      sync.RWMutex
	activeCBs Callbacks
)

//export goOnSendFileClicked
func goOnSendFileClicked() {
	cbMu.RLock()
	fn := activeCBs.OnSendFilePath
	cbMu.RUnlock()
	if fn == nil {
		return
	}
	go func() {
		out, err := exec.Command("zenity", "--file-selection", "--title=Send File via PhoneDrop v2").Output()
		if err != nil {
			return
		}
		path := strings.TrimSpace(string(out))
		if path != "" {
			fn(path)
		}
	}()
}

//export goOnSyncClipClicked
func goOnSyncClipClicked() {
	cbMu.RLock()
	fn := activeCBs.OnSyncClipboard
	cbMu.RUnlock()
	if fn != nil {
		go fn()
	}
}

//export goOnTogglePhoneMirror
func goOnTogglePhoneMirror(active C.int) {
	cbMu.RLock()
	fn := activeCBs.OnTogglePhoneMirror
	cbMu.RUnlock()
	if fn != nil {
		go fn(active != 0)
	}
}

//export goOnToggleLinuxStream
func goOnToggleLinuxStream(active C.int) {
	cbMu.RLock()
	fn := activeCBs.OnToggleLinuxStream
	cbMu.RUnlock()
	if fn != nil {
		go fn(active != 0)
	}
}

//export goOnCanvasRealized
func goOnCanvasRealized(xid C.uint64_t) {
	cbMu.RLock()
	fn := activeCBs.OnMirrorCanvasReady
	cbMu.RUnlock()
	if fn != nil {
		go fn(uint64(xid))
	}
}

//export goOnCanvasPointer
func goOnCanvasPointer(_ C.uint8_t, btn C.uint8_t, nx C.int32_t, ny C.int32_t) {
	cbMu.RLock()
	fn := activeCBs.OnMirrorPointer
	cbMu.RUnlock()
	if fn != nil {
		go fn(protocol.InputTapAbs, uint8(btn), int32(nx), int32(ny))
	}
}

//export goOnQuitRequested
func goOnQuitRequested() {
	cbMu.RLock()
	fn := activeCBs.OnQuit
	cbMu.RUnlock()
	if fn != nil {
		go fn()
	}
}

// Controller manages the native X11/Cairo window and XEmbed tray indicator.
type Controller struct{}

// NewController registers callbacks for the native X11/Cairo UI.
func NewController(cbs Callbacks) *Controller {
	cbMu.Lock()
	activeCBs = cbs
	cbMu.Unlock()
	return &Controller{}
}

// Run starts the X11 event loop on a locked OS thread and blocks until quit.
func (c *Controller) Run() {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	C.pd_init_and_run()
}

// SetStatus posts a status label update cleanly onto the X11 loop.
func (c *Controller) SetStatus(text string) {
	cs := C.CString(text)
	defer C.free(unsafe.Pointer(cs))
	C.pd_post_status(cs)
}

// SetClipboardPreview updates the clipboard preview line cleanly.
func (c *Controller) SetClipboardPreview(text string) {
	if len(text) > 56 {
		text = text[:56] + "..."
	}
	cs := C.CString("Clipboard: " + text)
	defer C.free(unsafe.Pointer(cs))
	C.pd_post_clip(cs)
}

// UpdateProgress updates the progress bar and transfer speed text from any goroutine.
func (c *Controller) UpdateProgress(rep transfer.ProgressReport) {
	frac := rep.Percent / 100.0
	if frac < 0 {
		frac = 0
	}
	if frac > 1 {
		frac = 1
	}
	var label string
	switch {
	case rep.Err != nil:
		label = fmt.Sprintf("Failed: %s (%v)", rep.FileName, rep.Err)
	case rep.Verified:
		label = fmt.Sprintf("Verified ✓ %s • 100%% (%.1f MB/s)", rep.FileName, rep.SpeedMBps)
	default:
		label = fmt.Sprintf("%s • %.1f%% (%.1f MB/s)", rep.FileName, rep.Percent, rep.SpeedMBps)
	}
	cs := C.CString(label)
	defer C.free(unsafe.Pointer(cs))
	C.pd_post_progress(C.double(frac), cs)
}

// Quit exits the X11 event loop cleanly.
func (c *Controller) Quit() {
	C.pd_post_quit()
}
