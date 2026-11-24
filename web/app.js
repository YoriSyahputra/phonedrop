(() => {
  const connBadge = document.getElementById("conn-badge");
  const clipInput = document.getElementById("clipboard-input");
  const clipTime = document.getElementById("clip-time");
  const btnSendClip = document.getElementById("btn-send-clip");
  const btnCopyLocal = document.getElementById("btn-copy-local");
  const fileInput = document.getElementById("file-input");
  const dropzone = document.getElementById("dropzone");
  const autoQrCheck = document.getElementById("auto-qr-check");
  const progressWrapper = document.getElementById("progress-wrapper");
  const progressLabel = document.getElementById("progress-label");
  const progressPercent = document.getElementById("progress-percent");
  const progressBarFill = document.getElementById("progress-bar-fill");
  const fileListEl = document.getElementById("file-list");
  const btnRefreshFiles = document.getElementById("btn-refresh-files");
  const qrModal = document.getElementById("qr-modal");
  const qrImage = document.getElementById("qr-image");
  const qrFilename = document.getElementById("qr-filename");
  const btnCloseQr = document.getElementById("btn-close-qr");
  const toastEl = document.getElementById("toast");
  const qrTabs = document.getElementById("qr-file-tabs");
  const btnPwaInstall = document.getElementById("btn-pwa-install");
  const btnPasteSend = document.getElementById("btn-paste-send");
  const autoSendWrap = document.getElementById("auto-send-wrap");
  const autoSendCheck = document.getElementById("auto-send-check");
  const btnZipAll = document.getElementById("btn-zip-all");
  const btnQrAll = document.getElementById("btn-qr-all");
  const directSendCheck = document.getElementById("direct-send-check");
  const directStatusDot = document.getElementById("direct-status-dot");
  const directStatusText = document.getElementById("direct-status-text");
  const btnApkModal = document.getElementById("btn-apk-modal");
  const btnNativeSettings = document.getElementById("btn-native-settings");
  const qrHint = document.getElementById("qr-hint");
  const qrDirectLink = document.getElementById("qr-direct-link");

  const isNativeAPK = typeof window.PhoneDropNative !== "undefined";
  const sessionId = "pd-" + Math.random().toString(36).slice(2, 10);
  let ws = null;
  let toastTimer = null;
  let pendingAutoCopyText = null;
  let lastSyncedText = ""; // Teks terakhir yang sudah sinkron di kedua sisi (anti kirim-balik)
  let isFirstConnect = true;
  let deferredPrompt = null;

  function showToast(msg, duration = 2600) {
    toastEl.textContent = msg;
    toastEl.classList.remove("hidden");
    if (toastTimer) clearTimeout(toastTimer);
    toastTimer = setTimeout(() => {
      toastEl.classList.add("hidden");
    }, duration);
  }

  function formatTime(ts) {
    const d = ts ? new Date(ts) : new Date();
    return d.toLocaleTimeString("id-ID", {
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
    });
  }

  function formatBytes(bytes) {
    if (bytes === 0) return "0 B";
    const k = 1024;
    const sizes = ["B", "KB", "MB", "GB"];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + " " + sizes[i];
  }

  function openQRModal(files) {
    if (!files || files.length === 0) return;

    // Normalisasi argumen jika yang dikirim string tunggal
    const fileList = Array.isArray(files) ? files : [files];

    qrTabs.innerHTML = "";
    if (fileList.length > 1) {
      qrTabs.classList.remove("hidden");

      // Tab 1: ZIP untuk semua file sekaligus
      const zipBtn = document.createElement("button");
      zipBtn.type = "button";
      zipBtn.className = "qr-tab-btn active";
      zipBtn.textContent = `📦 Semua File (${fileList.length} .zip)`;
      zipBtn.title = "Unduh semua file dalam 1 arsip ZIP";
      zipBtn.addEventListener("click", () => {
        document
          .querySelectorAll(".qr-tab-btn")
          .forEach((b) => b.classList.remove("active"));
        zipBtn.classList.add("active");
        showQRForZip(fileList);
      });
      qrTabs.appendChild(zipBtn);

      fileList.forEach((fname) => {
        const btn = document.createElement("button");
        btn.type = "button";
        btn.className = "qr-tab-btn";
        btn.textContent = fname;
        btn.title = fname;
        btn.addEventListener("click", () => {
          document
            .querySelectorAll(".qr-tab-btn")
            .forEach((b) => b.classList.remove("active"));
          btn.classList.add("active");
          showQRForFile(fname);
        });
        qrTabs.appendChild(btn);
      });

      // Default menampilkan QR untuk ZIP semua file
      showQRForZip(fileList);
    } else {
      qrTabs.classList.add("hidden");
      showQRForFile(fileList[0]);
    }

    qrModal.classList.remove("hidden");
  }

  function showQRForFile(filename) {
    if (qrDirectLink) qrDirectLink.classList.add("hidden");
    if (qrHint) {
      qrHint.textContent =
        "Arahkan kamera HP / Google Lens ke QR ini. File akan langsung terunduh via Wi-Fi lokal tanpa memakan kuota data.";
    }
    qrFilename.textContent = filename;
    qrImage.src = `/api/qr?name=${encodeURIComponent(filename)}&t=${Date.now()}`;
  }

  function showQRForZip(files) {
    if (qrDirectLink) qrDirectLink.classList.add("hidden");
    if (qrHint) {
      qrHint.textContent =
        "Arahkan kamera HP / Google Lens ke QR ini. File akan langsung terunduh via Wi-Fi lokal tanpa memakan kuota data.";
    }
    if (files && files.length > 0) {
      qrFilename.textContent = `Arsip ZIP (${files.length} file)`;
      qrImage.src = `/api/qr-zip?files=${encodeURIComponent(files.join(","))}&t=${Date.now()}`;
    } else {
      qrFilename.textContent = "Arsip ZIP (Semua File Ruang Bersama)";
      qrImage.src = `/api/qr-zip?t=${Date.now()}`;
    }
  }

  if (isNativeAPK && btnNativeSettings) {
    btnNativeSettings.classList.remove("hidden");
    btnNativeSettings.addEventListener("click", () => {
      window.PhoneDropNative.openSettings();
    });
  }

  if (btnApkModal) {
    if (isNativeAPK) {
      btnApkModal.textContent = "⚙️ Pengaturan & Riwayat";
      btnApkModal.title = "Buka Pengaturan Background Service, Server, & Riwayat Direct Send";
    }
    btnApkModal.addEventListener("click", () => {
      if (isNativeAPK) {
        window.PhoneDropNative.openSettings();
        return;
      }
      qrTabs.classList.add("hidden");
      qrFilename.textContent = "PhoneDrop.apk • Aplikasi Android & Background Service";
      qrImage.src = `/api/qr-apk?t=${Date.now()}`;
      if (qrDirectLink) qrDirectLink.classList.remove("hidden");
      if (qrHint) {
        qrHint.textContent =
          "Scan QR ini dari HP (atau tekan tombol Unduh di bawah) untuk memasang aplikasi PhoneDrop.apk. Setelah aktif, file dari Laptop otomatis masuk ke Downloads/PhoneDrop meski layar HP mati!";
      }
      qrModal.classList.remove("hidden");
    });
  }

  btnCloseQr.addEventListener("click", () => {
    qrModal.classList.add("hidden");
    qrImage.src = "";
  });

  qrModal.addEventListener("click", (e) => {
    if (e.target === qrModal) {
      qrModal.classList.add("hidden");
      qrImage.src = "";
    }
  });

  async function writeToSystemClipboard(text) {
    if (!text) return false;
    if (isNativeAPK && typeof window.PhoneDropNative.writeClipboard === "function") {
      try {
        if (window.PhoneDropNative.writeClipboard(text)) return true;
      } catch (_) {}
    }
    if (navigator.clipboard && navigator.clipboard.writeText) {
      try {
        await navigator.clipboard.writeText(text);
        return true;
      } catch (_) {}
    }
    try {
      const temp = document.createElement("textarea");
      temp.value = text;
      temp.setAttribute("readonly", "");
      temp.style.position = "fixed";
      temp.style.top = "-9999px";
      temp.style.opacity = "0";
      document.body.appendChild(temp);
      temp.focus();
      temp.select();
      temp.setSelectionRange(0, temp.value.length);
      const ok = document.execCommand("copy");
      document.body.removeChild(temp);
      return ok;
    } catch (_) {
      return false;
    }
  }

  async function attemptAutoCopy(text) {
    const success = await writeToSystemClipboard(text);
    if (success) {
      pendingAutoCopyText = null;
      lastSyncedText = text;
      showToast("Otomatis disalin ke Clipboard perangkat!");
    } else {
      // Biasanya gagal karena app sedang di latar belakang (dokumen tidak fokus).
      // Akan dicoba lagi otomatis saat app dibuka kembali (lihat handleAppResume).
      pendingAutoCopyText = text;
      if (document.visibilityState === "visible" && document.hasFocus()) {
        showToast("Teks masuk! Ketuk layar sekali untuk menyalin otomatis", 4000);
      }
    }
  }

  // Fallback terakhir: ketukan layar = user gesture yang selalu diizinkan browser.
  document.addEventListener("pointerdown", async () => {
    if (pendingAutoCopyText) {
      const textToCopy = pendingAutoCopyText;
      pendingAutoCopyText = null;
      if (await writeToSystemClipboard(textToCopy)) {
        lastSyncedText = textToCopy;
        showToast("Otomatis disalin ke Clipboard perangkat!");
      }
    }
  });

  // --- Clipboard via HTTPS / Native APK Bridge ---
  const canReadClipboard =
    isNativeAPK ||
    (window.isSecureContext &&
      !!navigator.clipboard &&
      typeof navigator.clipboard.readText === "function");
  const AUTO_SEND_KEY = "phonedrop-auto-send-on-open";

  function sendClipboardText(text) {
    if (!ws || ws.readyState !== WebSocket.OPEN) {
      showToast("Koneksi belum aktif");
      return false;
    }
    pendingAutoCopyText = null;
    lastSyncedText = text;
    ws.send(
      JSON.stringify({
        type: "clipboard",
        content: text,
        timestamp: Date.now(),
      }),
    );
    clipTime.textContent = "Terkirim " + formatTime(Date.now());
    return true;
  }

  async function readSystemClipboard() {
    if (!canReadClipboard) return null;
    if (isNativeAPK && typeof window.PhoneDropNative.readClipboard === "function") {
      try {
        return window.PhoneDropNative.readClipboard() || "";
      } catch (_) {}
    }
    try {
      return await navigator.clipboard.readText();
    } catch (_) {
      return null; // Izin ditolak / dokumen tidak fokus
    }
  }

  // Dipanggil saat app kembali ke depan: selesaikan salinan tertunda (Laptop → HP),
  // lalu (opsional) kirim clipboard HP yang baru ke Laptop.
  let resumeBusy = false;
  async function handleAppResume() {
    if (resumeBusy || document.visibilityState !== "visible") return;
    resumeBusy = true;
    try {
      // Beri waktu browser memberikan fokus ke dokumen setelah app dibuka.
      await new Promise((r) => setTimeout(r, 250));

      if (pendingAutoCopyText) {
        const text = pendingAutoCopyText;
        if (await writeToSystemClipboard(text)) {
          pendingAutoCopyText = null;
          lastSyncedText = text;
          showToast("Teks dari Laptop otomatis disalin!");
          return; // Clipboard HP baru saja diisi dari Laptop, jangan kirim balik
        }
      }

      if (autoSendCheck && autoSendCheck.checked) {
        const text = await readSystemClipboard();
        if (text && text.trim() && text !== lastSyncedText && text !== clipInput.value) {
          clipInput.value = text;
          if (sendClipboardText(text)) {
            showToast("Clipboard HP otomatis dikirim ke Laptop");
          }
        }
      }
    } finally {
      resumeBusy = false;
    }
  }

  document.addEventListener("visibilitychange", handleAppResume);
  window.addEventListener("focus", handleAppResume);

  if (canReadClipboard) {
    btnPasteSend.classList.remove("hidden");
    autoSendWrap.classList.remove("hidden");
    autoSendCheck.checked = localStorage.getItem(AUTO_SEND_KEY) === "1";

    btnPasteSend.addEventListener("click", async () => {
      const text = await readSystemClipboard();
      if (text === null) {
        return showToast("Izin clipboard ditolak. Aktifkan di pengaturan situs", 4000);
      }
      if (!text.trim()) return showToast("Clipboard HP kosong");
      clipInput.value = text;
      if (sendClipboardText(text)) {
        showToast("Clipboard HP terkirim ke Laptop");
      }
    });

    autoSendCheck.addEventListener("change", async () => {
      localStorage.setItem(AUTO_SEND_KEY, autoSendCheck.checked ? "1" : "0");
      if (autoSendCheck.checked) {
        // Minta izin sekarang (masih dalam user gesture) agar prompt tidak muncul tiba-tiba nanti.
        const probe = await readSystemClipboard();
        if (probe === null) {
          autoSendCheck.checked = false;
          localStorage.setItem(AUTO_SEND_KEY, "0");
          return showToast("Izin clipboard ditolak", 3500);
        }
        lastSyncedText = probe; // Isi saat ini dianggap sudah sinkron
        showToast("Aktif: clipboard HP dikirim saat app dibuka");
      }
    });
  }

  function updateDirectStatusUI(apkClients, adbConnected = false) {
    if (!directStatusText || !directStatusDot) return;
    if (apkClients > 0) {
      directStatusDot.className = "status-dot dot-active";
      directStatusText.textContent = `Direct Send: APK HP Aktif (${apkClients} perangkat Zero-Click)`;
    } else if (adbConnected) {
      directStatusDot.className = "status-dot dot-active";
      directStatusText.textContent = "Direct Send: ADB Terhubung (Zero-Click)";
    } else {
      directStatusDot.className = "status-dot dot-idle";
      directStatusText.textContent =
        "Direct Send: Mode Browser • Pasang APK untuk background";
    }
  }

  async function fetchDirectStatus() {
    try {
      const r = await fetch("/api/direct-send/status");
      if (!r.ok) return;
      const st = await r.json();
      updateDirectStatusUI(st.apk_clients || 0, !!st.adb_connected);
    } catch (_) {}
  }

  function triggerZeroClickDownload(files) {
    if (!files || files.length === 0) return;
    files.forEach((fname, idx) => {
      setTimeout(() => {
        const a = document.createElement("a");
        a.href = `/api/download?name=${encodeURIComponent(fname)}`;
        a.setAttribute("download", fname);
        a.style.display = "none";
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
      }, idx * 450);
    });
    showToast(`⚡ Direct Send: Mengunduh otomatis ${files.length} file...`, 3500);
    fetch(`/api/direct-send/ack?files=${encodeURIComponent(files.join(","))}`, {
      method: "POST",
    }).catch(() => {});
  }

  async function pushFileDirect(fname) {
    try {
      const r = await fetch(
        `/api/direct-send?name=${encodeURIComponent(fname)}&sender_id=${encodeURIComponent(sessionId)}`,
        { method: "POST" },
      );
      if (!r.ok) throw new Error();
      const resp = await r.json();
      if (resp.mode === "apk") {
        showToast(`⚡ "${fname}" langsung dikirim ke Downloads/PhoneDrop di HP!`);
      } else if (resp.mode === "adb") {
        showToast(`⚡ "${fname}" dikirim via ADB ke Downloads/PhoneDrop!`);
      } else {
        showToast(`📲 "${fname}" didorong ke browser HP!`);
      }
    } catch (_) {
      showToast("Gagal mendorong file ke HP");
    }
  }

  function connectWS() {
    const proto = window.location.protocol === "https:" ? "wss:" : "ws:";
    ws = new WebSocket(`${proto}//${window.location.host}/ws`);

    ws.onopen = () => {
      connBadge.textContent = "Terhubung (LAN)";
      connBadge.className = "badge badge-connected";
      fetchDirectStatus();
    };

    ws.onmessage = async (event) => {
      try {
        const data = JSON.parse(event.data);
        if (data.type === "clipboard") {
          const newText = data.content || "";
          const isChanged = clipInput.value !== newText;
          clipInput.value = newText;
          clipTime.textContent = "Sinkron " + formatTime(data.timestamp);
          if (newText && (!isFirstConnect || isChanged)) {
            await attemptAutoCopy(newText);
          }
          isFirstConnect = false;
        } else if (data.type === "files_updated") {
          loadFiles();
        } else if (data.type === "direct_status") {
          updateDirectStatusUI(data.apk_clients || 0, false);
        } else if (data.type === "direct_send") {
          if (
            !isNativeAPK &&
            data.sender_id !== sessionId &&
            data.files &&
            data.files.length > 0
          ) {
            triggerZeroClickDownload(data.files);
          }
        }
      } catch (err) {
        console.error("Gagal memproses pesan WS:", err);
      }
    };

    ws.onclose = () => {
      connBadge.textContent = "Menghubungkan...";
      connBadge.className = "badge badge-disconnected";
      setTimeout(connectWS, 2500);
    };

    ws.onerror = () => ws.close();
  }

  btnSendClip.addEventListener("click", () => {
    const text = clipInput.value;
    if (!text) return showToast("Kotak teks masih kosong");
    if (sendClipboardText(text)) {
      showToast("Teks dikirim ke perangkat lain");
    }
  });

  btnCopyLocal.addEventListener("click", async () => {
    const text = clipInput.value;
    if (!text) return showToast("Tidak ada teks untuk disalin");
    if (await writeToSystemClipboard(text)) {
      pendingAutoCopyText = null;
      showToast("Disalin ke Clipboard perangkat ini");
    } else {
      showToast("Gagal menyalin teks");
    }
  });

  function uploadFiles(files) {
    if (!files || files.length === 0) return;

    const formData = new FormData();
    for (let i = 0; i < files.length; i++) {
      formData.append("files", files[i]);
    }

    const xhr = new XMLHttpRequest();
    const isDirect = directSendCheck && directSendCheck.checked ? "1" : "0";
    xhr.open(
      "POST",
      `/api/upload?direct=${isDirect}&sender_id=${encodeURIComponent(sessionId)}`,
      true,
    );

    progressWrapper.classList.remove("hidden");
    progressBarFill.style.width = "0%";
    progressPercent.textContent = "0%";
    progressLabel.textContent = `Menyiapkan ${files.length} file...`;

    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable) {
        const pct = Math.round((e.loaded / e.total) * 100);
        progressBarFill.style.width = pct + "%";
        progressPercent.textContent = pct + "%";
      }
    };

    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        fileInput.value = "";

        loadFiles();

        try {
          const resp = JSON.parse(xhr.responseText);
          if (resp.files && resp.files.length > 0) {
            if (resp.direct_mode === "apk") {
              showToast(
                `⚡ ${resp.files.length} file langsung masuk ke Downloads/PhoneDrop di HP!`,
                3600,
              );
            } else if (resp.direct_mode === "adb") {
              showToast(
                `⚡ ${resp.files.length} file dikirim via ADB ke Downloads/PhoneDrop!`,
                3600,
              );
            } else if (resp.direct_mode === "pwa") {
              showToast(
                `📲 ${resp.files.length} file didorong otomatis ke HP!`,
                3200,
              );
            } else {
              showToast(`${resp.files.length} file berhasil diunggah`);
            }
            if (autoQrCheck && autoQrCheck.checked) {
              openQRModal(resp.files);
            }
          }
        } catch (_) {
          showToast("File berhasil disimpan");
        }
      } else {
        showToast("Gagal memproses file");
      }
      setTimeout(() => progressWrapper.classList.add("hidden"), 800);
    };
    xhr.onerror = () => {
      showToast("Terjadi kesalahan koneksi lokal");
      progressWrapper.classList.add("hidden");
    };

    xhr.send(formData);
  }

  fileInput.addEventListener("change", (e) => uploadFiles(e.target.files));
  dropzone.addEventListener("dragover", (e) => {
    e.preventDefault();
    dropzone.classList.add("dragover");
  });
  dropzone.addEventListener("dragleave", () =>
    dropzone.classList.remove("dragover"),
  );
  dropzone.addEventListener("drop", (e) => {
    e.preventDefault();
    dropzone.classList.remove("dragover");
    uploadFiles(e.dataTransfer.files);
  });

  async function loadFiles() {
    try {
      const res = await fetch("/api/files");
      if (!res.ok) throw new Error("Gagal mengambil daftar file");
      const files = await res.json();

      if (files && files.length >= 2) {
        if (btnZipAll) btnZipAll.classList.remove("hidden");
        if (btnQrAll) btnQrAll.classList.remove("hidden");
      } else {
        if (btnZipAll) btnZipAll.classList.add("hidden");
        if (btnQrAll) btnQrAll.classList.add("hidden");
      }

      fileListEl.innerHTML = "";
      if (!files || files.length === 0) {
        fileListEl.innerHTML =
          '<li class="empty-state">Belum ada file di Ruang Bersama</li>';
        return;
      }

      files.forEach((f) => {
        const li = document.createElement("li");
        li.className = "file-item";

        const meta = document.createElement("div");
        meta.className = "file-meta";

        const nameEl = document.createElement("div");
        nameEl.className = "file-name";
        nameEl.textContent = f.name;

        const sizeEl = document.createElement("div");
        sizeEl.className = "file-size";
        sizeEl.textContent = formatBytes(f.size);

        meta.appendChild(nameEl);
        meta.appendChild(sizeEl);

        const actions = document.createElement("div");
        actions.className = "file-actions";

        // 0. Tombol Direct Send ke HP (Zero-Click)
        const directBtn = document.createElement("button");
        directBtn.type = "button";
        directBtn.className = "btn-direct";
        directBtn.textContent = "Kirim HP";
        directBtn.title = "Direct Send langsung ke Downloads/PhoneDrop di HP";
        directBtn.addEventListener("click", () => pushFileDirect(f.name));

        // 1. Tombol QR
        const qrBtn = document.createElement("button");
        qrBtn.type = "button";
        qrBtn.className = "btn-qr";
        qrBtn.textContent = "QR";
        qrBtn.addEventListener("click", () => openQRModal(f.name));

        // 2. Tombol Simpan / Unduh
        const link = document.createElement("a");
        link.className = "btn-download";
        link.href = `/api/download?name=${encodeURIComponent(f.name)}`;
        link.setAttribute("download", f.name);
        link.textContent = "Simpan";

        // 3. Tombol Hapus (✕)
        const delBtn = document.createElement("button");
        delBtn.type = "button";
        delBtn.className = "btn-delete";
        delBtn.textContent = "✕";
        delBtn.title = "Hapus file";
        delBtn.addEventListener("click", async () => {
          if (!confirm(`Hapus file "${f.name}"?`)) return;
          const r = await fetch(
            `/api/delete?name=${encodeURIComponent(f.name)}`,
            { method: "DELETE" },
          );
          if (r.ok) {
            showToast("File dihapus");
            loadFiles();
          } else {
            showToast("Gagal menghapus file");
          }
        });

        // Masukkan elemen ke container secara berurutan
        actions.appendChild(directBtn);
        actions.appendChild(qrBtn);
        actions.appendChild(link);
        actions.appendChild(delBtn);

        li.appendChild(meta);
        li.appendChild(actions);
        fileListEl.appendChild(li);
      });
    } catch (err) {
      console.error("[PhoneDrop] Gagal memuat file:", err);
      fileListEl.innerHTML =
        '<li class="empty-state">Gagal memuat daftar file</li>';
    }
  }
  btnRefreshFiles.addEventListener("click", loadFiles);

  if (btnZipAll) {
    btnZipAll.addEventListener("click", () => {
      window.location.href = "/api/zip";
      showToast("Mengunduh semua file (.zip)...");
    });
  }

  if (btnQrAll) {
    btnQrAll.addEventListener("click", () => {
      qrTabs.classList.add("hidden");
      showQRForZip();
      qrModal.classList.remove("hidden");
    });
  }

  connectWS();
  loadFiles();

  // --- Modul Remote Touchpad & Keyboard ---
  const touchpad = document.getElementById("touchpad-area");
  const kbdInput = document.getElementById("remote-kbd-input");

  function sendInput(payload) {
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(
        JSON.stringify({
          type: "input",
          input: payload,
          timestamp: Date.now(),
        }),
      );
    }
  }

  let lastTouchX = 0;
  let lastTouchY = 0;
  let touchMoved = false;

  touchpad.addEventListener(
    "touchstart",
    (e) => {
      if (e.touches.length === 1) {
        lastTouchX = e.touches[0].clientX;
        lastTouchY = e.touches[0].clientY;
        touchMoved = false;
      }
    },
    { passive: false },
  );

  touchpad.addEventListener(
    "touchmove",
    (e) => {
      e.preventDefault();
      if (e.touches.length === 1) {
        const dx = Math.round((e.touches[0].clientX - lastTouchX) * 1.8);
        const dy = Math.round((e.touches[0].clientY - lastTouchY) * 1.8);
        lastTouchX = e.touches[0].clientX;
        lastTouchY = e.touches[0].clientY;
        if (Math.abs(dx) > 0 || Math.abs(dy) > 0) {
          touchMoved = true;
          sendInput({ sub_type: "move", dx, dy });
        }
      }
    },
    { passive: false },
  );

  touchpad.addEventListener("touchend", () => {
    if (!touchMoved) {
      sendInput({ sub_type: "click", button: "left" });
    }
  });

  document
    .getElementById("btn-click-left")
    .addEventListener("click", () =>
      sendInput({ sub_type: "click", button: "left" }),
    );
  document
    .getElementById("btn-click-right")
    .addEventListener("click", () =>
      sendInput({ sub_type: "click", button: "right" }),
    );
  document
    .getElementById("btn-scroll-up")
    .addEventListener("click", () =>
      sendInput({ sub_type: "click", button: "scroll_up" }),
    );
  document
    .getElementById("btn-scroll-down")
    .addEventListener("click", () =>
      sendInput({ sub_type: "click", button: "scroll_down" }),
    );

  // Remote Keyboard
  kbdInput.addEventListener("input", () => {
    if (kbdInput.value) {
      sendInput({ sub_type: "text", text: kbdInput.value });
      kbdInput.value = "";
    }
  });

  document
    .getElementById("btn-key-bs")
    .addEventListener("click", () =>
      sendInput({ sub_type: "key", key: "BackSpace" }),
    );
  document
    .getElementById("btn-key-enter")
    .addEventListener("click", () =>
      sendInput({ sub_type: "key", key: "Return" }),
    );

  // --- PWA Installation & Service Worker ---
  if ("serviceWorker" in navigator) {
    window.addEventListener("load", () => {
      navigator.serviceWorker
        .register("/sw.js")
        .then((reg) => console.log("[PWA] Service Worker aktif:", reg.scope))
        .catch((err) => console.warn("[PWA] Service Worker error:", err));
    });
  }

  window.addEventListener("beforeinstallprompt", (e) => {
    e.preventDefault();
    deferredPrompt = e;
    if (btnPwaInstall) {
      btnPwaInstall.classList.remove("hidden");
    }
  });

  if (btnPwaInstall) {
    btnPwaInstall.addEventListener("click", async () => {
      if (!deferredPrompt) return;
      deferredPrompt.prompt();
      const { outcome } = await deferredPrompt.userChoice;
      if (outcome === "accepted") {
        showToast("PhoneDrop berhasil dipasang!");
      }
      deferredPrompt = null;
      btnPwaInstall.classList.add("hidden");
    });
  }

  window.addEventListener("appinstalled", () => {
    if (btnPwaInstall) btnPwaInstall.classList.add("hidden");
    showToast("Aplikasi PhoneDrop siap digunakan!");
  });

  // --- Web Share Target: umpan balik setelah berbagi dari menu "Bagikan" Android ---
  (() => {
    const params = new URLSearchParams(window.location.search);
    const shared = params.get("shared");
    if (!shared) return;

    const messages = {
      files: `✓ ${params.get("count") || 1} file terkirim ke Laptop`,
      text: "✓ Teks disalin ke clipboard Laptop",
      empty: "Tidak ada konten yang dibagikan",
      error: "✕ Gagal menerima konten yang dibagikan",
    };
    showToast(messages[shared] || messages.error, 3500);

    // Bersihkan URL agar toast tidak muncul lagi saat refresh
    history.replaceState(null, "", window.location.pathname);
  })();
})();
