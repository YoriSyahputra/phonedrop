# PhoneDrop v2 — Architecture & Binary Wire Protocol Specification

> **Role:** Principal Systems, Network, & Android Software Architect  
> **Target Environment:** Ubuntu 26.04 LTS (`x86_64`, Intel Celeron / 4 GB RAM, Idle RSS `< 40 MB`) + Android 8.0+ (`minSdk 26`, `targetSdk 34+`)  
> **Default Transport Port:** TCP `28200` (`adb reverse tcp:28200 tcp:28200` over USB; mDNS `_phonedrop._tcp.local.` over Wi-Fi LAN)

---

## 1. System Architecture Overview

PhoneDrop v2 replaces PhoneDrop v1's HTTP/SSE/WebSocket polling model with a unified, zero-copy, multiplexed **Big-Endian binary TCP tunnel (`PDP2`)** on port `28200`. A single persistent TCP connection carries:
1. **Bi-directional Clipboard Sync (`0x01`)** with SHA-256 origin-hash loop suppression.
2. **Streaming Chunked File Transfers (`0x02`–`0x04`)** using fixed **64 KiB (`65,536 B`)** packets, bounded RAM (`< 1 MB` per transfer), real-time sender & receiver throughput (`MB/s`), and end-to-end SHA-256 acknowledgment.
3. **Bi-directional Hardware-Accelerated Screen Mirroring (`0x05`)** using H.264/H.265 Annex-B NAL units (`MediaCodec` on Android, `VA-API`/`libx264` + embedded `mpv --wid` IPC on Linux).
4. **Remote Input Control (`0x06`)** supporting normalized touch/mouse/keyboard forwarding across both mirroring directions.

### Resource & Technology Constraints
- **Linux Desktop (`desktop-linux/`)**:
  - Pure Go (`go 1.24+`, zero external Go module dependencies — standard library only + minimal CGo bindings for native `gtk+-3.0`, `gdk-x11-3.0`, and `ayatana-appindicator3-0.1`).
  - Zero Electron, zero Chromium, zero WebKit runtime.
  - Hardware-accelerated embedded display window using `GtkDrawingArea` X11 window ID (`--wid=<XID>`) controlled by `mpv` JSON IPC (`/tmp/phonedrop-mpv-<pid>.sock`) with `--hwdec=vaapi-copy --profile=low-latency --untimed`.
  - Idle memory footprint target: **RSS `< 40 MB` (`40,960 KiB`)**.
- **Android Client (`android-client/`)**:
  - Modern Kotlin, `minSdk = 26`, `targetSdk = 34`, JVM 17.
  - Clean Architecture (`domain/`, `data/`, `service/`, `presentation/`) with Kotlin Coroutines & `StateFlow`/`SharedFlow`.
  - Jetpack Compose Material3 UI + Foreground Services (`DATA_SYNC` & `MEDIA_PROJECTION`) + `MediaCodec` + `SurfaceView`.

---

## 2. Binary Wire Protocol (`PDP2` v2)

All multi-byte integers on the wire are encoded in **Network Byte Order (Big-Endian)**. TCP sockets MUST enable `TCP_NODELAY = true` (`SO_KEEPALIVE = true`) to prevent Nagle batching stalls on video frames, input events, and `FileDone` ACKs.

### 2.1 Fixed 16-Byte Frame Header

Every packet sent across TCP port `28200` begins with a fixed **16-byte header**:

```text
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|           Magic = "PDP2" (0x50, 0x44, 0x50, 0x32)             |  Bytes 0..3
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
| Version(0x02) |  Packet Type  |     Flags     | Reserved(0x00)|  Bytes 4..7
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                        Sequence ID (uint32)                   |  Bytes 8..11
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                      Payload Length (uint32)                  |  Bytes 12..15
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                     Payload ([PayloadLen]byte)                |  Bytes 16..
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

| Offset | Size | Type | Field | Specification |
|---|---|---|---|---|
| `0..3` | 4B | `[4]byte` | `Magic` | `0x50, 0x44, 0x50, 0x32` (`"PDP2"`). Immediate disconnect on mismatch. |
| `4` | 1B | `uint8` | `Version` | `0x02` (Protocol v2). |
| `5` | 1B | `uint8` | `Type` | `0x01`=Clip, `0x02`=FileInit, `0x03`=FileChunk, `0x04`=FileDone, `0x05`=VideoFrame, `0x06`=InputEvent. |
| `6` | 1B | `uint8` | `Flags` | Bitmask (see table below). |
| `7` | 1B | `uint8` | `Reserved` | Must be `0x00` on send; ignored on read for minor extensibility. |
| `8..11` | 4B | `uint32` | `SeqID` | Per-connection monotonic sequence counter starting at `1`. |
| `12..15` | 4B | `uint32` | `PayloadLen` | Payload length in bytes. Hard safety cap: **`4,194,304` (4 MiB)** (`ErrPayloadTooLarge`). |

#### Header Flags Bitmask (`Flags`)
| Bit | Constant | Value | Meaning |
|---|---|---|---|
| 0 | `FlagAck` | `0x01` | Acknowledgment packet (e.g., receiver `FileDone` SHA-256 ACK). |
| 1 | `FlagError` | `0x02` | Failure indicator (combined with `FlagAck` on hash/size mismatch). |
| 2 | `FlagKeyframe` | `0x04` | `VideoFrame` contains an IDR keyframe. |
| 3 | `FlagCodecConfig` | `0x08` | `VideoFrame` contains SPS/PPS/VPS codec configuration NAL units. |
| 4 | `FlagDirLinuxToPhone` | `0x10` | Origin direction is Linux -> Phone (when unset: Phone -> Linux or peer). |
| 7 | `FlagHeartbeat` | `0x80` | Keepalive frame (`PayloadLen == 0`, valid with any packet type `0x01`..`0x06` or `0x01` ping). |

---

### 2.2 Packet Payload Schemas (`0x01` – `0x06`)

#### `0x01` — `PacketClip` (Clipboard Sync)
```text
+------------------+------------------+------------------+-------------+------------------+------------------+
| TimestampMs (8B) | OriginHash (8B)  | MimeLen (2B u16) | Mime (N B)  | TextLen (4B u32) | Text (M B UTF-8) |
+------------------+------------------+------------------+-------------+------------------+------------------+
```
- `OriginHash`: First 8 bytes of `SHA-256(Text)` used by both peers' anti-echo ring buffer so injecting a received clipboard entry does not re-broadcast it back to the sender.
- `Mime`: Typically `"text/plain;charset=utf-8"`.

#### `0x02` — `PacketFileInit` (File Transfer Initialization)
```text
+------------------+------------------+------------------+------------------+------------------+------------------+------------------+------------------+
| TransferID (16B) | TotalSize (8B)   | ChunkSize (4B)   | TotalChunks (4B) | NameLen (2B u16) | FileName (N B)   | MimeLen (2B u16) | MimeType (M B)   |
+------------------+------------------+------------------+------------------+------------------+------------------+------------------+------------------+
```
- `TransferID`: 16 random bytes (UUIDv4-style binary ID).
- `TotalSize`: `uint64` byte length (`0` allowed for empty files, yielding `TotalChunks = 0`).
- `ChunkSize`: Fixed `65536` (`64 KiB`).
- `TotalChunks`: `ceil(TotalSize / ChunkSize)`.
- `FileName`: Sanitized UTF-8 basename (path separators stripped).

#### `0x03` — `PacketFileChunk` (Fixed 64 KiB Streaming Data)
```text
+------------------+------------------+------------------+-----------------------------------+
| TransferID (16B) | ChunkIndex (4B)  | DataLen (4B u32) | Data ([DataLen]byte, 1..65536 B)  |
+------------------+------------------+------------------+-----------------------------------+
```
- Fixed 24-byte chunk header + raw payload (`1 <= DataLen <= 65536`).
- **Zero-buffering guarantee:** Senders read `<= 65536` bytes from `os.File` / `InputStream` into a reusable `sync.Pool` / pre-allocated `ByteArray(65536)` and write directly to the TCP socket (`WriteChunkFrame`). Receivers write `Data` directly to disk (`*.part` / `MediaStore` stream) while feeding an incremental `SHA-256` digest.

#### `0x04` — `PacketFileDone` (Integrity Validation & Signed ACK)
Total fixed payload = **57 bytes**:
```text
+------------------+------------------+------------------+------------------+
| TransferID (16B) | TotalBytes (8B)  | SHA256 (32B)     | StatusCode (1B)  |
+------------------+------------------+------------------+------------------+
```
- **Sender -> Receiver** (`Flags = 0x00`, `StatusCode = 0x00`): Sent after all chunks are flushed, carrying the full incremental `SHA-256` digest computed while reading the source file.
- **Receiver -> Sender** (`Flags = FlagAck (0x01)`):
  - Receiver flushes & closes the output file, compares `bytesWritten == TotalBytes` and constant-time `SHA256 == computedSHA256`.
  - On match: Atomically renames `.part` to final filename (or commits `IS_PENDING = 0` in `MediaStore`), updates UI/Notification to `100% Verified ✓`, and replies with `Flags = FlagAck (0x01)`, `StatusCode = 0x00`, and receiver's computed `SHA256`.
  - On mismatch: Deletes `.part` / pending entry, marks transfer `Failed`, and replies with `Flags = FlagAck | FlagError (0x03)`, `StatusCode = 0x01` (checksum mismatch) or `0x02` (size/IO error).

#### `0x05` — `PacketVideoFrame` (Hardware Video Stream)
14-byte video metadata header + Annex-B NAL unit payload:
```text
+---------------+---------------+---------------+---------------+---------------+-----------------------------------+
| StreamID (1B) | Codec (1B)    | Width (2B)    | Height (2B)   | PtsUs (8B)    | NALData (Annex-B bytes)           |
+---------------+---------------+---------------+---------------+---------------+-----------------------------------+
```
- `StreamID`: `0x01` = Phone -> Linux, `0x02` = Linux -> Phone.
- `Codec`: `0x01` = H.264 (`video/avc`), `0x02` = H.265 (`video/hevc`).
- `PtsUs`: Presentation timestamp in microseconds (`uint64`).
- `NALData`: One or more complete Annex-B NAL units prefixed with `00 00 00 01` or `00 00 01`. `Flags` sets `FlagCodecConfig (0x08)` on SPS/PPS/VPS and `FlagKeyframe (0x04)` on IDR frames.

#### `0x06` — `PacketInputEvent` (Remote Touch / Mouse / Keyboard)
22-byte fixed header + optional UTF-8 text:
```text
+---------------+------------------+----------+----------+----------+----------+------------------+------------------+
| EventType(1B) | ActionOrBtn (1B) | X (4B)   | Y (4B)   | Arg1(4B) | Arg2(4B) | TextLen (2B u16) | Text (N B UTF-8) |
+---------------+------------------+----------+----------+----------+----------+------------------+------------------+
```
- `EventType`:
  - `0x01` `MoveRel`: relative delta `(X, Y)` pixels.
  - `0x02` `TapAbs`: normalized `(X, Y)` in range `[0..65535]` scaled by target screen resolution (`Arg1` = action down/up/move).
  - `0x03` `MouseButton`: `ActionOrButton` (`1`=left, `2`=middle, `3`=right), `Arg1` (`1`=down, `0`=up, `2`=click).
  - `0x04` `Scroll`: `X` = horizontal delta, `Y` = vertical delta.
  - `0x05` `KeyCode`: `Arg1` = platform/HID keycode, `Arg2` = modifiers.
  - `0x06` `TextUTF8`: `Text` UTF-8 string to inject.
  - `0x07` `SwipeAbs`: start `(X, Y)` to end `(Arg1, Arg2)` normalized `[0..65535]`.

---

## 3. File Transfer Progress Architecture (Fixing v1 Progress Bugs)

### Why Progress Reporting Broke in Legacy Implementations
1. **In-Memory & Multipart Buffering:** HTTP multipart writers and unchunked streams buffered data in userspace/kernel buffers, causing progress bars to jump instantaneously to 99%–100% before bytes reached disk.
2. **Missing End-of-Stream Acknowledgment:** Senders declared completion immediately after `socket.write()` returned, even though megabytes remained in OS TCP send buffers (`SO_SNDBUF`) and had not been flushed or checksummed on the receiver.
3. **Android Notification Rate-Limit Dropping:** Calling `NotificationManager.notify()` on every 64 KiB packet (>500 updates/sec over USB) triggered Android `NotificationManagerService` per-package rate-limiting (`> ~5 Hz`), silently dropping intermediate progress updates and the final `100%` completion notification!

### PhoneDrop v2 Solution
1. **Fixed 64 KiB Streaming Chunks:** Every `0x03 FileChunk` corresponds to at most `65,536` payload bytes, guaranteeing deterministic progress ticks even on multi-gigabyte files while keeping RAM usage constant (`O(1)` buffer pool).
2. **Sliding-Window / EMA Throughput Calculation (`MB/s`):**
   - Both sender and receiver track instantaneous rate over `>= 100 ms` intervals smoothed with an Exponential Moving Average (`alpha = 0.35`): `speedMBps = emaBytesPerSec / (1024 * 1024)`.
3. **Mandatory `0x04 FileDone` Verification Barrier:**
   - Sender transitions from `InProgress` (`<= 99.9%`) to `Verified (100.0%)` **only after** receiving the peer's `0x04 FileDone` packet carrying `FlagAck` and matching `SHA-256`.
4. **Throttled UI & Notification Cadence:**
   - **Android `FileTransferService` Notification:** Throttled to **<= 4 Hz (`>= 250 ms` between intermediate updates)**, while **0% (`FileInit`) and 100% (`FileDone`) bypass throttling unconditionally** so Android never drops the start or completion banner.
   - **Android `StateFlow` / Compose UI & Linux GTK3 / CLI:** Throttled to `>= 50 ms` (`20 Hz`) using `g_idle_add` on GTK3 so UI rendering stays smooth even on a 2-core Celeron N4000.

---

## 4. ADB Hotplug Detection & Port-Forwarding State Machine

`desktop-linux/core/usb/adb_watcher.go` maintains a zero-polling event-driven connection to the local ADB daemon at `127.0.0.1:5037` using ADB's native `host:track-devices` wire protocol (`"0012host:track-devices"`):

```text
                       +-----------------------+
        App Start /    |     DISCONNECTED      | <-----------------------------+
        USB Unplug     +-----------------------+                               |
                                  |                                            |
                    host:track-devices sees <serial>                           |
                                  v                                            |
                       +-----------------------+                               |
       status ==       | DETECTED_UNAUTHORIZED | (Prompt user: "Allow USB    |
       "unauthorized"  +-----------------------+  Debugging on Phone")         |
                                  |                                            |
                    status transitions to "device"                             |
                                  v                                            |
                       +-----------------------+                               |
                       |      AUTHORIZED       |                               |
                       +-----------------------+                               |
                                  |                                            |
            exec: `adb -s <serial> reverse tcp:28200 tcp:28200`                |
                                  v                                            |
                       +-----------------------+    reverse error / timeout    |
                       |      FORWARDING       | ----------------------+       |
                       +-----------------------+                       |       |
                                  |                                    v       |
                    verify `adb -s <serial> reverse --list`     +-----------+  |
                                  v                             |   ERROR   | -+
                       +-----------------------+                | (backoff) |
                       |     TUNNEL_READY      |                +-----------+
                       +-----------------------+                       ^
                                  |                                    |
                    Android connects to 127.0.0.1:28200                |
                                  v                                    |
                       +-----------------------+    USB unplug / EOF   |
                       |     CONNECTED_USB     | ----------------------+
                       +-----------------------+
```

### Dual-Mode Connectivity Priority (Android Client)
1. **Zero-Config USB First:** `BinarySocketClient` first probes `127.0.0.1:28200` with a short connect timeout (`600 ms`). When `adb reverse tcp:28200 tcp:28200` is active, this immediately establishes a high-throughput USB loopback tunnel without requiring Wi-Fi, IP entry, or firewall changes.
2. **Wi-Fi LAN mDNS Fallback:** If USB loopback is unavailable, `MdnsDiscoveryManager` (`NsdManager`) resolves `_phonedrop._tcp.` advertised by Linux (`avahi-publish-service` / pure-Go mDNS responder) on port `28200` and connects directly over the LAN. Manual `host:port` override is also supported.

---

## 5. Bi-Directional Screen Mirroring Pipeline

### 5.1 Phone -> Linux (`StreamID = 0x01`)
1. **Primary Capture Path (Android APK Native):**
   - `ScreenCaptureService` (`FOREGROUND_SERVICE_MEDIA_PROJECTION`) binds `MediaProjection` + `VirtualDisplay` directly to `MediaCodec.createEncoderByType("video/avc")` input `Surface` (`COLOR_FormatSurface`, `KEY_BIT_RATE` adaptive, `KEY_FRAME_RATE = 30`, `KEY_I_FRAME_INTERVAL = 1`).
   - Output Annex-B NAL buffers (`BUFFER_FLAG_CODEC_CONFIG` SPS/PPS and IDR/P-frames) are encapsulated in `0x05 PacketVideoFrame` packets and pushed over TCP `:28200`.
2. **Optional Zero-Prompt USB Bridge (`scrcpy_bridge.go`):**
   - When connected over USB ADB without opening the Android projection prompt, Linux can ingest raw H.264 directly via `adb exec-out screenrecord --output-format=h264 -` (or `scrcpy-server`) into the exact same Annex-B `nal_splitter` and `mpv` player.
3. **Linux Embedded Hardware Display (`mpv_embed.go`):**
   - GTK3 embeds an X11 `GtkDrawingArea` whose native XID (`GDK_WINDOW_XID`) is passed to:
     `mpv --wid=<XID> --profile=low-latency --no-cache --untimed --hwdec=vaapi-copy --demuxer=h264 --input-ipc-server=<unixSock> fd://0`
   - Incoming `0x05` NAL units are piped directly to `mpv` stdin; cached SPS/PPS headers are prepended automatically if needed.
   - Mouse clicks, drags, and scroll events inside the `GtkDrawingArea` are normalized (`[0..65535]`) and sent to Android via `0x06 PacketInputEvent`.

### 5.2 Linux -> Phone (`StreamID = 0x02`)
1. **Linux Hardware Screen Capture (`desktop_capture.go`):**
   - Primary encoder: `ffmpeg -vaapi_device /dev/dri/renderD128 -f x11grab -framerate 30 -video_size <WxH> -i :0.0 -vf format=nv12,hwupload -c:v h264_vaapi -qp 26 -bf 0 -g 30 -f h264 pipe:1`.
   - Automatic fallback (if VA-API unavailable): `libx264 -preset ultrafast -tune zerolatency -profile:v baseline -g 30 -f h264 pipe:1`.
   - Go's streaming `nal_splitter.go` detects `00 00 00 01` / `00 00 01` start codes, tags SPS/PPS (`FlagCodecConfig`) and IDR (`FlagKeyframe`), caches the latest SPS/PPS pair on `TunnelServer`, and broadcasts `0x05 PacketVideoFrame` packets.
2. **Android Hardware Decode & Render (`VideoStreamDecoder.kt`):**
   - `SurfaceView` supplies a native `Surface` directly to `MediaCodec.createDecoderByType("video/avc")` configured with `KEY_LOW_LATENCY = 1` (API 30+).
   - `VideoStreamDecoder` queues incoming `0x05` NAL frames into `MediaCodec` input buffers and invokes `releaseOutputBuffer(index, true)` for zero-copy GPU rendering onto `SurfaceView`.
   - Touch taps, swipes, and key events captured on the `SurfaceView` overlay are sent back via `0x06 PacketInputEvent` and executed on Linux via `xdotool` / `ydotool`.

---

## 6. Modular Directory Structure

### 6.1 `desktop-linux/` (Pure Go 1.24+ Module + Thin CGo GTK3 UI)
```text
desktop-linux/
├── go.mod                              # module phonedrop/v2/desktop (go 1.24, zero external Go deps)
├── cmd/
│   └── phonedrop-desktop/
│       └── main.go                     # CLI/GUI/Headless daemon entrypoint & wiring
├── core/
│   ├── protocol/
│   │   ├── framer.go                   # 16B Big-Endian header, ReadFrame/WriteFrame/WriteChunkFrame, sync.Pool
│   │   ├── payloads.go                 # Binary codecs for 0x01..0x06 packet payloads
│   │   └── framer_test.go              # Round-trip, invalid magic, cap, & pool unit tests
│   ├── transport/
│   │   ├── tcp_tunnel.go               # TCP :28200 server & client, TCP_NODELAY, heartbeat, session router
│   │   └── tcp_tunnel_test.go          # Loopback multiplexed session & SPS/PPS late-joiner tests
│   ├── usb/
│   │   ├── adb_watcher.go              # 127.0.0.1:5037 host:track-devices state machine & adb reverse :28200
│   │   └── adb_watcher_test.go         # Mock ADB daemon hotplug & state transition tests
│   ├── discovery/
│   │   └── mdns.go                     # _phonedrop._tcp.local. mDNS advertiser (avahi + Go UDP fallback)
│   ├── transfer/
│   │   ├── file_engine.go              # Streaming 64KB chunk sender/receiver, EMA MB/s, SHA-256 FileDone ACK
│   │   └── file_engine_test.go         # Multi-MB transfer, progress ticks, 0B file, & corruption rejection tests
│   ├── clipboard/
│   │   └── sync.go                     # X11 (xclip) & Wayland (wl-clipboard) watcher + OriginHash anti-loop
│   ├── input/
│   │   └── injector.go                 # xdotool / ydotool mouse, tap, scroll, & keyboard injector
│   └── mirror/
│       ├── nal_splitter.go             # Streaming Annex-B H.264/H.265 NAL splitter & classifier
│       ├── nal_splitter_test.go        # NAL unit boundary & SPS/PPS/IDR detection unit tests
│       ├── desktop_capture.go          # ffmpeg h264_vaapi / libx264 x11grab -> 0x05 VideoFrame streamer
│       ├── mpv_embed.go                # mpv --wid=<XID> low-latency launcher & JSON UNIX IPC controller
│       ├── phone_receiver.go           # 0x05 Phone->Linux frame ingestor feeding embedded mpv stdin
│       └── scrcpy_bridge.go            # Optional ADB screenrecord / scrcpy-server USB fallback bridge
├── ui/
│   ├── gtk_linux.go                    # Native GTK3 window + Ayatana AppIndicator tray + DrawingArea XID + g_idle_add
│   ├── gtk_stub.go                     # !cgo / nogui stub fallback
│   └── cli_progress.go                 # Terminal ANSI progress bar & speed/SHA-256 printer
└── test/
    └── e2e_integration_test.go         # End-to-end TCP :28200 clip, 5MB chunked file, video, & input suite
```

### 6.2 `android-client/` (Kotlin Clean Architecture + Coroutines/StateFlow + Compose)
```text
android-client/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties                   # Low-RAM JVM tuning (-Xmx640m, --no-daemon, single worker)
├── gradlew / gradlew.bat / gradle/
└── app/
    ├── build.gradle.kts                # minSdk 26, targetSdk 34, Kotlin 1.9.24, Compose BOM, Coroutines
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml     # DATA_SYNC & MEDIA_PROJECTION services, permissions, ACTION_SEND
        │   ├── res/
        │   │   ├── values/strings.xml
        │   │   └── values/themes.xml
        │   └── java/com/phonedrop/v2/
        │       ├── domain/
        │       │   ├── model/
        │       │   │   ├── ConnectionState.kt
        │       │   │   ├── TransferState.kt
        │       │   │   └── MirrorMode.kt
        │       │   ├── repository/
        │       │   │   └── PhoneDropRepository.kt
        │       │   └── usecase/
        │       │       ├── ConnectSessionUseCase.kt
        │       │       ├── SendFileUseCase.kt
        │       │       └── SyncClipboardUseCase.kt
        │       ├── data/
        │       │   ├── protocol/
        │       │   │   ├── PacketFramer.kt
        │       │   │   └── PacketPayloads.kt
        │       │   ├── network/
        │       │   │   ├── BinarySocketClient.kt
        │       │   │   └── MdnsDiscoveryManager.kt
        │       │   ├── repository/
        │       │   │   ├── FileTransferManager.kt
        │       │   │   └── PhoneDropSessionHolder.kt
        │       │   └── mirror/
        │       │       ├── VideoStreamDecoder.kt
        │       │       └── ScreenCaptureEncoder.kt
        │       ├── service/
        │       │   ├── FileTransferService.kt      # Foreground service + 4Hz throttled Notification progress
        │       │   └── ScreenCaptureService.kt     # MediaProjection foreground service
        │       └── presentation/
        │           ├── MainActivity.kt             # Compose host, document picker, MediaProjection, Share Intent
        │           ├── MainViewModel.kt            # StateFlow bindings for Connection, Transfer, Clip, Mirror
        │           └── ui/
        │               └── PhoneDropScreen.kt      # Material3 dark UI + progress bars + SurfaceView overlay
        └── test/java/com/phonedrop/v2/
            ├── PacketFramerTest.kt                 # Binary framing & payload parity tests matching Go
            └── FileTransferSocketTest.kt           # 64KB chunked loopback socket transfer & SHA-256 ACK tests
```
