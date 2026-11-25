package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"log"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"runtime/debug"
	"sort"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"phonedrop/v2/desktop/core/clipboard"
	"phonedrop/v2/desktop/core/discovery"
	"phonedrop/v2/desktop/core/input"
	"phonedrop/v2/desktop/core/mirror"
	"phonedrop/v2/desktop/core/protocol"
	"phonedrop/v2/desktop/core/transfer"
	"phonedrop/v2/desktop/core/transport"
	"phonedrop/v2/desktop/core/usb"
	"phonedrop/v2/desktop/ui"
)

type originStore struct {
	mu      sync.RWMutex
	path    string
	origins map[string]string // filename -> "phone" | "laptop"
}

func newOriginStore(path string) *originStore {
	s := &originStore{
		path:    path,
		origins: make(map[string]string),
	}
	if data, err := os.ReadFile(path); err == nil {
		_ = json.Unmarshal(data, &s.origins)
	}
	return s
}

func (s *originStore) Get(name string) string {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.origins[name]
}

func (s *originStore) Set(name, origin string) {
	if name == "" || origin == "" {
		return
	}
	s.mu.Lock()
	if s.origins[name] == origin {
		s.mu.Unlock()
		return
	}
	s.origins[name] = origin
	data, err := json.Marshal(s.origins)
	s.mu.Unlock()
	if err == nil && s.path != "" {
		_ = os.MkdirAll(filepath.Dir(s.path), 0o755)
		_ = os.WriteFile(s.path, data, 0o644)
	}
}

func (s *originStore) Delete(name string) {
	s.mu.Lock()
	if _, ok := s.origins[name]; !ok {
		s.mu.Unlock()
		return
	}
	delete(s.origins, name)
	data, err := json.Marshal(s.origins)
	s.mu.Unlock()
	if err == nil && s.path != "" {
		_ = os.WriteFile(s.path, data, 0o644)
	}
}

type mirrorConfigStore struct {
	mu   sync.RWMutex
	path string
	cfg  protocol.MirrorConfig
}

func newMirrorConfigStore(path string) *mirrorConfigStore {
	s := &mirrorConfigStore{
		path: path,
		cfg:  protocol.DefaultMirrorConfig(),
	}
	if data, err := os.ReadFile(path); err == nil {
		var loaded protocol.MirrorConfig
		if json.Unmarshal(data, &loaded) == nil {
			s.cfg = loaded.Sanitize()
		}
	}
	return s
}

func (s *mirrorConfigStore) Get() protocol.MirrorConfig {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.cfg
}

func (s *mirrorConfigStore) Set(cfg protocol.MirrorConfig) protocol.MirrorConfig {
	clean := cfg.Sanitize()
	s.mu.Lock()
	s.cfg = clean
	data, err := json.MarshalIndent(clean, "", "  ")
	s.mu.Unlock()
	if err == nil && s.path != "" {
		_ = os.MkdirAll(filepath.Dir(s.path), 0o755)
		_ = os.WriteFile(s.path, data, 0o644)
	}
	return clean
}

func prioritizeCombo(current string, choices []string) string {
	out := make([]string, 0, len(choices)+1)
	seen := make(map[string]bool, len(choices)+1)
	if current != "" {
		out = append(out, current)
		seen[current] = true
	}
	for _, c := range choices {
		if !seen[c] {
			out = append(out, c)
			seen[c] = true
		}
	}
	return strings.Join(out, "|")
}

func formatMirrorSummary(c protocol.MirrorConfig) string {
	fps := "Auto Hz"
	if c.FPS > 0 {
		fps = fmt.Sprintf("%d FPS", c.FPS)
	}
	res := "Asli"
	if c.MaxSize > 0 {
		res = fmt.Sprintf("%dp", c.MaxSize)
	}
	aud := "Audio ON"
	if !c.Audio {
		aud = "No Audio"
	}
	return fmt.Sprintf("%s • %d Mbps • %s • %s • %s", fps, c.BitrateMbps, res, aud, strings.ToUpper(c.Codec))
}

func main() {
	debug.SetMemoryLimit(28 << 20)
	port := flag.Int("port", protocol.DefaultPort, "TCP listen port for PDP2 tunnel")
	destDir := flag.String("dest", "", "Destination folder for received files (default ~/Downloads/PhoneDrop)")
	headless := flag.Bool("headless", false, "Run headless daemon without native X11 GUI")
	sendFile := flag.String("send", "", "Send a file via CLI to --connect endpoint and exit")
	connectAddr := flag.String("connect", fmt.Sprintf("127.0.0.1:%d", protocol.DefaultPort), "Target endpoint for --send mode")
	noADB := flag.Bool("no-adb", false, "Disable ADB USB hotplug watcher")
	noMDNS := flag.Bool("no-mdns", false, "Disable mDNS LAN advertisement")
	flag.Parse()

	home, err := os.UserHomeDir()
	if err != nil {
		log.Fatalf("resolve home dir: %v", err)
	}
	if *destDir == "" {
		*destDir = filepath.Join(home, "Downloads", "PhoneDrop")
	}
	origins := newOriginStore(filepath.Join(home, ".config", "phonedrop", "file-origins.json"))
	mirrorStore := newMirrorConfigStore(filepath.Join(home, ".config", "phonedrop", "mirror-settings.json"))

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	cliPrinter := ui.NewCLIProgressPrinter(os.Stdout)

	if *sendFile != "" {
		if err := runCLISend(ctx, *connectAddr, *destDir, *sendFile, cliPrinter); err != nil {
			log.Fatalf("send failed: %v", err)
		}
		return
	}

	var uiMu sync.Mutex
	var guiCtrl *ui.Controller
	var adbSerial string
	var tunnel *transport.TunnelServer
	var fileEngine *transfer.Engine
	var progressClearTimer *time.Timer

	clipMgr := clipboard.NewManager()
	desktopCap := mirror.NewDesktopCapture(mirror.CaptureConfig{}, nil)
	capW, capH := desktopCap.ScreenSize()
	injector := input.NewInjector(capW, capH)

	scrcpyMgr := mirror.NewScrcpyManager(func() {
		uiMu.Lock()
		ctrl := guiCtrl
		uiMu.Unlock()
		if ctrl != nil {
			ctrl.SetPhoneMirrorActive(false)
		}
	})

	detectLinkInfo := func(s *transport.Session) (linkType string, label string) {
		if s == nil {
			return "", fmt.Sprintf("Siaga :%d", *port)
		}
		if s.Mode == transport.ModeWiFiLAN {
			return "wifi_lan", "Terhubung • Wireless LAN"
		}
		uiMu.Lock()
		ser := adbSerial
		uiMu.Unlock()
		ser = mirror.ResolveActiveADBSerial(ser)
		if strings.Contains(ser, ":") || strings.Contains(strings.ToLower(ser), "tls") {
			return "wireless_adb", "Terhubung • Wireless ADB"
		}
		return "usb_cable", "Terhubung • Kabel USB"
	}

	refreshConnBadge := func() {
		var primary *transport.Session
		if tunnel != nil {
			primary = tunnel.PrimarySession()
		}
		linkType, label := detectLinkInfo(primary)
		uiMu.Lock()
		ctrl := guiCtrl
		uiMu.Unlock()
		if ctrl != nil {
			ctrl.SetConnectionBadge(label, linkType, primary != nil)
		}
		if primary != nil {
			if b, err := (protocol.ControlMessage{
				Op:       protocol.ControlOpConnInfo,
				LinkType: linkType,
			}).Encode(); err == nil {
				_ = primary.WriteFrame(protocol.PacketTypeControl, protocol.FlagDirLinuxToPhone, b)
			}
		}
	}

	setActivity := func(msg string) {
		log.Println(msg)
		uiMu.Lock()
		ctrl := guiCtrl
		uiMu.Unlock()
		if ctrl != nil {
			ctrl.SetActivityStatus(msg)
		}
	}

	listSharedFiles := func() ([]protocol.RemoteFileEntry, []ui.FileRow) {
		entries, err := os.ReadDir(*destDir)
		if err != nil {
			return nil, nil
		}
		type item struct {
			name   string
			size   int64
			mod    int64
			origin string
		}
		var items []item
		for _, ent := range entries {
			if ent.IsDir() || strings.HasPrefix(ent.Name(), ".phonedrop-") || strings.HasSuffix(ent.Name(), ".part") {
				continue
			}
			info, err := ent.Info()
			if err != nil {
				continue
			}
			items = append(items, item{
				name:   ent.Name(),
				size:   info.Size(),
				mod:    info.ModTime().UnixMilli(),
				origin: origins.Get(ent.Name()),
			})
		}
		sort.Slice(items, func(i, j int) bool {
			return items[i].mod > items[j].mod
		})

		protoFiles := make([]protocol.RemoteFileEntry, len(items))
		uiRows := make([]ui.FileRow, len(items))
		for i, it := range items {
			protoFiles[i] = protocol.RemoteFileEntry{Name: it.name, Size: it.size, ModMs: it.mod, Origin: it.origin}
			uiRows[i] = ui.FileRow{Name: it.name, Size: it.size, ModMs: it.mod, Origin: it.origin}
		}
		return protoFiles, uiRows
	}

	syncSharedFiles := func(target *transport.Session) {
		protoFiles, uiRows := listSharedFiles()
		uiMu.Lock()
		ctrl := guiCtrl
		uiMu.Unlock()
		if ctrl != nil {
			ctrl.SetFileList(uiRows)
		}
		msgBytes, err := (protocol.ControlMessage{
			Op:    protocol.ControlOpFileList,
			Files: protoFiles,
		}).Encode()
		if err != nil {
			return
		}
		if target != nil {
			_ = target.WriteFrame(protocol.PacketTypeControl, protocol.FlagDirLinuxToPhone, msgBytes)
		} else if tunnel != nil {
			tunnel.BroadcastFrame(protocol.PacketTypeControl, protocol.FlagDirLinuxToPhone, msgBytes)
		}
	}

	stageLaptopFileToShared := func(srcPath string) {
		absSrc, err1 := filepath.Abs(srcPath)
		absDest, err2 := filepath.Abs(*destDir)
		base := protocol.SanitizeFileName(filepath.Base(srcPath))
		if err1 == nil && err2 == nil && filepath.Dir(absSrc) == absDest {
			origins.Set(base, "laptop")
			syncSharedFiles(nil)
			return
		}
		dstPath := filepath.Join(*destDir, base)
		if _, statErr := os.Stat(dstPath); os.IsNotExist(statErr) {
			// Instant zero-copy hardlink when on same filesystem; copy small files (< 50 MB) across mounts.
			if linkErr := os.Link(srcPath, dstPath); linkErr == nil {
				origins.Set(base, "laptop")
				syncSharedFiles(nil)
				return
			}
			if info, err := os.Stat(srcPath); err == nil && info.Size() <= 50*1024*1024 {
				if in, err := os.Open(srcPath); err == nil {
					if out, err := os.Create(dstPath); err == nil {
						_, _ = io.Copy(out, in)
						_ = out.Close()
						origins.Set(base, "laptop")
					}
					_ = in.Close()
				}
			}
		} else {
			origins.Set(base, "laptop")
		}
		syncSharedFiles(nil)
	}

	fileEngine, err = transfer.NewEngine(*destDir, func(rep transfer.ProgressReport) {
		cliPrinter.Print(rep)
		uiMu.Lock()
		ctrl := guiCtrl
		if progressClearTimer != nil {
			progressClearTimer.Stop()
			progressClearTimer = nil
		}
		if rep.Verified || rep.Err != nil {
			if ctrl != nil {
				progressClearTimer = time.AfterFunc(2500*time.Millisecond, func() {
					ctrl.ClearProgress()
				})
			}
		}
		uiMu.Unlock()

		if ctrl != nil {
			ctrl.UpdateProgress(rep)
		}
		if rep.Verified {
			if rep.Direction == transfer.DirectionReceive {
				origins.Set(rep.FileName, "phone")
			} else if rep.Direction == transfer.DirectionSend && origins.Get(rep.FileName) == "" {
				origins.Set(rep.FileName, "laptop")
			}
			go syncSharedFiles(nil)
		}
	})
	if err != nil {
		log.Fatalf("init transfer engine: %v", err)
	}

	sendControlErr := func(s *transport.Session, errText string) {
		if s == nil {
			return
		}
		b, err := (protocol.ControlMessage{Op: protocol.ControlOpError, Message: errText}).Encode()
		if err == nil {
			_ = s.WriteFrame(protocol.PacketTypeControl, protocol.FlagDirLinuxToPhone|protocol.FlagError, b)
		}
	}

	handlers := transport.Handlers{
		OnConnect: func(s *transport.Session) {
			refreshConnBadge()
			setActivity(fmt.Sprintf("Perangkat tersambung (%s)", s.RemoteAddr))
			go func() {
				syncSharedFiles(s)
				curCfg := mirrorStore.Get()
				if b, err := (protocol.ControlMessage{
					Op:     protocol.ControlOpUpdateMirrorConfig,
					Mirror: &curCfg,
				}).Encode(); err == nil {
					_ = s.WriteFrame(protocol.PacketTypeControl, protocol.FlagDirLinuxToPhone, b)
				}
				if cur := clipMgr.Current(); cur != "" {
					if b, err := clipboard.BuildClipPacket(cur); err == nil {
						_ = s.WriteFrame(protocol.PacketTypeClip, protocol.FlagDirLinuxToPhone, b)
					}
				}
			}()
		},
		OnDisconnect: func(_ *transport.Session, _ error) {
			if len(tunnel.Sessions()) == 0 {
				desktopCap.Stop()
				uiMu.Lock()
				ctrl := guiCtrl
				uiMu.Unlock()
				if ctrl != nil {
					ctrl.SetLinuxStreamActive(false)
				}
				refreshConnBadge()
				setActivity("Menunggu koneksi HP (USB / Wireless)...")
			}
		},
		OnClip: func(_ *transport.Session, clip protocol.ClipPayload) {
			if err := clipMgr.ApplyRemote(clip); err != nil {
				log.Printf("[CLIP] apply failed: %v", err)
				return
			}
			uiMu.Lock()
			ctrl := guiCtrl
			uiMu.Unlock()
			if ctrl != nil {
				ctrl.SetClipboardPreview(clip.Text)
			}
		},
		OnFileInit: func(s *transport.Session, init protocol.FileInitPayload) {
			fileEngine.HandleFileInit(s, init)
		},
		OnFileChunk: func(_ *transport.Session, chunk protocol.FileChunkPayload) {
			fileEngine.HandleFileChunk(chunk)
		},
		OnFileDone: func(s *transport.Session, flags uint8, done protocol.FileDonePayload) {
			fileEngine.HandleFileDone(s, flags, done)
		},
		OnInputEvent: func(_ *transport.Session, ev protocol.InputEventPayload) {
			injector.Execute(ev)
		},
		OnControl: func(s *transport.Session, payload []byte) {
			msg, err := protocol.DecodeControlMessage(payload)
			if err != nil {
				return
			}
			switch msg.Op {
			case protocol.ControlOpListFiles:
				syncSharedFiles(s)
			case protocol.ControlOpPullFile:
				safe := protocol.SanitizeFileName(msg.Name)
				targetPath := filepath.Join(*destDir, safe)
				go func() {
					if err := fileEngine.SendFile(ctx, s, targetPath, nil); err != nil {
						sendControlErr(s, fmt.Sprintf("Gagal kirim %s: %v", safe, err))
					}
				}()
			case protocol.ControlOpDeleteFile:
				safe := protocol.SanitizeFileName(msg.Name)
				targetPath := filepath.Join(*destDir, safe)
				if err := os.Remove(targetPath); err != nil {
					sendControlErr(s, fmt.Sprintf("Gagal hapus %s: %v", safe, err))
					return
				}
				origins.Delete(safe)
				syncSharedFiles(nil)
			case protocol.ControlOpUpdateMirrorConfig:
				if msg.Mirror != nil {
					cleanCfg := mirrorStore.Set(*msg.Mirror)
					desktopCap.ApplyMirrorConfig(cleanCfg)
					scrcpyMgr.SetConfig(cleanCfg)
					if desktopCap.IsRunning() {
						desktopCap.Stop()
						_ = desktopCap.Start(ctx)
						tunnel.ReplayCodecConfig(s)
					}
					if scrcpyMgr.IsRunning() {
						uiMu.Lock()
						ser := adbSerial
						uiMu.Unlock()
						go func() { _ = scrcpyMgr.RestartIfRunning(ser, cleanCfg) }()
					}
					setActivity("Setelan Mirror: " + formatMirrorSummary(cleanCfg))
				}
			case protocol.ControlOpStartDesktopStream, protocol.ControlOpRequestKeyframe:
				if msg.Mirror != nil {
					cleanCfg := mirrorStore.Set(*msg.Mirror)
					desktopCap.ApplyMirrorConfig(cleanCfg)
					scrcpyMgr.SetConfig(cleanCfg)
				}
				_ = desktopCap.Start(ctx)
				tunnel.ReplayCodecConfig(s)
				uiMu.Lock()
				ctrl := guiCtrl
				uiMu.Unlock()
				if ctrl != nil {
					ctrl.SetLinuxStreamActive(true)
				}
				setActivity("Streaming Layar Laptop -> HP (" + formatMirrorSummary(mirrorStore.Get()) + ")")
			case protocol.ControlOpStopDesktopStream:
				desktopCap.Stop()
				uiMu.Lock()
				ctrl := guiCtrl
				uiMu.Unlock()
				if ctrl != nil {
					ctrl.SetLinuxStreamActive(false)
				}
				setActivity("Streaming Layar Laptop dihentikan")
			case protocol.ControlOpStartPhoneScrcpy:
				if msg.Mirror != nil {
					cleanCfg := mirrorStore.Set(*msg.Mirror)
					desktopCap.ApplyMirrorConfig(cleanCfg)
					scrcpyMgr.SetConfig(cleanCfg)
				}
				uiMu.Lock()
				ser := adbSerial
				ctrl := guiCtrl
				uiMu.Unlock()
				if err := scrcpyMgr.StartWithConfig(ser, mirrorStore.Get()); err != nil {
					sendControlErr(s, err.Error())
					setActivity(err.Error())
					return
				}
				if ctrl != nil {
					ctrl.SetPhoneMirrorActive(true)
				}
				setActivity("scrcpy Aktif (" + formatMirrorSummary(mirrorStore.Get()) + ")")
			case protocol.ControlOpStopPhoneScrcpy:
				scrcpyMgr.Stop()
				uiMu.Lock()
				ctrl := guiCtrl
				uiMu.Unlock()
				if ctrl != nil {
					ctrl.SetPhoneMirrorActive(false)
				}
			}
		},
	}

	listenAddr := fmt.Sprintf(":%d", *port)
	tunnel = transport.NewTunnelServer(listenAddr, handlers)
	if _, err := tunnel.Listen(); err != nil {
		log.Fatalf("listen %s: %v", listenAddr, err)
	}

	desktopCap = mirror.NewDesktopCapture(mirror.CaptureConfig{}, func(flags uint8, hdr protocol.VideoFrameHeader, nal []byte) {
		tunnel.BroadcastVideoFrame(flags, hdr, nal)
	})
	initMirrorCfg := mirrorStore.Get()
	desktopCap.ApplyMirrorConfig(initMirrorCfg)
	scrcpyMgr.SetConfig(initMirrorCfg)

	go func() {
		if err := tunnel.Serve(ctx); err != nil {
			log.Printf("[NET] server stopped: %v", err)
		}
	}()

	if !*noMDNS {
		discovery.Advertise(ctx, "", *port)
	}

	if !*noADB {
		watcher := usb.NewWatcher(*port, func(ev usb.DeviceEvent) {
			uiMu.Lock()
			if ev.State == usb.StateTunnelReady {
				adbSerial = ev.Serial
			} else if ev.State == usb.StateDisconnected {
				adbSerial = ""
			}
			uiMu.Unlock()
			refreshConnBadge()
			if len(tunnel.Sessions()) == 0 {
				setActivity(fmt.Sprintf("ADB [%s] %s", ev.State, strings.TrimSpace(ev.Serial)))
			}
		})
		go watcher.Watch(ctx)
	}

	go clipMgr.Watch(ctx, 700*time.Millisecond, func(clip protocol.ClipPayload) {
		b, err := clip.Encode()
		if err == nil {
			tunnel.BroadcastFrame(protocol.PacketTypeClip, protocol.FlagDirLinuxToPhone, b)
		}
		uiMu.Lock()
		ctrl := guiCtrl
		uiMu.Unlock()
		if ctrl != nil {
			ctrl.SetClipboardPreview(clip.Text)
		}
	})

	useGUI := !*headless && (os.Getenv("DISPLAY") != "" || os.Getenv("WAYLAND_DISPLAY") != "")
	if useGUI {
		ctrl := ui.NewController(ui.Callbacks{
			OnSendFilePath: func(path string) {
				sess := tunnel.PrimarySession()
				if sess == nil {
					setActivity("Belum ada HP terhubung")
					return
				}
				stageLaptopFileToShared(path)
				if err := fileEngine.SendFile(ctx, sess, path, nil); err != nil {
					setActivity(fmt.Sprintf("Kirim gagal: %v", err))
				}
			},
			OnSendSharedFile: func(name string) {
				sess := tunnel.PrimarySession()
				if sess == nil {
					setActivity("Belum ada HP terhubung")
					return
				}
				safe := protocol.SanitizeFileName(name)
				if origins.Get(safe) == "" {
					origins.Set(safe, "laptop")
				}
				target := filepath.Join(*destDir, safe)
				if err := fileEngine.SendFile(ctx, sess, target, nil); err != nil {
					setActivity(fmt.Sprintf("Kirim gagal: %v", err))
				}
			},
			OnOpenFile: func(name string) {
				target := filepath.Join(*destDir, protocol.SanitizeFileName(name))
				_ = exec.Command("xdg-open", target).Start()
			},
			OnDeleteSharedFile: func(name string) {
				safe := protocol.SanitizeFileName(name)
				target := filepath.Join(*destDir, safe)
				if err := os.Remove(target); err == nil {
					origins.Delete(safe)
					syncSharedFiles(nil)
				}
			},
			OnOpenFolder: func() {
				_ = exec.Command("xdg-open", *destDir).Start()
			},
			OnRefreshFiles: func() {
				syncSharedFiles(nil)
			},
			OnSyncClipboard: func() {
				text := clipMgr.Current()
				if text == "" {
					return
				}
				b, err := clipboard.BuildClipPacket(text)
				if err == nil {
					tunnel.BroadcastFrame(protocol.PacketTypeClip, protocol.FlagDirLinuxToPhone, b)
					setActivity("Clipboard dikirim ke HP")
				}
			},
			OnTogglePhoneMirror: func(active bool) {
				uiMu.Lock()
				ser := adbSerial
				c := guiCtrl
				uiMu.Unlock()
				if active {
					cfg := mirrorStore.Get()
					if err := scrcpyMgr.StartWithConfig(ser, cfg); err != nil {
						setActivity(err.Error())
						if c != nil {
							c.SetPhoneMirrorActive(false)
						}
						return
					}
					setActivity("scrcpy Aktif (" + formatMirrorSummary(cfg) + ")")
				} else {
					scrcpyMgr.Stop()
					setActivity("scrcpy dihentikan")
				}
			},
			OnToggleLinuxStream: func(active bool) {
				if active {
					cfg := mirrorStore.Get()
					desktopCap.ApplyMirrorConfig(cfg)
					_ = desktopCap.Start(ctx)
					setActivity("Streaming Layar Laptop -> HP (" + formatMirrorSummary(cfg) + ")")
				} else {
					desktopCap.Stop()
					setActivity("Streaming Layar Laptop dihentikan")
				}
			},
			OnConfigureMirror: func() {
				cur := mirrorStore.Get()
				curFps := "Auto"
				if cur.FPS > 0 {
					curFps = strconv.Itoa(cur.FPS)
				}
				curRes := "Asli"
				if cur.MaxSize > 0 {
					curRes = strconv.Itoa(cur.MaxSize)
				}
				curBr := strconv.Itoa(cur.BitrateMbps)
				curAud := "Aktif"
				if !cur.Audio {
					curAud = "Tanpa Audio"
				}
				curCodec := strings.ToLower(cur.Codec)
				if curCodec != "h265" {
					curCodec = "h264"
				}
				curScreen := "Tetap Nyala"
				if cur.TurnScreenOff {
					curScreen = "Matikan Layar HP"
				}

				out, err := exec.Command(
					"zenity", "--forms",
					"--title=Pengaturan Screen Mirroring (PhoneDrop)",
					"--text=Atur FPS, resolusi, bitrate, audio, codec, dan layar HP:",
					"--separator=|",
					"--add-combo=Target FPS", "--combo-values="+prioritizeCombo(curFps, []string{"Auto", "30", "60", "90", "120", "144"}),
					"--add-combo=Resolusi Maks (px)", "--combo-values="+prioritizeCombo(curRes, []string{"Asli", "1920", "1280", "1024", "720"}),
					"--add-combo=Bitrate (Mbps)", "--combo-values="+prioritizeCombo(curBr, []string{"8", "4", "12", "16", "24", "2"}),
					"--add-combo=Audio HP -> Laptop", "--combo-values="+prioritizeCombo(curAud, []string{"Aktif", "Tanpa Audio"}),
					"--add-combo=Codec Video", "--combo-values="+prioritizeCombo(curCodec, []string{"h264", "h265"}),
					"--add-combo=Layar HP saat Mirror", "--combo-values="+prioritizeCombo(curScreen, []string{"Tetap Nyala", "Matikan Layar HP"}),
				).Output()
				if err != nil {
					return
				}
				parts := strings.Split(strings.TrimRight(string(out), "\r\n"), "|")
				next := cur
				if len(parts) > 0 && strings.TrimSpace(parts[0]) != "" {
					v := strings.TrimSpace(parts[0])
					if strings.EqualFold(v, "Auto") {
						next.FPS = 0
					} else if n, err := strconv.Atoi(v); err == nil {
						next.FPS = n
					}
				}
				if len(parts) > 1 && strings.TrimSpace(parts[1]) != "" {
					v := strings.TrimSpace(parts[1])
					if strings.EqualFold(v, "Asli") {
						next.MaxSize = 0
					} else if n, err := strconv.Atoi(v); err == nil {
						next.MaxSize = n
					}
				}
				if len(parts) > 2 && strings.TrimSpace(parts[2]) != "" {
					if n, err := strconv.Atoi(strings.TrimSpace(parts[2])); err == nil {
						next.BitrateMbps = n
					}
				}
				if len(parts) > 3 && strings.TrimSpace(parts[3]) != "" {
					next.Audio = strings.TrimSpace(parts[3]) != "Tanpa Audio"
				}
				if len(parts) > 4 && strings.TrimSpace(parts[4]) != "" {
					next.Codec = strings.ToLower(strings.TrimSpace(parts[4]))
				}
				if len(parts) > 5 && strings.TrimSpace(parts[5]) != "" {
					next.TurnScreenOff = strings.TrimSpace(parts[5]) == "Matikan Layar HP"
				}

				saved := mirrorStore.Set(next)
				desktopCap.ApplyMirrorConfig(saved)
				scrcpyMgr.SetConfig(saved)
				if b, err := (protocol.ControlMessage{
					Op:     protocol.ControlOpUpdateMirrorConfig,
					Mirror: &saved,
				}).Encode(); err == nil && tunnel != nil {
					tunnel.BroadcastFrame(protocol.PacketTypeControl, protocol.FlagDirLinuxToPhone, b)
				}
				if desktopCap.IsRunning() {
					desktopCap.Stop()
					_ = desktopCap.Start(ctx)
					if tunnel != nil {
						if p := tunnel.PrimarySession(); p != nil {
							tunnel.ReplayCodecConfig(p)
						}
					}
				}
				if scrcpyMgr.IsRunning() {
					uiMu.Lock()
					ser := adbSerial
					uiMu.Unlock()
					_ = scrcpyMgr.RestartIfRunning(ser, saved)
				}
				setActivity("Setelan Mirror disimpan: " + formatMirrorSummary(saved))
			},
			OnQuit: func() {
				cancel()
			},
		})

		uiMu.Lock()
		guiCtrl = ctrl
		uiMu.Unlock()

		refreshConnBadge()
		_, initialRows := listSharedFiles()
		ctrl.SetFileList(initialRows)
		if cur := clipMgr.Current(); cur != "" {
			ctrl.SetClipboardPreview(cur)
		}

		sigCh := make(chan os.Signal, 1)
		signal.Notify(sigCh, os.Interrupt, syscall.SIGTERM)
		go func() {
			<-sigCh
			cancel()
			ctrl.Quit()
		}()

		ctrl.Run()
	} else {
		log.Printf("PhoneDrop headless daemon listening on %s (dest: %s, clip: %s, input: %s)",
			tunnel.Addr(), *destDir, clipMgr.BackendName(), injector.EngineName())
		sigCh := make(chan os.Signal, 1)
		signal.Notify(sigCh, os.Interrupt, syscall.SIGTERM)
		select {
		case <-sigCh:
		case <-ctx.Done():
		}
	}

	desktopCap.Stop()
	scrcpyMgr.Stop()
	_ = tunnel.Close()
}

func runCLISend(ctx context.Context, addr, destDir, filePath string, printer *ui.CLIProgressPrinter) error {
	eng, err := transfer.NewEngine(destDir, nil)
	if err != nil {
		return err
	}
	sess, err := transport.DialSession(ctx, addr, transport.Handlers{
		OnFileDone: func(s *transport.Session, flags uint8, done protocol.FileDonePayload) {
			eng.HandleFileDone(s, flags, done)
		},
	})
	if err != nil {
		return fmt.Errorf("connect %s: %w", addr, err)
	}
	defer sess.Close()

	return eng.SendFile(ctx, sess, filePath, func(rep transfer.ProgressReport) {
		printer.Print(rep)
	})
}
