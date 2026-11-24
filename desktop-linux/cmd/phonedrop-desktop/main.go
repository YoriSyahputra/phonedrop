package main

import (
	"context"
	"flag"
	"fmt"
	"log"
	"os"
	"os/signal"
	"path/filepath"
	"runtime/debug"
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
	headless := flag.Bool("headless", false, "Run headless daemon without GTK3 GUI")
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
	var activeXID uint64
	var phonePlayer *mirror.MPVPlayer
	var phoneReceiver *mirror.PhoneReceiver
	var adbSerial string
	var adbCancel context.CancelFunc

	clipMgr := clipboard.NewManager()
	desktopCap := mirror.NewDesktopCapture(mirror.CaptureConfig{PreferVAAPI: true}, nil)
	capW, capH := desktopCap.ScreenSize()
	injector := input.NewInjector(capW, capH)

	fileEngine, err := transfer.NewEngine(*destDir, func(rep transfer.ProgressReport) {
		cliPrinter.Print(rep)
		uiMu.Lock()
		ctrl := guiCtrl
		uiMu.Unlock()
		if ctrl != nil {
			ctrl.UpdateProgress(rep)
		}
	})
	if err != nil {
		log.Fatalf("init transfer engine: %v", err)
	}

	var tunnel *transport.TunnelServer

	updateStatus := func(msg string) {
		log.Println(msg)
		uiMu.Lock()
		ctrl := guiCtrl
		uiMu.Unlock()
		if ctrl != nil {
			ctrl.SetStatus(msg)
		}
	}

	handlers := transport.Handlers{
		OnConnect: func(s *transport.Session) {
			updateStatus(fmt.Sprintf("Connected [%s]: %s", s.Mode, s.RemoteAddr))
		},
		OnDisconnect: func(s *transport.Session, derr error) {
			updateStatus(fmt.Sprintf("Disconnected: %s (listening :%d)", s.RemoteAddr, *port))
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
		OnVideoFrame: func(_ *transport.Session, flags uint8, vid protocol.VideoFramePayload) {
			uiMu.Lock()
			pr := phoneReceiver
			uiMu.Unlock()
			if pr != nil {
				pr.HandleVideoFrame(flags, vid)
			}
		},
		OnInputEvent: func(_ *transport.Session, ev protocol.InputEventPayload) {
			injector.Execute(ev)
		},
	}

	listenAddr := fmt.Sprintf(":%d", *port)
	tunnel = transport.NewTunnelServer(listenAddr, handlers)
	if _, err := tunnel.Listen(); err != nil {
		log.Fatalf("listen %s: %v", listenAddr, err)
	}

	// Re-bind DesktopCapture sink now that tunnel exists.
	desktopCap = mirror.NewDesktopCapture(mirror.CaptureConfig{PreferVAAPI: true}, func(flags uint8, hdr protocol.VideoFrameHeader, nal []byte) {
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
			updateStatus(fmt.Sprintf("ADB [%s] %s", ev.State, strings.TrimSpace(ev.Serial+" "+ev.Err)))
		})
		go watcher.Watch(ctx)
	}

	go clipMgr.Watch(ctx, 700*time.Millisecond, func(clip protocol.ClipPayload) {
		b, err := clip.Encode()
		if err == nil {
			tunnel.BroadcastFrame(protocol.PacketTypeClip, protocol.FlagDirLinuxToPhone, b)
		}
	})

	useGUI := !*headless && (os.Getenv("DISPLAY") != "" || os.Getenv("WAYLAND_DISPLAY") != "")
	if useGUI {
		ctrl := ui.NewController(ui.Callbacks{
			OnSendFilePath: func(path string) {
				sess := tunnel.PrimarySession()
				if sess == nil {
					updateStatus("No connected phone to receive file")
					return
				}
				if err := fileEngine.SendFile(ctx, sess, path, nil); err != nil {
					updateStatus(fmt.Sprintf("Send failed: %v", err))
				}
			},
			OnSyncClipboard: func() {
				text := clipMgr.Current()
				if text == "" {
					return
				}
				b, err := clipboard.BuildClipPacket(text)
				if err == nil {
					tunnel.BroadcastFrame(protocol.PacketTypeClip, protocol.FlagDirLinuxToPhone, b)
				}
			},
			OnMirrorCanvasReady: func(xid uint64) {
				uiMu.Lock()
				activeXID = xid
				player := mirror.NewMPVPlayer(xid)
				phonePlayer = player
				phoneReceiver = mirror.NewPhoneReceiver(player, nil)
				uiMu.Unlock()
			},
			OnTogglePhoneMirror: func(active bool) {
				uiMu.Lock()
				if active {
					if phonePlayer == nil {
						phonePlayer = mirror.NewMPVPlayer(activeXID)
						phoneReceiver = mirror.NewPhoneReceiver(phonePlayer, nil)
					}
					player := phonePlayer
					ser := adbSerial
					hasSession := tunnel.PrimarySession() != nil
					if !hasSession && ser != "" {
						adbCtx, c := context.WithCancel(ctx)
						adbCancel = c
						go func() {
							_ = mirror.StartADBScreenStream(adbCtx, ser, player)
						}()
					} else {
						_ = player.Start()
					}
				} else {
					if adbCancel != nil {
						adbCancel()
						adbCancel = nil
					}
					if phonePlayer != nil {
						phonePlayer.Stop()
					}
				}
				uiMu.Unlock()
			},
			OnToggleLinuxStream: func(active bool) {
				if active {
					_ = desktopCap.Start(ctx)
					updateStatus("Streaming Linux Desktop (H.264) → Phone")
				} else {
					desktopCap.Stop()
					updateStatus("Stopped Linux Desktop stream")
				}
			},
			OnMirrorPointer: func(evType uint8, btn uint8, nx, ny int32) {
				ev := protocol.InputEventPayload{
					EventType:      evType,
					ActionOrButton: btn,
					X:              nx,
					Y:              ny,
					Arg1:           2,
				}
				if sess := tunnel.PrimarySession(); sess != nil {
					if b, err := ev.Encode(); err == nil {
						_ = sess.WriteFrame(protocol.PacketTypeInputEvent, protocol.FlagDirLinuxToPhone, b)
					}
					return
				}
				uiMu.Lock()
				ser := adbSerial
				uiMu.Unlock()
				if ser != "" {
					go mirror.ForwardInputToADB(ser, ev, 1080, 2400)
				}
			},
			OnQuit: func() {
				cancel()
			},
		})

		uiMu.Lock()
		guiCtrl = ctrl
		uiMu.Unlock()

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
	uiMu.Lock()
	if phonePlayer != nil {
		phonePlayer.Stop()
	}
	uiMu.Unlock()
	_ = tunnel.Close()
}

func runCLISend(ctx context.Context, addr, destDir, filePath string, printer *ui.CLIProgressPrinter) error {
	var eng *transfer.Engine
	var err error
	eng, err = transfer.NewEngine(destDir, nil)
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
