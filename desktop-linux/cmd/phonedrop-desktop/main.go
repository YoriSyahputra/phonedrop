package main

import (
	"context"
	"flag"
	"fmt"
	"log"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"runtime/debug"
	"sort"
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

	if *destDir == "" {
		home, err := os.UserHomeDir()
		if err != nil {
			log.Fatalf("resolve home dir: %v", err)
		}
		*destDir = filepath.Join(home, "Downloads", "PhoneDrop")
	}

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

	listSharedFiles := func() ([]protocol.RemoteFileEntry, []ui.FileRow) {
		entries, err := os.ReadDir(*destDir)
		if err != nil {
			return nil, nil
		}
		type item struct {
			name string
			size int64
			mod  int64
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
				name: ent.Name(),
				size: info.Size(),
				mod:  info.ModTime().UnixMilli(),
			})
		}
		sort.Slice(items, func(i, j int) bool {
			return items[i].mod > items[j].mod
		})

		protoFiles := make([]protocol.RemoteFileEntry, len(items))
		uiRows := make([]ui.FileRow, len(items))
		for i, it := range items {
			protoFiles[i] = protocol.RemoteFileEntry{Name: it.name, Size: it.size, ModMs: it.mod}
			uiRows[i] = ui.FileRow{Name: it.name, Size: it.size, ModMs: it.mod}
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

	updateStatus := func(msg string) {
		log.Println(msg)
		uiMu.Lock()
		ctrl := guiCtrl
		uiMu.Unlock()
		if ctrl != nil {
			connected := tunnel != nil && len(tunnel.Sessions()) > 0
			ctrl.SetStatus(msg, connected)
		}
	}

	fileEngine, err := transfer.NewEngine(*destDir, func(rep transfer.ProgressReport) {
		cliPrinter.Print(rep)
		uiMu.Lock()
		ctrl := guiCtrl
		uiMu.Unlock()
		if ctrl != nil {
			ctrl.UpdateProgress(rep)
		}
		if rep.Verified {
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
			updateStatus(fmt.Sprintf("Terhubung • %s", s.Mode))
			go func() {
				syncSharedFiles(s)
				if cur := clipMgr.Current(); cur != "" {
					if b, err := clipboard.BuildClipPacket(cur); err == nil {
						_ = s.WriteFrame(protocol.PacketTypeClip, protocol.FlagDirLinuxToPhone, b)
					}
				}
			}()
		},
		OnDisconnect: func(s *transport.Session, _ error) {
			if len(tunnel.Sessions()) == 0 {
				desktopCap.Stop()
				uiMu.Lock()
				ctrl := guiCtrl
				uiMu.Unlock()
				if ctrl != nil {
					ctrl.SetLinuxStreamActive(false)
				}
				updateStatus(fmt.Sprintf("Siaga :%d (USB / Wi-Fi)", *port))
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
				syncSharedFiles(nil)
			case protocol.ControlOpStartDesktopStream, protocol.ControlOpRequestKeyframe:
				_ = desktopCap.Start(ctx)
				tunnel.ReplayCodecConfig(s)
				uiMu.Lock()
				ctrl := guiCtrl
				uiMu.Unlock()
				if ctrl != nil {
					ctrl.SetLinuxStreamActive(true)
				}
				updateStatus("Streaming Layar Laptop -> HP")
			case protocol.ControlOpStopDesktopStream:
				desktopCap.Stop()
				uiMu.Lock()
				ctrl := guiCtrl
				uiMu.Unlock()
				if ctrl != nil {
					ctrl.SetLinuxStreamActive(false)
				}
				updateStatus("Terhubung • Layar Laptop berhenti")
			case protocol.ControlOpStartPhoneScrcpy:
				uiMu.Lock()
				ser := adbSerial
				ctrl := guiCtrl
				uiMu.Unlock()
				if err := scrcpyMgr.Start(ser); err != nil {
					sendControlErr(s, err.Error())
					updateStatus(err.Error())
					return
				}
				if ctrl != nil {
					ctrl.SetPhoneMirrorActive(true)
				}
				updateStatus("scrcpy Aktif (Layar HP -> Laptop)")
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
			if len(tunnel.Sessions()) == 0 {
				updateStatus(fmt.Sprintf("ADB [%s] %s", ev.State, strings.TrimSpace(ev.Serial)))
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
					updateStatus("Belum ada HP terhubung")
					return
				}
				if err := fileEngine.SendFile(ctx, sess, path, nil); err != nil {
					updateStatus(fmt.Sprintf("Kirim gagal: %v", err))
				}
			},
			OnSendSharedFile: func(name string) {
				sess := tunnel.PrimarySession()
				if sess == nil {
					updateStatus("Belum ada HP terhubung")
					return
				}
				target := filepath.Join(*destDir, protocol.SanitizeFileName(name))
				if err := fileEngine.SendFile(ctx, sess, target, nil); err != nil {
					updateStatus(fmt.Sprintf("Kirim gagal: %v", err))
				}
			},
			OnOpenFile: func(name string) {
				target := filepath.Join(*destDir, protocol.SanitizeFileName(name))
				_ = exec.Command("xdg-open", target).Start()
			},
			OnDeleteSharedFile: func(name string) {
				target := filepath.Join(*destDir, protocol.SanitizeFileName(name))
				if err := os.Remove(target); err == nil {
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
					updateStatus("Clipboard dikirim ke HP")
				}
			},
			OnTogglePhoneMirror: func(active bool) {
				uiMu.Lock()
				ser := adbSerial
				c := guiCtrl
				uiMu.Unlock()
				if active {
					if err := scrcpyMgr.Start(ser); err != nil {
						updateStatus(err.Error())
						if c != nil {
							c.SetPhoneMirrorActive(false)
						}
						return
					}
					updateStatus("scrcpy Aktif (Kontrol HP dari Laptop)")
				} else {
					scrcpyMgr.Stop()
					updateStatus("scrcpy dihentikan")
				}
			},
			OnToggleLinuxStream: func(active bool) {
				if active {
					_ = desktopCap.Start(ctx)
					updateStatus("Streaming Layar Laptop -> HP")
				} else {
					desktopCap.Stop()
					updateStatus("Stream Layar Laptop berhenti")
				}
			},
			OnQuit: func() {
				cancel()
			},
		})

		uiMu.Lock()
		guiCtrl = ctrl
		uiMu.Unlock()

		// Populate initial Ruang File Bersama & clipboard preview.
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
		log.Printf("PhoneDrop v2 headless daemon listening on %s (dest: %s, clip: %s, input: %s)",
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
