# 📱 PhoneDrop — Project Notes & Development Blueprint

> **PhoneDrop** adalah utilitas sinkronisasi dan kontrol lokal (LAN) maupun jarak jauh (WAN) ultra-ringan antara Laptop Ubuntu dan HP Android, dirancang khusus untuk efisiensi tinggi pada perangkat dengan spesifikasi hemat daya (RAM 4 GB / Intel Celeron).

---

## 🛠️ Spesifikasi Lingkungan Pengembangan
- **Proyek Root:** `~/projects/phonedrop`
- **Go Module:** `phonedrop` (Semua import internal menggunakan prefix `phonedrop/internal/...`)
- **Host OS:** Ubuntu 26.04 LTS (Display Server: Wayland `wl-clipboard` / X11 `xclip`, Input: `xdotool` / `ydotool`)
- **Spesifikasi Laptop:** Intel Celeron, RAM 4 GB (Target footprint RAM: < 50 MB)
- **Klien:** HP Android (Browser Chromium / Brave / PWA & Native Client)
- **Editor:** LazyVim di Terminal

---

## 📌 Status Fitur & Checklist Roadmap

### Tahap 1: Versi Web Lokal (Mesin Inti Go & Jaringan Wi-Fi)
- [x] **Sinkronisasi Clipboard Dua Arah (Laptop ⇄ HP)**
  - Menggunakan WebSocket real-time.
  - Memiliki mekanisme anti-loop injection dan *smart touch trigger* untuk browser mobile.
- [x] **Transfer File Dua Arah (0 Kuota / LAN Lokal)**
  - Penyimpanan streaming langsung ke disk di `~/Downloads/PhoneDrop`.
  - Dukungan multi-file dan ukuran besar (hemat RAM).
- [x] **QR Code Instant Download**
  - Pembuatan QR Code otomatis langsung dari backend Go (`go-qrcode`).
  - Scan via kamera HP langsung mengunduh file tanpa membuka antarmuka utama.
- [x] **Manajemen File Ruang Bersama**
  - Menampilkan daftar file di `~/Downloads/PhoneDrop`.
  - Tombol aksi: Simpan (Unduh), QR Code switcher, dan Hapus (`✕`).
- [x] **Remote Touchpad Nirkabel**
  - Menggerakkan kursor laptop, klik kiri (tap), klik kanan, dan scroll halaman.
- [x] **Remote Keyboard Nirkabel**
  - Mengetik teks langsung ke laptop beserta tombol navigasi (`Enter`, `Backspace`).
- [ ] ~~*Gyroscope / Air Mouse*~~ *(Dibatalkan & dihapus: Tidak praktis dan dibatasi izin browser HTTP).*

---

### Tahap 2: Menjadi Aplikasi Mandiri (Ubuntu Desktop & Android APK)
- [x] **PWA & Desktop Launcher (`.desktop`)**
  - Konfigurasi Web App Manifest (`site.webmanifest`), Service Worker (`sw.js`), & ikon adaptif (192x192, 512x512, SVG) agar bisa di-install di layar utama HP tanpa URL bar (PWA Standalone).
  - File `phonedrop.desktop` dan script launcher (`phonedrop-launcher.sh`) terdaftar di Ubuntu Application Drawer (`~/.local/share/applications/`) dengan ikon resmi, notifikasi desktop `notify-send`, dan mode jendela mandiri (Chromium app mode).
- [x] **HTTPS Lokal (Secure Context untuk PWA di HP)**
  - Paket `internal/tlscert`: CA lokal (ECDSA P-256, 10 tahun) dibuat otomatis tanpa mkcert/openssl, disimpan di `~/.config/phonedrop/tls/`.
  - Sertifikat server otomatis diperbarui jika IP LAN berubah / mendekati kedaluwarsa (CA tetap → HP tidak perlu install ulang).
  - Server ganda: HTTP `:8080` (kompatibilitas, QR download, launcher) + HTTPS `:8443` (PWA, `navigator.clipboard`, sensor). Flag: `-tls-port`, `-no-tls`.
  - CA diunduh di HP via `http://<IP>:8080/phonedrop-ca.crt` lalu di-install sekali sebagai *CA certificate*.
- [x] **Share Target (Menu "Bagikan" Android → Laptop)**
  - `share_target` di `site.webmanifest` → PhoneDrop muncul di menu *Bagikan* semua aplikasi Android (butuh PWA terinstall via HTTPS).
  - Endpoint `POST /share-target` (`files.HandleShareTarget`): file di-stream langsung ke `~/Downloads/PhoneDrop`, teks/URL langsung masuk clipboard laptop (deduplikasi URL), notifikasi `notify-send`, lalu redirect 303 ke UI dengan toast status.
  - Service Worker `v2`: request non-GET & `/share-target` tidak pernah disentuh SW (stream file besar tetap hemat RAM).
  - ⚠️ **Wajib install PWA via Chrome Android.** Brave hanya membuat shortcut (ikon berlencana 🦁, bukan WebAPK) sehingga Share Target tidak terdaftar. Brave tetap bisa dipakai untuk browsing biasa.
- [x] **Smart Clipboard Sync via HTTPS (Laptop ⇄ HP)**
  - Auto-copy instan saat app di HP dibuka kembali (`visibilitychange` / `focus` handler) tanpa perlu mengetuk layar manual lagi.
  - Tombol 1-klik **"📋 Tempel & Kirim dari Clipboard HP"** memanfaatkan Async Clipboard API (`navigator.clipboard.readText()`).
  - Opsi toggle **"Kirim clipboard HP otomatis saat app dibuka"** (tersimpan di `localStorage` HP).
  - Anti-loop feedback protection: mendeteksi teks terakhir yang disinkronkan agar tidak terjadi pengiriman berulang bolak-balik.
  - Progressive enhancement: opsi HTTPS otomatis muncul hanya saat berada di Secure Context (`window.isSecureContext`).
- [x] **Multi-File ZIP Generator**
  - Endpoint `GET /api/zip` (`files.HandleZip`): Kompresi `.zip` *on-the-fly* secara streaming langsung ke HTTP socket via `archive/zip` tanpa buffer file utuh di memori (RAM tetap stabil ~15 MB meski mengompres folder > 1 GB).
  - Endpoint `GET /api/qr-zip` (`files.HandleZipQR`): Membuat QR Code instan beresolusi 256px untuk scan unduh ZIP satu ketukan di kamera HP.
  - Dukungan filter query: `/api/zip` (semua file) atau `/api/zip?files=a.jpg,b.pdf` (file pilihan).
  - Integrasi UI: Tombol `📦 Unduh (.zip)` & `📱 QR Semua` di header Ruang File Bersama (otomatis muncul jika ada >= 2 file).
  - Modal Multi-Upload: Saat mengirim banyak file dari laptop, pop-up QR langsung menyediakan tab default `📦 Semua File (X .zip)` agar pengguna HP cukup scan **1x QR Code** untuk mengunduh semua file sekaligus.
- [x] **Direct Send (Zero-Click File Drop)**
  - **Arsitektur 3-Jalur Otomatis (`internal/directsend/manager.go`):**
    1. **Native Android Background Service APK (`PhoneDrop.apk`, ~37 KB):** Dikompilasi langsung via `android/build-apk.sh` (`aapt2` + `javac` + `d8` + `zipalign` + `apksigner` tanpa Gradle, hemat RAM < 60 MB). Berjalan sebagai *Foreground Service* (`DirectSendService.java`), mendengarkan aliran event `/api/direct-send/events`, lalu men-stream file dari laptop langsung ke `Downloads/PhoneDrop` via Android Scoped Storage (`MediaStore.Downloads`) **tanpa prompt izin penyimpanan & walau layar HP terkunci/mati**. Juga mendukung *Share to PhoneDrop* (`ACTION_SEND` / `ACTION_SEND_MULTIPLE`) langsung dari APK.
    2. **ADB Direct Push:** Jika HP terhubung via ADB (USB/Wireless), otomatis mendorong file langsung ke `/sdcard/Download/PhoneDrop` + trigger MediaScanner.
    3. **PWA Browser Auto-Download & Antrean Siaga:** Jika hanya membuka PWA di browser HP, sinyal WebSocket `direct_send` memicu unduhan otomatis tanpa klik di HP.
  - **Integrasi UI:** Indikator status live APK (`Direct Send: APK HP Aktif`), checkbox `⚡ Direct Send`, tombol **`Kirim HP`** di setiap baris file, serta tombol **`🤖 Pasang APK Service`** (QR Code & unduh langsung `/phonedrop.apk`).
- [ ] **Screen Mirroring (HP ke Laptop)**
  - Menampilkan layar HP di monitor laptop Ubuntu via Wi-Fi lokal.
- [ ] **Kontrol Jarak Jauh Luar Rumah (WAN / Internet Access)**
  - **P2P E2EE Connection:** Koneksi aman tanpa *port forwarding* (WebRTC DataChannel / mesh network seperti Tailscale).
  - **Remote Touchpad & Keyboard via Internet:** Mengontrol laptop di rumah dari jaringan data seluler HP di luar.
  - **Screen Mirroring Laptop ke HP:** Menampilkan layar Ubuntu di HP secara real-time dengan latensi rendah (PipeWire + akselerasi hardware Intel VA-API H.264) sehingga laptop bisa dikendalikan penuh dari mana saja.

---

## 🏗️ Struktur Folder Proyek Saat Ini

```text
~/projects/phonedrop/
├── go.mod
├── go.sum
├── main.go                       # Entry point, routing HTTP, static embed
├── phonedrop                     # Binary Go mandiri
├── phonedrop.desktop             # Desktop launcher entry Ubuntu
├── phonedrop-launcher.sh         # Script launcher cerdas & notifikasi sistem
├── PROJECT_NOTES.md              # Dokumentasi & catatan proyek
├── android/
│   ├── AndroidManifest.xml       # Manifest APK (Foreground Service + Share Target)
│   ├── build-apk.sh              # Builder APK ultra-ringan tanpa Gradle (< 3 detik)
│   └── src/id/phonedrop/app/
│       ├── DirectSendService.java # Background service penerima file ke Downloads/PhoneDrop
│       └── MainActivity.java     # UI native gelap + pengirim Share Intent
├── internal/
│   ├── clipboard/
│   │   └── clipboard.go          # Monitor Wayland (wl-clipboard) & X11 (xclip)
│   ├── directsend/
│   │   └── manager.go            # Orkestrasi Direct Send (APK SSE, ADB Push, PWA WS)
│   ├── discovery/
│   │   └── discovery.go          # Deteksi IP LAN & banner terminal
│   ├── files/
│   │   └── handler.go            # Upload streaming, download, QR, & delete file
│   ├── input/
│   │   └── remote.go             # Simulasi kursor & keyboard via xdotool/ydotool
│   ├── tlscert/
│   │   └── tlscert.go            # CA lokal + sertifikat HTTPS otomatis (auto-renew IP LAN)
│   └── ws/
│       └── hub.go                # WebSocket hub (clipboard, files, remote input)
└── web/
    ├── app.js                    # Logika UI, WS reconnect, touchpad, modal QR, PWA install
    ├── index.html                # Antarmuka responsif mobile-first + PWA meta
    ├── style.css                 # Tema gelap minimalis (Glassmorphism & Earthy Accent)
    ├── site.webmanifest          # PWA manifest
    ├── sw.js                     # PWA Service Worker (offline cache)
    ├── icon.svg                  # Master vector icon
    ├── icon-192.png              # App icon Android / desktop (192x192)
    ├── icon-512.png              # App icon splash & desktop launcher (512x512)
    └── phonedrop.apk             # Installer APK Android Background Service (~37 KB)
```

---

## 🔒 Blueprint Keamanan & Arsitektur Jarak Jauh (WAN)

```text
[ LAPTOP DI RUMAH (UBUNTU) ]
       │
       ├── Core Daemon (Go) ─── PipeWire + VA-API (Screen Capture H.264)
       │
       └── Transport Layer:
             ├── Mode LAN: TLS 1.3 / WebSocket (0 Kuota)
             └── Mode WAN: WebRTC P2P (DTLS-SRTP End-to-End Encryption)
                                    │
                         (Koneksi Internet / P2P)
                                    │
[ HP ANDROID DI LUAR ] ─────────────┘
       ├── MediaCodec Hardware Decoder (Display Layar Laptop)
       ├── Touch Gesture Overlay (Kirim Input Mouse/Kbd)
       └── Scoped Storage Receiver (Direct Send)
```

1. **Zero-Trust Handshake:** Perangkat wajib melakukan pairing via kode 6-digit / QR terenkripsi (ECDH Curve25519) sebelum koneksi remote diizinkan.
2. **Anti-Port Forwarding:** Koneksi menembus NAT router menggunakan protokol WebRTC / STUN / TURN atau overlay VPN (Tailscale) sehingga port laptop tidak terekspos langsung ke internet publik.

---

## 🚀 Perintah Cepat Terminal

```bash
# 1. Update & unduh dependensi Go
go mod tidy

# 2. Build binary mandiri yang ringan (tanpa debug symbol)
go build -ldflags="-s -w" -o phonedrop main.go

# 3. Jalankan server PhoneDrop
./phonedrop

# 4. Akses melalui browser (ganti dengan IP LAN yang muncul di terminal):
# Laptop: http://localhost:8080
# HP:     https://<IP-LAN-LAPTOP>:8443  (setelah install CA dari http://<IP-LAN-LAPTOP>:8080/phonedrop-ca.crt)
```