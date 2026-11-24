#!/usr/bin/env bash
# ==============================================================================
# PhoneDrop — Ubuntu Desktop App Launcher
# ==============================================================================

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
BIN="$PROJECT_DIR/phonedrop"
PORT="8080"
ICON="$PROJECT_DIR/web/icon-512.png"

# Hentikan PhoneDrop jika parameter --stop diberikan
if [ "$1" = "--stop" ]; then
    if pgrep -f "$BIN" >/dev/null; then
        pkill -f "$BIN"
        notify-send -i "$ICON" "PhoneDrop" "Layanan PhoneDrop berhasil dihentikan." 2>/dev/null || true
    else
        notify-send -i "$ICON" "PhoneDrop" "Layanan PhoneDrop belum berjalan." 2>/dev/null || true
    fi
    exit 0
fi

# Cek & build binary jika belum tersedia
if [ ! -f "$BIN" ]; then
    notify-send -i "$ICON" "PhoneDrop" "Menyiapkan binary PhoneDrop..." 2>/dev/null || true
    (cd "$PROJECT_DIR" && go build -ldflags="-s -w" -o phonedrop main.go)
fi

# Jalankan daemon server jika belum aktif
if ! pgrep -f "$BIN" >/dev/null; then
    nohup "$BIN" -port "$PORT" </dev/null >/tmp/phonedrop.log 2>&1 &
    disown
    sleep 0.8
fi

# Deteksi IP lokal Wi-Fi / LAN
LAN_IP=$(ip -4 route get 1.1.1.1 2>/dev/null | awk '{print $7; exit}')
if [ -z "$LAN_IP" ]; then
    LAN_IP=$(hostname -I 2>/dev/null | awk '{print $1}')
fi
if [ -z "$LAN_IP" ]; then
    LAN_IP="localhost"
fi

LOCAL_URL="http://localhost:$PORT"
HP_URL="http://$LAN_IP:$PORT"

# Kirim notifikasi desktop Ubuntu
notify-send -i "$ICON" "PhoneDrop Aktif" "Akses dari HP: $HP_URL\nFolder Tujuan: ~/Downloads/PhoneDrop" 2>/dev/null || true

# Luncurkan tampilan UI sebagai jendela aplikasi mandiri (PWA Desktop Window)
if command -v chromium >/dev/null 2>&1; then
    chromium --app="$LOCAL_URL" >/dev/null 2>&1 &
elif command -v google-chrome >/dev/null 2>&1; then
    google-chrome --app="$LOCAL_URL" >/dev/null 2>&1 &
elif command -v brave-browser >/dev/null 2>&1; then
    brave-browser --app="$LOCAL_URL" >/dev/null 2>&1 &
else
    xdg-open "$LOCAL_URL" >/dev/null 2>&1 &
fi
