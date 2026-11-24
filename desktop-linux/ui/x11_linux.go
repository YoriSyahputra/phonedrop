//go:build linux && cgo && !nogui

package ui

/*
#cgo pkg-config: x11 cairo-xlib
#cgo LDFLAGS: -lm
#include <X11/Xlib.h>
#include <X11/Xutil.h>
#include <X11/Xatom.h>
#include <cairo/cairo.h>
#include <cairo/cairo-xlib.h>
#include <fcntl.h>
#include <math.h>
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
extern void goOnOpenFolderClicked();
extern void goOnRefreshFilesClicked();
extern void goOnFileActionClicked(int action, char *name);
extern void goOnQuitRequested();

#define SYSTEM_TRAY_REQUEST_DOCK 0
#define FILE_ACT_OPEN   1
#define FILE_ACT_SEND   2
#define FILE_ACT_DELETE 3

#define LINK_NONE        0
#define LINK_USB_CABLE   1
#define LINK_WIRELESS    2

typedef struct {
    int x, y, w, h;
} Rect;

typedef struct {
    char *name;
    char *size_str;
    int origin_type; // 0=none, 1=phone, 2=laptop
} CFileRow;

static struct {
    pthread_mutex_t mu;
    int wakeup_fd[2];
    Display *dpy;
    int screen;
    Window main_win;
    Window tray_win;
    Atom wm_delete;
    int win_w;
    int win_h;
    int visible;
    int tray_docked;
    int quit_flag;

    char *conn_text;
    int conn_link_type;
    int connected;
    char *activity_text;
    char *clip_text;
    char *progress_text;
    double progress_frac;
    int progress_active;
    int progress_verified;
    int phone_mirror_active;
    int linux_stream_active;

    CFileRow *files;
    int file_count;
    double scroll_px;
    double target_scroll_px;

    Rect btn_send;
    Rect btn_phone_mirror;
    Rect btn_linux_stream;
    Rect btn_clip;
    Rect btn_folder;
    Rect btn_refresh;
} g_x11 = {
    .mu = PTHREAD_MUTEX_INITIALIZER,
    .wakeup_fd = {-1, -1},
    .win_w = 520,
    .win_h = 650,
    .visible = 1
};

static void pd_wake(void) {
    if (g_x11.wakeup_fd[1] >= 0) {
        char b = 1;
        ssize_t ignored = write(g_x11.wakeup_fd[1], &b, 1);
        (void)ignored;
    }
}

static void pd_post_conn_badge(const char *txt, int link_type, int connected) {
    pthread_mutex_lock(&g_x11.mu);
    free(g_x11.conn_text);
    g_x11.conn_text = strdup(txt ? txt : "");
    g_x11.conn_link_type = link_type;
    g_x11.connected = connected;
    pthread_mutex_unlock(&g_x11.mu);
    pd_wake();
}

static void pd_post_activity(const char *txt) {
    pthread_mutex_lock(&g_x11.mu);
    free(g_x11.activity_text);
    g_x11.activity_text = strdup(txt ? txt : "");
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

static void pd_post_progress(double frac, const char *txt, int active, int verified) {
    pthread_mutex_lock(&g_x11.mu);
    g_x11.progress_frac = frac;
    g_x11.progress_active = active;
    g_x11.progress_verified = verified;
    free(g_x11.progress_text);
    g_x11.progress_text = strdup(txt ? txt : "");
    pthread_mutex_unlock(&g_x11.mu);
    pd_wake();
}

static void pd_post_mirror_states(int has_pm, int pm, int has_ls, int ls) {
    pthread_mutex_lock(&g_x11.mu);
    if (has_pm) g_x11.phone_mirror_active = pm;
    if (has_ls) g_x11.linux_stream_active = ls;
    pthread_mutex_unlock(&g_x11.mu);
    pd_wake();
}

static void pd_clear_files_locked(void) {
    for (int i = 0; i < g_x11.file_count; i++) {
        free(g_x11.files[i].name);
        free(g_x11.files[i].size_str);
    }
    free(g_x11.files);
    g_x11.files = NULL;
    g_x11.file_count = 0;
}

static void pd_begin_files(int count) {
    pthread_mutex_lock(&g_x11.mu);
    pd_clear_files_locked();
    if (count > 0) {
        g_x11.files = (CFileRow *)calloc((size_t)count, sizeof(CFileRow));
        g_x11.file_count = count;
    }
    if (count == 0) {
        g_x11.scroll_px = 0.0;
        g_x11.target_scroll_px = 0.0;
    }
    pthread_mutex_unlock(&g_x11.mu);
}

static void pd_set_file_item(int idx, const char *name, const char *size_str, int origin_type) {
    pthread_mutex_lock(&g_x11.mu);
    if (idx >= 0 && idx < g_x11.file_count && g_x11.files) {
        g_x11.files[idx].name = strdup(name ? name : "");
        g_x11.files[idx].size_str = strdup(size_str ? size_str : "");
        g_x11.files[idx].origin_type = origin_type;
    }
    pthread_mutex_unlock(&g_x11.mu);
}

static void pd_commit_files(void) {
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
    if (r * 2 > w) r = w / 2.0;
    if (r * 2 > h) r = h / 2.0;
    cairo_new_sub_path(cr);
    cairo_arc(cr, x + w - r, y + r, r, -pi / 2.0, 0);
    cairo_arc(cr, x + w - r, y + h - r, r, 0, pi / 2.0);
    cairo_arc(cr, x + r, y + h - r, r, pi / 2.0, pi);
    cairo_arc(cr, x + r, y + r, r, pi, 3.0 * pi / 2.0);
    cairo_close_path(cr);
}

static void draw_logo_mark(cairo_t *cr, double x, double y, double sz) {
    draw_rounded_rect(cr, x, y, sz, sz, sz * 0.24);
    cairo_set_source_rgb(cr, 0.11, 0.14, 0.12);
    cairo_fill_preserve(cr);
    cairo_set_source_rgb(cr, 0.83, 0.72, 0.54);
    cairo_set_line_width(cr, 1.3);
    cairo_stroke(cr);

    // Inner phone silhouette
    double px = x + sz * 0.31;
    double py = y + sz * 0.18;
    double pw = sz * 0.38;
    double ph = sz * 0.54;
    draw_rounded_rect(cr, px, py, pw, ph, sz * 0.08);
    cairo_set_source_rgb(cr, 0.06, 0.09, 0.07);
    cairo_fill_preserve(cr);
    cairo_set_source_rgb(cr, 0.875, 0.769, 0.608);
    cairo_set_line_width(cr, 1.3);
    cairo_stroke(cr);

    // Sync drop arrow inside phone
    double cx = x + sz * 0.5;
    cairo_set_line_cap(cr, CAIRO_LINE_CAP_ROUND);
    cairo_set_line_join(cr, CAIRO_LINE_JOIN_ROUND);
    cairo_move_to(cr, cx, y + sz * 0.34);
    cairo_line_to(cr, cx, y + sz * 0.55);
    cairo_move_to(cr, cx - sz * 0.10, y + sz * 0.46);
    cairo_line_to(cr, cx, y + sz * 0.56);
    cairo_line_to(cr, cx + sz * 0.10, y + sz * 0.46);
    cairo_stroke(cr);

    // Laptop base line
    cairo_move_to(cr, x + sz * 0.20, y + sz * 0.80);
    cairo_line_to(cr, x + sz * 0.80, y + sz * 0.80);
    cairo_stroke(cr);
}

static void draw_icon_usb(cairo_t *cr, double cx, double cy) {
    cairo_save(cr);
    cairo_set_line_cap(cr, CAIRO_LINE_CAP_ROUND);
    cairo_set_line_width(cr, 1.4);
    cairo_move_to(cr, cx - 5, cy);
    cairo_line_to(cr, cx + 5, cy);
    cairo_move_to(cr, cx - 1, cy);
    cairo_line_to(cr, cx + 2, cy - 3.5);
    cairo_move_to(cr, cx - 2, cy);
    cairo_line_to(cr, cx + 1, cy + 3.5);
    cairo_stroke(cr);
    cairo_arc(cr, cx + 5, cy, 1.4, 0, 6.28318);
    cairo_fill(cr);
    cairo_restore(cr);
}

static void draw_icon_wifi(cairo_t *cr, double cx, double cy) {
    double pi = 3.141592653589793;
    cairo_save(cr);
    cairo_set_line_cap(cr, CAIRO_LINE_CAP_ROUND);
    cairo_set_line_width(cr, 1.4);
    cairo_arc(cr, cx, cy + 3.5, 6.5, -pi * 0.78, -pi * 0.22);
    cairo_stroke(cr);
    cairo_arc(cr, cx, cy + 3.5, 3.8, -pi * 0.75, -pi * 0.25);
    cairo_stroke(cr);
    cairo_arc(cr, cx, cy + 3.5, 1.3, 0, 6.28318);
    cairo_fill(cr);
    cairo_restore(cr);
}

static void draw_icon_check(cairo_t *cr, double cx, double cy) {
    cairo_save(cr);
    cairo_set_source_rgb(cr, 0.369, 0.671, 0.510);
    cairo_set_line_cap(cr, CAIRO_LINE_CAP_ROUND);
    cairo_set_line_join(cr, CAIRO_LINE_JOIN_ROUND);
    cairo_set_line_width(cr, 1.8);
    cairo_move_to(cr, cx - 4.0, cy + 0.5);
    cairo_line_to(cr, cx - 1.0, cy + 3.5);
    cairo_line_to(cr, cx + 4.5, cy - 3.0);
    cairo_stroke(cr);
    cairo_restore(cr);
}

static void draw_action_glyph(cairo_t *cr, int kind, double cx, double cy) {
    cairo_save(cr);
    cairo_set_line_cap(cr, CAIRO_LINE_CAP_ROUND);
    cairo_set_line_join(cr, CAIRO_LINE_JOIN_ROUND);
    cairo_set_line_width(cr, 1.5);
    if (kind == 1) { // upload arrow
        cairo_move_to(cr, cx, cy + 4.5);
        cairo_line_to(cr, cx, cy - 4.5);
        cairo_move_to(cr, cx - 3.5, cy - 1.0);
        cairo_line_to(cr, cx, cy - 4.5);
        cairo_line_to(cr, cx + 3.5, cy - 1.0);
        cairo_move_to(cr, cx - 5.0, cy + 6.0);
        cairo_line_to(cr, cx + 5.0, cy + 6.0);
        cairo_stroke(cr);
    } else if (kind == 2) { // phone
        draw_rounded_rect(cr, cx - 4.0, cy - 6.5, 8.0, 13.0, 2.0);
        cairo_stroke(cr);
        cairo_move_to(cr, cx - 1.5, cy + 4.0);
        cairo_line_to(cr, cx + 1.5, cy + 4.0);
        cairo_stroke(cr);
    } else if (kind == 3) { // laptop display
        draw_rounded_rect(cr, cx - 6.0, cy - 5.0, 12.0, 8.5, 1.5);
        cairo_stroke(cr);
        cairo_move_to(cr, cx - 7.5, cy + 5.5);
        cairo_line_to(cr, cx + 7.5, cy + 5.5);
        cairo_stroke(cr);
    } else if (kind == 4) { // clipboard
        draw_rounded_rect(cr, cx - 4.5, cy - 5.0, 9.0, 11.0, 1.5);
        cairo_stroke(cr);
        cairo_move_to(cr, cx - 2.0, cy - 6.0);
        cairo_line_to(cr, cx + 2.0, cy - 6.0);
        cairo_stroke(cr);
    }
    cairo_restore(cr);
}

// style: 0=espresso secondary, 1=champagne brass primary, 2=active botanical green, 3=coral danger
static void draw_button(cairo_t *cr, Rect r, const char *label, int style, double font_size, int icon_kind) {
    draw_rounded_rect(cr, r.x, r.y, r.w, r.h, 9.0);
    if (style == 1) {
        cairo_set_source_rgb(cr, 0.784, 0.663, 0.494);
    } else if (style == 2) {
        cairo_set_source_rgb(cr, 0.18, 0.40, 0.29);
    } else if (style == 3) {
        cairo_set_source_rgb(cr, 0.24, 0.13, 0.12);
    } else {
        cairo_set_source_rgb(cr, 0.13, 0.145, 0.135);
    }
    cairo_fill_preserve(cr);

    if (style == 1) {
        cairo_set_source_rgb(cr, 0.875, 0.769, 0.608);
    } else if (style == 2) {
        cairo_set_source_rgb(cr, 0.369, 0.671, 0.510);
    } else if (style == 3) {
        cairo_set_source_rgb(cr, 0.831, 0.416, 0.369);
    } else {
        cairo_set_source_rgba(cr, 0.83, 0.72, 0.54, 0.22);
    }
    cairo_set_line_width(cr, 1.0);
    cairo_stroke(cr);

    cairo_select_font_face(cr, "Sans", CAIRO_FONT_SLANT_NORMAL, CAIRO_FONT_WEIGHT_BOLD);
    cairo_set_font_size(cr, font_size);
    cairo_text_extents_t ext;
    cairo_text_extents(cr, label, &ext);
    if (style == 1) {
        cairo_set_source_rgb(cr, 0.043, 0.067, 0.055);
    } else if (style == 3) {
        cairo_set_source_rgb(cr, 0.90, 0.58, 0.54);
    } else if (style == 2) {
        cairo_set_source_rgb(cr, 0.88, 0.97, 0.91);
    } else {
        cairo_set_source_rgb(cr, 0.953, 0.937, 0.902);
    }

    double iconSpace = icon_kind > 0 ? 18.0 : 0.0;
    double totalW = ext.width + iconSpace;
    double startX = r.x + (r.w - totalW) / 2.0;
    if (icon_kind > 0) {
        draw_action_glyph(cr, icon_kind, startX + 6.0, r.y + r.h / 2.0);
    }
    cairo_move_to(cr, startX + iconSpace - ext.x_bearing, r.y + (r.h + ext.height) / 2.0 - 1.0);
    cairo_show_text(cr, label);
}

static void draw_text_ellipsized(cairo_t *cr, double x, double y, double max_w, const char *txt) {
    if (!txt || !*txt || max_w <= 10.0) return;
    cairo_text_extents_t ext;
    cairo_text_extents(cr, txt, &ext);
    if (ext.width <= max_w) {
        cairo_move_to(cr, x, y);
        cairo_show_text(cr, txt);
        return;
    }
    size_t len = strlen(txt);
    char *tmp = (char *)malloc(len + 4);
    if (!tmp) return;
    while (len > 3) {
        len--;
        memcpy(tmp, txt, len);
        memcpy(tmp + len, "...", 4);
        cairo_text_extents(cr, tmp, &ext);
        if (ext.width <= max_w) {
            cairo_move_to(cr, x, y);
            cairo_show_text(cr, tmp);
            free(tmp);
            return;
        }
    }
    free(tmp);
}

static void redraw_main_window(void) {
    if (!g_x11.dpy || !g_x11.main_win || !g_x11.visible) return;

    pthread_mutex_lock(&g_x11.mu);
    int w = g_x11.win_w;
    int h = g_x11.win_h;
    char *conn = strdup(g_x11.conn_text ? g_x11.conn_text : "Siaga :28200");
    int link_type = g_x11.conn_link_type;
    int connected = g_x11.connected;
    char *activity = strdup(g_x11.activity_text ? g_x11.activity_text : "Siap kirim file 64KB + sinkronisasi clipboard");
    char *clip = strdup(g_x11.clip_text ? g_x11.clip_text : "Clipboard: siap sinkronisasi otomatis");
    char *prog = strdup(g_x11.progress_text ? g_x11.progress_text : "");
    double frac = g_x11.progress_frac;
    int prog_active = g_x11.progress_active;
    int prog_ok = g_x11.progress_verified;
    int pm = g_x11.phone_mirror_active;
    int ls = g_x11.linux_stream_active;

    int pad = 16;
    int halfW = (w - pad * 3) / 2;
    if (halfW < 100) halfW = 100;
    g_x11.btn_send = (Rect){pad, 60, halfW, 38};
    g_x11.btn_phone_mirror = (Rect){pad * 2 + halfW, 60, halfW, 38};
    g_x11.btn_linux_stream = (Rect){pad, 106, halfW, 38};
    g_x11.btn_clip = (Rect){pad * 2 + halfW, 106, halfW, 38};

    int filesCardY = 236;
    int filesCardH = h - filesCardY - 14;
    if (filesCardH < 120) filesCardH = 120;
    g_x11.btn_folder = (Rect){w - pad - 156, filesCardY + 12, 66, 26};
    g_x11.btn_refresh = (Rect){w - pad - 82, filesCardY + 12, 70, 26};

    Rect bSend = g_x11.btn_send;
    Rect bPM = g_x11.btn_phone_mirror;
    Rect bLS = g_x11.btn_linux_stream;
    Rect bClip = g_x11.btn_clip;
    Rect bFolder = g_x11.btn_folder;
    Rect bRefresh = g_x11.btn_refresh;

    int listTop = filesCardY + 46;
    int listH = filesCardH - 54;
    int rowH = 44;
    double maxScroll = (double)(g_x11.file_count * rowH - listH);
    if (maxScroll < 0.0) maxScroll = 0.0;
    if (g_x11.target_scroll_px > maxScroll) g_x11.target_scroll_px = maxScroll;
    if (g_x11.target_scroll_px < 0.0) g_x11.target_scroll_px = 0.0;
    if (g_x11.scroll_px > maxScroll) g_x11.scroll_px = maxScroll;
    if (g_x11.scroll_px < 0.0) g_x11.scroll_px = 0.0;

    double scrollPx = g_x11.scroll_px;
    int fcount = g_x11.file_count;
    CFileRow *fcopy = NULL;
    if (fcount > 0 && g_x11.files) {
        fcopy = (CFileRow *)calloc((size_t)fcount, sizeof(CFileRow));
        for (int i = 0; i < fcount; i++) {
            fcopy[i].name = strdup(g_x11.files[i].name ? g_x11.files[i].name : "");
            fcopy[i].size_str = strdup(g_x11.files[i].size_str ? g_x11.files[i].size_str : "");
            fcopy[i].origin_type = g_x11.files[i].origin_type;
        }
    }
    pthread_mutex_unlock(&g_x11.mu);

    Visual *vis = DefaultVisual(g_x11.dpy, g_x11.screen);
    cairo_surface_t *surf = cairo_xlib_surface_create(g_x11.dpy, g_x11.main_win, vis, w, h);
    cairo_t *cr = cairo_create(surf);

    // Offscreen double-buffering eliminates 100% of flicker on resize and smooth scroll.
    cairo_push_group(cr);

    // Deep Botanical background #0b110e + subtle ambient radial glow
    cairo_set_source_rgb(cr, 0.043, 0.067, 0.055);
    cairo_paint(cr);

    cairo_pattern_t *glow = cairo_pattern_create_radial(w * 0.2, 0.0, 20.0, w * 0.2, 0.0, 280.0);
    cairo_pattern_add_color_stop_rgba(glow, 0.0, 0.11, 0.24, 0.18, 0.35);
    cairo_pattern_add_color_stop_rgba(glow, 1.0, 0.043, 0.067, 0.055, 0.0);
    cairo_set_source(cr, glow);
    cairo_paint(cr);
    cairo_pattern_destroy(glow);

    // Top Header Bar with official vector mark
    draw_logo_mark(cr, pad, 14.0, 30.0);
    cairo_select_font_face(cr, "Sans", CAIRO_FONT_SLANT_NORMAL, CAIRO_FONT_WEIGHT_BOLD);
    cairo_set_font_size(cr, 16.5);
    cairo_set_source_rgb(cr, 0.953, 0.937, 0.902);
    cairo_move_to(cr, pad + 38, 35);
    cairo_show_text(cr, "PhoneDrop");

    // Smart Connection Pill right (Kabel USB / Wireless / Siaga)
    cairo_set_font_size(cr, 11.5);
    cairo_text_extents_t sext;
    cairo_text_extents(cr, conn, &sext);
    double iconW = (connected && link_type != LINK_NONE) ? 16.0 : 0.0;
    double pillW = sext.width + iconW + 24.0;
    if (pillW > w - 180) pillW = w - 180;
    double pillX = w - pad - pillW;
    draw_rounded_rect(cr, pillX, 16, pillW, 26, 13.0);
    if (connected) {
        cairo_set_source_rgba(cr, 0.16, 0.34, 0.25, 0.85);
    } else {
        cairo_set_source_rgba(cr, 0.16, 0.14, 0.12, 0.85);
    }
    cairo_fill_preserve(cr);
    if (connected) {
        cairo_set_source_rgb(cr, 0.369, 0.671, 0.510);
    } else {
        cairo_set_source_rgba(cr, 0.83, 0.72, 0.54, 0.35);
    }
    cairo_set_line_width(cr, 1.0);
    cairo_stroke(cr);

    if (connected) {
        cairo_set_source_rgb(cr, 0.55, 0.86, 0.69);
        if (link_type == LINK_USB_CABLE) {
            draw_icon_usb(cr, pillX + 14.0, 29.0);
        } else if (link_type == LINK_WIRELESS) {
            draw_icon_wifi(cr, pillX + 14.0, 29.0);
        }
    } else {
        cairo_set_source_rgb(cr, 0.87, 0.77, 0.61);
    }
    draw_text_ellipsized(cr, pillX + 11.0 + iconW, 33.0, pillW - 18.0 - iconW, conn);

    // 2x2 Quick Action Grid (zero emojis, custom vector icons)
    draw_button(cr, bSend, "Kirim File ke HP", 1, 12.0, 1);
    draw_button(cr, bPM, pm ? "Tutup Layar HP" : "Layar HP (scrcpy)", pm ? 2 : 0, 12.0, 2);
    draw_button(cr, bLS, ls ? "Stop Layar Laptop" : "Layar Laptop -> HP", ls ? 2 : 0, 12.0, 3);
    draw_button(cr, bClip, "Kirim Clipboard", 0, 12.0, 4);

    // Compact Activity / Transfer & Clipboard Glass Card (154..226)
    draw_rounded_rect(cr, pad, 154, w - pad * 2, 72, 12.0);
    cairo_set_source_rgba(cr, 0.095, 0.130, 0.110, 0.92);
    cairo_fill_preserve(cr);
    cairo_set_source_rgba(cr, 0.83, 0.72, 0.54, 0.22);
    cairo_set_line_width(cr, 1.0);
    cairo_stroke(cr);

    int innerW = w - pad * 4;
    if (prog_active) {
        draw_rounded_rect(cr, pad * 2, 168, innerW, 8, 4.0);
        cairo_set_source_rgb(cr, 0.05, 0.07, 0.06);
        cairo_fill(cr);
        if (frac > 0.0) {
            double fw = innerW * (frac > 1.0 ? 1.0 : frac);
            if (fw < 8.0) fw = 8.0;
            draw_rounded_rect(cr, pad * 2, 168, fw, 8, 4.0);
            if (prog_ok) {
                cairo_set_source_rgb(cr, 0.369, 0.671, 0.510);
            } else {
                cairo_set_source_rgb(cr, 0.784, 0.663, 0.494);
            }
            cairo_fill(cr);
        }
        double textOffsetX = 0.0;
        if (prog_ok) {
            draw_icon_check(cr, pad * 2 + 6.0, 189.0);
            textOffsetX = 16.0;
        }
        cairo_select_font_face(cr, "Sans", CAIRO_FONT_SLANT_NORMAL, CAIRO_FONT_WEIGHT_BOLD);
        cairo_set_font_size(cr, 11.5);
        cairo_set_source_rgb(cr, 0.953, 0.937, 0.902);
        draw_text_ellipsized(cr, pad * 2 + textOffsetX, 193, innerW - textOffsetX, prog);
    } else {
        cairo_select_font_face(cr, "Sans", CAIRO_FONT_SLANT_NORMAL, CAIRO_FONT_WEIGHT_BOLD);
        cairo_set_font_size(cr, 11.5);
        cairo_set_source_rgb(cr, 0.875, 0.769, 0.608);
        draw_text_ellipsized(cr, pad * 2, 181, innerW, activity);
    }

    cairo_select_font_face(cr, "Sans", CAIRO_FONT_SLANT_NORMAL, CAIRO_FONT_WEIGHT_NORMAL);
    cairo_set_font_size(cr, 11.0);
    cairo_set_source_rgb(cr, 0.639, 0.612, 0.565);
    draw_text_ellipsized(cr, pad * 2, 212, innerW, clip);

    // Ruang File Bersama Card
    draw_rounded_rect(cr, pad, filesCardY, w - pad * 2, filesCardH, 12.0);
    cairo_set_source_rgba(cr, 0.086, 0.118, 0.098, 0.94);
    cairo_fill_preserve(cr);
    cairo_set_source_rgba(cr, 0.83, 0.72, 0.54, 0.22);
    cairo_stroke(cr);

    cairo_select_font_face(cr, "Sans", CAIRO_FONT_SLANT_NORMAL, CAIRO_FONT_WEIGHT_BOLD);
    cairo_set_font_size(cr, 13.0);
    cairo_set_source_rgb(cr, 0.953, 0.937, 0.902);
    char hdrBuf[64];
    snprintf(hdrBuf, sizeof(hdrBuf), "Ruang File Bersama (%d)", fcount);
    cairo_move_to(cr, pad * 2, filesCardY + 30);
    cairo_show_text(cr, hdrBuf);

    draw_button(cr, bFolder, "Folder", 0, 10.5, 0);
    draw_button(cr, bRefresh, "Segarkan", 0, 10.5, 0);

    // Scissor-clipped smooth scrolling list
    int rowX = pad + 10;
    int rowW = w - (pad + 10) * 2 - (maxScroll > 0.0 ? 8 : 0);

    cairo_save(cr);
    cairo_rectangle(cr, rowX - 2, listTop, rowW + 12, listH);
    cairo_clip(cr);

    if (fcount == 0) {
        cairo_select_font_face(cr, "Sans", CAIRO_FONT_SLANT_NORMAL, CAIRO_FONT_WEIGHT_NORMAL);
        cairo_set_font_size(cr, 12.0);
        cairo_set_source_rgb(cr, 0.639, 0.612, 0.565);
        cairo_move_to(cr, pad * 2, listTop + 32);
        cairo_show_text(cr, "Belum ada file di ~/Downloads/PhoneDrop");
    } else {
        for (int i = 0; i < fcount; i++) {
            double ry = (double)listTop + (double)(i * rowH) - scrollPx;
            if (ry + rowH < listTop || ry > listTop + listH) {
                continue;
            }
            draw_rounded_rect(cr, rowX, ry, rowW, rowH - 6, 8.0);
            cairo_set_source_rgb(cr, 0.055, 0.078, 0.067);
            cairo_fill(cr);

            int btnRight = rowX + rowW - 6;
            Rect rDel = {btnRight - 50, (int)llround(ry + 6), 50, 26};
            Rect rSend = {rDel.x - 62, (int)llround(ry + 6), 56, 26};
            Rect rOpen = {rSend.x - 52, (int)llround(ry + 6), 46, 26};

            draw_button(cr, rOpen, "Buka", 0, 10.5, 0);
            draw_button(cr, rSend, "Kirim", 1, 10.5, 0);
            draw_button(cr, rDel, "Hapus", 3, 10.5, 0);

            double textMaxW = (double)(rOpen.x - (rowX + 12) - 8);
            cairo_select_font_face(cr, "Sans", CAIRO_FONT_SLANT_NORMAL, CAIRO_FONT_WEIGHT_BOLD);
            cairo_set_font_size(cr, 11.5);
            cairo_set_source_rgb(cr, 0.953, 0.937, 0.902);
            draw_text_ellipsized(cr, rowX + 10, ry + 16.0, textMaxW, fcopy[i].name);

            // Size + Origin badge
            cairo_select_font_face(cr, "Sans", CAIRO_FONT_SLANT_NORMAL, CAIRO_FONT_WEIGHT_NORMAL);
            cairo_set_font_size(cr, 10.0);
            cairo_set_source_rgb(cr, 0.639, 0.612, 0.565);
            cairo_move_to(cr, rowX + 10, ry + 31.0);
            cairo_show_text(cr, fcopy[i].size_str);

            if (fcopy[i].origin_type == 1 || fcopy[i].origin_type == 2) {
                cairo_text_extents_t szExt;
                cairo_text_extents(cr, fcopy[i].size_str, &szExt);
                double badgeX = rowX + 18.0 + szExt.width;
                const char *badgeLbl = (fcopy[i].origin_type == 1) ? "Dari HP" : "Dari Laptop";
                double bw = (fcopy[i].origin_type == 1) ? 48.0 : 66.0;
                draw_rounded_rect(cr, badgeX, ry + 21.0, bw, 13.0, 6.5);
                if (fcopy[i].origin_type == 1) {
                    cairo_set_source_rgba(cr, 0.369, 0.671, 0.510, 0.16);
                } else {
                    cairo_set_source_rgba(cr, 0.784, 0.663, 0.494, 0.16);
                }
                cairo_fill(cr);
                cairo_set_font_size(cr, 9.0);
                if (fcopy[i].origin_type == 1) {
                    cairo_set_source_rgb(cr, 0.50, 0.83, 0.66);
                } else {
                    cairo_set_source_rgb(cr, 0.875, 0.769, 0.608);
                }
                cairo_move_to(cr, badgeX + 6.0, ry + 30.5);
                cairo_show_text(cr, badgeLbl);
            }
        }
    }

    // Smooth scrollbar thumb
    if (maxScroll > 0.0) {
        double totalContentH = (double)(fcount * rowH);
        double thumbH = ((double)listH / totalContentH) * (double)listH;
        if (thumbH < 24.0) thumbH = 24.0;
        double thumbY = (double)listTop + (scrollPx / maxScroll) * ((double)listH - thumbH);
        draw_rounded_rect(cr, rowX + rowW + 2, thumbY, 4.0, thumbH, 2.0);
        cairo_set_source_rgba(cr, 0.83, 0.72, 0.54, 0.45);
        cairo_fill(cr);
    }

    cairo_restore(cr);

    // Blit atomic backbuffer frame to X11 window
    cairo_pop_group_to_source(cr);
    cairo_paint(cr);

    cairo_destroy(cr);
    cairo_surface_destroy(surf);
    XFlush(g_x11.dpy);

    if (fcopy) {
        for (int i = 0; i < fcount; i++) {
            free(fcopy[i].name);
            free(fcopy[i].size_str);
        }
        free(fcopy);
    }
    free(conn);
    free(activity);
    free(clip);
    free(prog);
}

static void redraw_tray_icon(void) {
    if (!g_x11.dpy || !g_x11.tray_win) return;
    Visual *vis = DefaultVisual(g_x11.dpy, g_x11.screen);
    cairo_surface_t *surf = cairo_xlib_surface_create(g_x11.dpy, g_x11.tray_win, vis, 24, 24);
    cairo_t *cr = cairo_create(surf);
    cairo_set_source_rgb(cr, 0.043, 0.067, 0.055);
    cairo_paint(cr);
    draw_logo_mark(cr, 1.0, 1.0, 22.0);
    cairo_destroy(cr);
    cairo_surface_destroy(surf);
    XFlush(g_x11.dpy);
}

static void dock_tray_icon(void) {
    char atom_name[64];
    snprintf(atom_name, sizeof(atom_name), "_NET_SYSTEM_TRAY_S%d", g_x11.screen);
    Atom tray_atom = XInternAtom(g_x11.dpy, atom_name, False);
    Window tray_owner = XGetSelectionOwner(g_x11.dpy, tray_atom);
    if (tray_owner == None) return;

    g_x11.tray_win = XCreateSimpleWindow(
        g_x11.dpy,
        RootWindow(g_x11.dpy, g_x11.screen),
        0, 0, 24, 24, 0, 0, 0x0b110e
    );
    XSelectInput(g_x11.dpy, g_x11.tray_win, ExposureMask | ButtonPressMask);

    Atom xembed_info = XInternAtom(g_x11.dpy, "_XEMBED_INFO", False);
    unsigned long info[2] = {0, 1};
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

static void handle_file_list_click(int cx, int cy) {
    int pad = 16;
    int filesCardY = 236;
    int listTop = filesCardY + 46;
    int rowH = 44;

    pthread_mutex_lock(&g_x11.mu);
    int w = g_x11.win_w;
    int h = g_x11.win_h;
    int filesCardH = h - filesCardY - 14;
    if (filesCardH < 120) filesCardH = 120;
    int listH = filesCardH - 54;
    if (cy < listTop || cy > listTop + listH) {
        pthread_mutex_unlock(&g_x11.mu);
        return;
    }
    double contentY = (double)(cy - listTop) + g_x11.scroll_px;
    int idx = (int)floor(contentY / (double)rowH);
    double maxScroll = (double)(g_x11.file_count * rowH - listH);
    if (maxScroll < 0.0) maxScroll = 0.0;
    double ry = (double)listTop + (double)(idx * rowH) - g_x11.scroll_px;
    if (idx < 0 || idx >= g_x11.file_count || !g_x11.files || cy > ry + rowH - 6) {
        pthread_mutex_unlock(&g_x11.mu);
        return;
    }
    char *fname = strdup(g_x11.files[idx].name ? g_x11.files[idx].name : "");
    pthread_mutex_unlock(&g_x11.mu);

    int rowX = pad + 10;
    int rowW = w - (pad + 10) * 2 - (maxScroll > 0.0 ? 8 : 0);
    int btnRight = rowX + rowW - 6;
    Rect rDel = {btnRight - 50, (int)llround(ry + 6), 50, 26};
    Rect rSend = {rDel.x - 62, (int)llround(ry + 6), 56, 26};
    Rect rOpen = {rSend.x - 52, (int)llround(ry + 6), 46, 26};

    if (point_in_rect(cx, cy, rOpen)) {
        goOnFileActionClicked(FILE_ACT_OPEN, fname);
    } else if (point_in_rect(cx, cy, rSend)) {
        goOnFileActionClicked(FILE_ACT_SEND, fname);
    } else if (point_in_rect(cx, cy, rDel)) {
        goOnFileActionClicked(FILE_ACT_DELETE, fname);
    } else {
        free(fname);
    }
}

static void pd_init_and_run(void) {
    XInitThreads();
    Display *dpy = XOpenDisplay(NULL);
    if (!dpy) return;

    if (pipe(g_x11.wakeup_fd) == 0) {
        fcntl(g_x11.wakeup_fd[0], F_SETFL, O_NONBLOCK);
        fcntl(g_x11.wakeup_fd[1], F_SETFL, O_NONBLOCK);
    }

    g_x11.dpy = dpy;
    g_x11.screen = DefaultScreen(dpy);
    Window root = RootWindow(dpy, g_x11.screen);

    g_x11.main_win = XCreateSimpleWindow(dpy, root, 80, 80, g_x11.win_w, g_x11.win_h, 0, 0, 0x0b110e);
    XStoreName(dpy, g_x11.main_win, "PhoneDrop");
    XSelectInput(dpy, g_x11.main_win, ExposureMask | ButtonPressMask | StructureNotifyMask);

    g_x11.wm_delete = XInternAtom(dpy, "WM_DELETE_WINDOW", False);
    XSetWMProtocols(dpy, g_x11.main_win, &g_x11.wm_delete, 1);

    XMapWindow(dpy, g_x11.main_win);
    dock_tray_icon();
    XFlush(dpy);

    redraw_main_window();
    redraw_tray_icon();

    int xfd = ConnectionNumber(dpy);
    for (;;) {
        pthread_mutex_lock(&g_x11.mu);
        int should_quit = g_x11.quit_flag;
        int animating = fabs(g_x11.target_scroll_px - g_x11.scroll_px) > 0.5;
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
                    pthread_mutex_lock(&g_x11.mu);
                    g_x11.win_w = nw;
                    g_x11.win_h = nh;
                    pthread_mutex_unlock(&g_x11.mu);
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
                } else if (ev.xbutton.window == g_x11.main_win) {
                    if (ev.xbutton.button == Button4 || ev.xbutton.button == Button5) {
                        pthread_mutex_lock(&g_x11.mu);
                        int filesCardH = g_x11.win_h - 236 - 14;
                        if (filesCardH < 120) filesCardH = 120;
                        int listH = filesCardH - 54;
                        double maxS = (double)(g_x11.file_count * 44 - listH);
                        if (maxS < 0.0) maxS = 0.0;
                        double step = (ev.xbutton.button == Button4) ? -58.0 : 58.0;
                        g_x11.target_scroll_px += step;
                        if (g_x11.target_scroll_px < 0.0) g_x11.target_scroll_px = 0.0;
                        if (g_x11.target_scroll_px > maxS) g_x11.target_scroll_px = maxS;
                        animating = 1;
                        pthread_mutex_unlock(&g_x11.mu);
                        continue;
                    }
                    if (ev.xbutton.button != Button1) continue;

                    int cx = ev.xbutton.x;
                    int cy = ev.xbutton.y;
                    pthread_mutex_lock(&g_x11.mu);
                    Rect bSend = g_x11.btn_send;
                    Rect bPM = g_x11.btn_phone_mirror;
                    Rect bLS = g_x11.btn_linux_stream;
                    Rect bClip = g_x11.btn_clip;
                    Rect bFolder = g_x11.btn_folder;
                    Rect bRefresh = g_x11.btn_refresh;
                    pthread_mutex_unlock(&g_x11.mu);

                    if (point_in_rect(cx, cy, bSend)) {
                        goOnSendFileClicked();
                    } else if (point_in_rect(cx, cy, bClip)) {
                        goOnSyncClipClicked();
                    } else if (point_in_rect(cx, cy, bFolder)) {
                        goOnOpenFolderClicked();
                    } else if (point_in_rect(cx, cy, bRefresh)) {
                        goOnRefreshFilesClicked();
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
                    } else {
                        handle_file_list_click(cx, cy);
                    }
                }
            }
        }

        if (animating) {
            pthread_mutex_lock(&g_x11.mu);
            double diff = g_x11.target_scroll_px - g_x11.scroll_px;
            if (fabs(diff) <= 0.6) {
                g_x11.scroll_px = g_x11.target_scroll_px;
            } else {
                g_x11.scroll_px += diff * 0.34;
            }
            pthread_mutex_unlock(&g_x11.mu);
            redraw_main_window();
        }

        struct pollfd fds[2];
        fds[0].fd = xfd;
        fds[0].events = POLLIN;
        fds[1].fd = g_x11.wakeup_fd[0];
        fds[1].events = POLLIN;
        int timeoutMs = animating ? 16 : -1;
        if (poll(fds, g_x11.wakeup_fd[0] >= 0 ? 2 : 1, timeoutMs) > 0) {
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

	"phonedrop/v2/desktop/core/transfer"
)

// FileRow represents one entry displayed in Ruang File Bersama.
type FileRow struct {
	Name   string
	Size   int64
	ModMs  int64
	Origin string // "phone" | "laptop" | ""
}

// Callbacks connects native X11 UI events back into Go application logic.
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
		out, err := exec.Command("zenity", "--file-selection", "--multiple", "--separator=\n", "--title=Kirim File ke HP (PhoneDrop)").Output()
		if err != nil {
			return
		}
		for _, line := range strings.Split(strings.TrimSpace(string(out)), "\n") {
			p := strings.TrimSpace(line)
			if p != "" {
				fn(p)
			}
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

//export goOnOpenFolderClicked
func goOnOpenFolderClicked() {
	cbMu.RLock()
	fn := activeCBs.OnOpenFolder
	cbMu.RUnlock()
	if fn != nil {
		go fn()
	}
}

//export goOnRefreshFilesClicked
func goOnRefreshFilesClicked() {
	cbMu.RLock()
	fn := activeCBs.OnRefreshFiles
	cbMu.RUnlock()
	if fn != nil {
		go fn()
	}
}

//export goOnFileActionClicked
func goOnFileActionClicked(action C.int, cname *C.char) {
	name := C.GoString(cname)
	C.free(unsafe.Pointer(cname))
	if name == "" {
		return
	}
	cbMu.RLock()
	cbs := activeCBs
	cbMu.RUnlock()
	switch int(action) {
	case 1:
		if cbs.OnOpenFile != nil {
			go cbs.OnOpenFile(name)
		}
	case 2:
		if cbs.OnSendSharedFile != nil {
			go cbs.OnSendSharedFile(name)
		}
	case 3:
		if cbs.OnDeleteSharedFile != nil {
			go cbs.OnDeleteSharedFile(name)
		}
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

// SetConnectionBadge updates the top-right link pill independently of activity messages.
func (c *Controller) SetConnectionBadge(text string, linkType string, connected bool) {
	cs := C.CString(text)
	defer C.free(unsafe.Pointer(cs))
	lt := C.LINK_NONE
	switch linkType {
	case "usb_cable":
		lt = C.LINK_USB_CABLE
	case "wireless_adb", "wifi_lan":
		lt = C.LINK_WIRELESS
	}
	connFlag := 0
	if connected {
		connFlag = 1
	}
	C.pd_post_conn_badge(cs, C.int(lt), C.int(connFlag))
}

// SetActivityStatus updates the activity line inside the status card without touching the connection badge.
func (c *Controller) SetActivityStatus(text string) {
	cs := C.CString(text)
	defer C.free(unsafe.Pointer(cs))
	C.pd_post_activity(cs)
}

// SetClipboardPreview updates the clipboard preview line cleanly.
func (c *Controller) SetClipboardPreview(text string) {
	text = strings.ReplaceAll(text, "\n", " ")
	if len(text) > 72 {
		text = text[:72] + "..."
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
	verifiedFlag := 0
	var label string
	switch {
	case rep.Err != nil:
		label = fmt.Sprintf("Gagal: %s (%v)", rep.FileName, rep.Err)
	case rep.Verified:
		verifiedFlag = 1
		label = fmt.Sprintf("Selesai: %s • 100%% (%.1f MB/s • SHA-256)", rep.FileName, rep.SpeedMBps)
	default:
		dir := "Menerima"
		if rep.Direction == transfer.DirectionSend {
			dir = "Mengirim"
		}
		label = fmt.Sprintf("%s %s • %.1f%% (%.1f MB/s)", dir, rep.FileName, rep.Percent, rep.SpeedMBps)
	}
	cs := C.CString(label)
	defer C.free(unsafe.Pointer(cs))
	C.pd_post_progress(C.double(frac), cs, 1, C.int(verifiedFlag))
}

// ClearProgress hides the completed progress bar after the brief completion delay.
func (c *Controller) ClearProgress() {
	cs := C.CString("")
	defer C.free(unsafe.Pointer(cs))
	C.pd_post_progress(0, cs, 0, 0)
}

// SetPhoneMirrorActive syncs the Layar HP (scrcpy) toggle appearance.
func (c *Controller) SetPhoneMirrorActive(active bool) {
	v := 0
	if active {
		v = 1
	}
	C.pd_post_mirror_states(1, C.int(v), 0, 0)
}

// SetLinuxStreamActive syncs the Layar Laptop -> HP toggle appearance.
func (c *Controller) SetLinuxStreamActive(active bool) {
	v := 0
	if active {
		v = 1
	}
	C.pd_post_mirror_states(0, 0, 1, C.int(v))
}

// SetFileList replaces the visible file list in Ruang File Bersama.
func (c *Controller) SetFileList(files []FileRow) {
	C.pd_begin_files(C.int(len(files)))
	for i, f := range files {
		cn := C.CString(f.Name)
		cs := C.CString(formatSize(f.Size))
		origin := 0
		if f.Origin == "phone" {
			origin = 1
		} else if f.Origin == "laptop" {
			origin = 2
		}
		C.pd_set_file_item(C.int(i), cn, cs, C.int(origin))
		C.free(unsafe.Pointer(cn))
		C.free(unsafe.Pointer(cs))
	}
	C.pd_commit_files()
}

func formatSize(b int64) string {
	if b < 1024 {
		return fmt.Sprintf("%d B", b)
	}
	kb := float64(b) / 1024.0
	if kb < 1024.0 {
		return fmt.Sprintf("%.1f KB", kb)
	}
	mb := kb / 1024.0
	if mb < 1024.0 {
		return fmt.Sprintf("%.1f MB", mb)
	}
	return fmt.Sprintf("%.2f GB", mb/1024.0)
}

// Quit exits the X11 event loop cleanly.
func (c *Controller) Quit() {
	C.pd_post_quit()
}
