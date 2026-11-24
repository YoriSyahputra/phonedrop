package id.phonedrop.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {
    private static final int REQ_FILE_CHOOSER = 201;

    private FrameLayout rootContainer;
    private WebView webView;
    private ScrollView settingsOverlay;

    private TextView statusDot;
    private TextView statusLabel;
    private EditText serverInput;
    private Button toggleBtn;
    private TextView historyView;

    private ValueCallback<Uri[]> filePathCallback;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String newFile = intent.getStringExtra("file");
            if (newFile != null && !newFile.isEmpty()) {
                Toast.makeText(
                        MainActivity.this,
                        "✓ Direct Send diterima: " + newFile,
                        Toast.LENGTH_SHORT).show();
            }
            refreshSettingsUI();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window w = getWindow();
        if (w != null && Build.VERSION.SDK_INT >= 21) {
            w.setStatusBarColor(Color.parseColor("#0b110e"));
            w.setNavigationBarColor(Color.parseColor("#0b110e"));
        }

        requestNotifPermissionIfNeeded();
        buildMainAndSettingsLayout();

        // Otomatis jalankan background service
        SharedPreferences prefs = getSharedPreferences("phonedrop", MODE_PRIVATE);
        if (prefs.getBoolean("auto_start", true) && !DirectSendService.isRunning) {
            startBgService();
        }

        loadMainWebUI();
        handleIncomingShareIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingShareIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(DirectSendService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(statusReceiver, filter);
        }
        refreshSettingsUI();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(statusReceiver);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onBackPressed() {
        if (settingsOverlay != null && settingsOverlay.getVisibility() == View.VISIBLE) {
            hideSettings();
            return;
        }
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE_CHOOSER) {
            if (filePathCallback == null) return;
            Uri[] results = null;
            if (resultCode == Activity.RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    ClipData cd = data.getClipData();
                    results = new Uri[cd.getItemCount()];
                    for (int i = 0; i < cd.getItemCount(); i++) {
                        results[i] = cd.getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        }
    }

    private void requestNotifPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
            }
        }
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void buildMainAndSettingsLayout() {
        rootContainer = new FrameLayout(this);
        rootContainer.setBackgroundColor(Color.parseColor("#0b110e"));

        // Berikan padding atas dinamis agar konten tidak tertutup status bar, notch, atau punch-hole camera
        int statusBarHeight = 0;
        int resId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resId > 0) {
            statusBarHeight = getResources().getDimensionPixelSize(resId);
        }
        rootContainer.setPadding(0, statusBarHeight > 0 ? statusBarHeight : dp(32), 0, 0);

        if (Build.VERSION.SDK_INT >= 20) {
            rootContainer.setOnApplyWindowInsetsListener((v, insets) -> {
                int top = insets.getSystemWindowInsetTop();
                if (top > 0) {
                    v.setPadding(0, top, 0, 0);
                }
                return insets;
            });
        }

        // 1. Tampilan Utama: WebView Antarmuka Penuh PhoneDrop
        webView = new WebView(this);
        webView.setBackgroundColor(Color.parseColor("#0b110e"));
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        if (Build.VERSION.SDK_INT >= 21) {
            ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        webView.addJavascriptInterface(new JsBridge(), "PhoneDropNative");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    runOnUiThread(() -> {
                        Toast.makeText(
                                MainActivity.this,
                                "Server Laptop belum terjangkau. Periksa IP di Pengaturan.",
                                Toast.LENGTH_LONG).show();
                        showSettings();
                    });
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(
                    WebView wv,
                    ValueCallback<Uri[]> filePathCb,
                    FileChooserParams fileChooserParams) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = filePathCb;
                try {
                    Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    startActivityForResult(Intent.createChooser(intent, "Pilih File"), REQ_FILE_CHOOSER);
                    return true;
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
            }
        });

        // Unduhan dari dalam WebView langsung ditangani oleh DirectSendService ke Downloads/PhoneDrop
        webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            String fileName = URLUtil.guessFileName(url, contentDisposition, mimetype);
            Intent dl = new Intent(MainActivity.this, DirectSendService.class);
            dl.setAction(DirectSendService.ACTION_DOWNLOAD_URL);
            dl.putExtra("url", url);
            dl.putExtra("filename", fileName);
            startService(dl);
            Toast.makeText(
                    MainActivity.this,
                    "Mengunduh ke Downloads/PhoneDrop: " + fileName,
                    Toast.LENGTH_SHORT).show();
        });

        rootContainer.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // 2. Halaman Pengaturan & Riwayat Direct Send (disembunyikan secara default)
        settingsOverlay = buildSettingsOverlay();
        settingsOverlay.setVisibility(View.GONE);
        rootContainer.addView(settingsOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        setContentView(rootContainer);
    }

    private ScrollView buildSettingsOverlay() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#0b110e"));
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, dp(22), pad, pad);

        // Top Bar Pengaturan dengan Tombol Kembali
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.setPadding(0, 0, 0, dp(18));

        Button backBtn = makeButton("← Kembali", false);
        backBtn.setPadding(dp(14), dp(8), dp(14), dp(8));
        backBtn.setOnClickListener(v -> hideSettings());
        headerRow.addView(backBtn);

        LinearLayout titleWrap = new LinearLayout(this);
        titleWrap.setOrientation(LinearLayout.VERTICAL);
        titleWrap.setPadding(dp(14), 0, 0, 0);

        TextView title = new TextView(this);
        title.setText("Pengaturan PhoneDrop");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.parseColor("#f3efe6"));
        titleWrap.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Background Service • Server LAN • Riwayat");
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        subtitle.setTextColor(Color.parseColor("#c8a97e"));
        titleWrap.addView(subtitle);

        headerRow.addView(titleWrap);
        root.addView(headerRow);

        // Card 1: Riwayat Direct Send (Downloads/PhoneDrop)
        LinearLayout logCard = makeCard();
        LinearLayout logHead = new LinearLayout(this);
        logHead.setOrientation(LinearLayout.HORIZONTAL);
        logHead.setGravity(Gravity.CENTER_VERTICAL);

        TextView logTitle = new TextView(this);
        logTitle.setText("Riwayat Direct Send (Downloads/PhoneDrop)");
        logTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        logTitle.setTypeface(Typeface.DEFAULT_BOLD);
        logTitle.setTextColor(Color.parseColor("#f3efe6"));
        LinearLayout.LayoutParams logTitleLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        logTitle.setLayoutParams(logTitleLp);
        logHead.addView(logTitle);

        Button clearBtn = makeButton("Bersihkan", false);
        clearBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        clearBtn.setPadding(dp(10), dp(5), dp(10), dp(5));
        clearBtn.setOnClickListener(v -> {
            getSharedPreferences("phonedrop", MODE_PRIVATE)
                    .edit()
                    .putString("history_json", "[]")
                    .apply();
            refreshSettingsUI();
            Toast.makeText(this, "Catatan riwayat dibersihkan", Toast.LENGTH_SHORT).show();
        });
        logHead.addView(clearBtn);
        logCard.addView(logHead);

        historyView = new TextView(this);
        historyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        historyView.setTextColor(Color.parseColor("#a39c90"));
        historyView.setPadding(0, dp(10), 0, 0);
        logCard.addView(historyView);
        root.addView(logCard);

        // Card 2: Status & Kontrol Background Service
        LinearLayout statusCard = makeCard();
        addMarginTop(statusCard, dp(16));

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);

        statusDot = new TextView(this);
        statusDot.setText("●");
        statusDot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        statusDot.setPadding(0, 0, dp(10), 0);
        statusRow.addView(statusDot);

        statusLabel = new TextView(this);
        statusLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        statusLabel.setTypeface(Typeface.DEFAULT_BOLD);
        statusLabel.setTextColor(Color.parseColor("#f3efe6"));
        statusRow.addView(statusLabel);

        statusCard.addView(statusRow);

        TextView desc = new TextView(this);
        desc.setText("Saat layanan ini aktif, file yang dikirim dari Laptop dengan mode Direct Send otomatis masuk ke folder internal Downloads/PhoneDrop walau aplikasi ditutup atau layar HP mati.");
        desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        desc.setTextColor(Color.parseColor("#a39c90"));
        desc.setPadding(0, dp(10), 0, dp(16));
        statusCard.addView(desc);

        toggleBtn = makeButton("Aktifkan Background Service", true);
        toggleBtn.setOnClickListener(v -> {
            saveServerUrl();
            SharedPreferences prefs = getSharedPreferences("phonedrop", MODE_PRIVATE);
            if (DirectSendService.isRunning) {
                prefs.edit().putBoolean("auto_start", false).apply();
                stopBgService();
            } else {
                prefs.edit().putBoolean("auto_start", true).apply();
                startBgService();
            }
        });
        statusCard.addView(toggleBtn);
        root.addView(statusCard);

        // Card 3: Konfigurasi Server Laptop
        LinearLayout cfgCard = makeCard();
        addMarginTop(cfgCard, dp(16));

        TextView cfgTitle = new TextView(this);
        cfgTitle.setText("Alamat Server Laptop (Wi-Fi LAN)");
        cfgTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        cfgTitle.setTypeface(Typeface.DEFAULT_BOLD);
        cfgTitle.setTextColor(Color.parseColor("#f3efe6"));
        cfgCard.addView(cfgTitle);

        SharedPreferences prefs = getSharedPreferences("phonedrop", MODE_PRIVATE);
        String savedUrl = prefs.getString("server_url", getString(R.string.default_server));

        serverInput = new EditText(this);
        serverInput.setText(savedUrl);
        serverInput.setHint("http://192.168.1.8:8080");
        serverInput.setHintTextColor(Color.parseColor("#6e685f"));
        serverInput.setTextColor(Color.parseColor("#f3efe6"));
        serverInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        serverInput.setSingleLine(true);
        serverInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setColor(Color.parseColor("#0c120f"));
        inputBg.setCornerRadius(dp(10));
        inputBg.setStroke(dp(1), Color.parseColor("#383127"));
        serverInput.setBackground(inputBg);
        serverInput.setPadding(dp(14), dp(12), dp(14), dp(12));
        addMarginTop(serverInput, dp(10));
        cfgCard.addView(serverInput);

        Button saveReconnectBtn = makeButton("Simpan & Muat Ulang Aplikasi", true);
        addMarginTop(saveReconnectBtn, dp(10));
        saveReconnectBtn.setOnClickListener(v -> {
            saveServerUrl();
            stopBgService();
            v.postDelayed(this::startBgService, 300);
            loadMainWebUI();
            hideSettings();
            Toast.makeText(this, "Terhubung ke " + getSavedServerUrl(), Toast.LENGTH_SHORT).show();
        });
        cfgCard.addView(saveReconnectBtn);

        root.addView(cfgCard);
        scroll.addView(root);
        return scroll;
    }

    public class JsBridge {
        @JavascriptInterface
        public void openSettings() {
            runOnUiThread(MainActivity.this::showSettings);
        }

        @JavascriptInterface
        public String readClipboard() {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null && cm.hasPrimaryClip()) {
                    ClipData cd = cm.getPrimaryClip();
                    if (cd != null && cd.getItemCount() > 0) {
                        CharSequence cs = cd.getItemAt(0).coerceToText(MainActivity.this);
                        if (cs != null) return cs.toString();
                    }
                }
            } catch (Exception ignored) {
            }
            return "";
        }

        @JavascriptInterface
        public boolean writeClipboard(String text) {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("PhoneDrop", text));
                    return true;
                }
            } catch (Exception ignored) {
            }
            return false;
        }
    }

    private void showSettings() {
        refreshSettingsUI();
        if (settingsOverlay != null) {
            settingsOverlay.setVisibility(View.VISIBLE);
        }
    }

    private void hideSettings() {
        if (settingsOverlay != null) {
            settingsOverlay.setVisibility(View.GONE);
        }
    }

    private String getSavedServerUrl() {
        SharedPreferences prefs = getSharedPreferences("phonedrop", MODE_PRIVATE);
        String url = prefs.getString("server_url", getString(R.string.default_server));
        if (url == null || url.trim().isEmpty()) {
            url = getString(R.string.default_server);
        }
        return url.trim();
    }

    private void loadMainWebUI() {
        if (webView != null) {
            webView.loadUrl(getSavedServerUrl());
        }
    }

    /**
     * Menggabungkan riwayat dari SharedPreferences (file yang diterima DirectSendService di background)
     * DAN memindai langsung folder MediaStore Downloads/PhoneDrop agar semua file yang ada di
     * Downloads/PhoneDrop selalu tampil lengkap.
     */
    private String buildHistoryReport() {
        Map<String, String> entries = new LinkedHashMap<>();

        // 1. Baca riwayat yang tersimpan di SharedPreferences
        try {
            SharedPreferences prefs = getSharedPreferences("phonedrop", MODE_PRIVATE);
            JSONArray arr = new JSONArray(prefs.getString("history_json", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String name = o.optString("name", "");
                long size = o.optLong("size", 0);
                String time = o.optString("time", "");
                if (!name.isEmpty() && !entries.containsKey(name)) {
                    entries.put(name, "✓ " + name + "\n   " + formatBytes(size) + " • " + time);
                }
            }
        } catch (Exception ignored) {
        }

        // 2. Pindai MediaStore.Downloads untuk folder Download/PhoneDrop (Android 10+)
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                String[] proj = new String[]{
                        MediaStore.Downloads.DISPLAY_NAME,
                        MediaStore.Downloads.SIZE,
                        MediaStore.Downloads.DATE_MODIFIED,
                        MediaStore.Downloads.RELATIVE_PATH
                };
                try (Cursor c = getContentResolver().query(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        proj,
                        null,
                        null,
                        MediaStore.Downloads.DATE_MODIFIED + " DESC")) {
                    if (c != null) {
                        int nameIdx = c.getColumnIndex(MediaStore.Downloads.DISPLAY_NAME);
                        int sizeIdx = c.getColumnIndex(MediaStore.Downloads.SIZE);
                        int dateIdx = c.getColumnIndex(MediaStore.Downloads.DATE_MODIFIED);
                        int pathIdx = c.getColumnIndex(MediaStore.Downloads.RELATIVE_PATH);
                        SimpleDateFormat sdf = new SimpleDateFormat("dd/MM HH:mm", Locale.getDefault());
                        while (c.moveToNext()) {
                            String relPath = pathIdx >= 0 ? c.getString(pathIdx) : "";
                            if (relPath != null && !relPath.contains("PhoneDrop")) {
                                continue;
                            }
                            String name = nameIdx >= 0 ? c.getString(nameIdx) : null;
                            long size = sizeIdx >= 0 ? c.getLong(sizeIdx) : 0;
                            long modSec = dateIdx >= 0 ? c.getLong(dateIdx) : 0;
                            if (name != null && !name.isEmpty() && !entries.containsKey(name)) {
                                String dateStr = modSec > 0
                                        ? sdf.format(new Date(modSec * 1000L))
                                        : "Tersimpan";
                                entries.put(name, "✓ " + name + "\n   " + formatBytes(size) + " • " + dateStr);
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }

        // 3. Fallback cek langsung objek File di /sdcard/Download/PhoneDrop
        try {
            File dir = new File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "PhoneDrop");
            File[] files = dir.listFiles();
            if (files != null) {
                SimpleDateFormat sdf = new SimpleDateFormat("dd/MM HH:mm", Locale.getDefault());
                for (File f : files) {
                    if (f.isFile() && !entries.containsKey(f.getName())) {
                        entries.put(
                                f.getName(),
                                "✓ " + f.getName() + "\n   " + formatBytes(f.length()) + " • "
                                        + sdf.format(new Date(f.lastModified())));
                    }
                }
            }
        } catch (Exception ignored) {
        }

        if (entries.isEmpty()) {
            return "Belum ada file yang diterima di Downloads/PhoneDrop.\nKirim file dari Laptop dengan Direct Send untuk menguji.";
        }

        StringBuilder sb = new StringBuilder();
        int idx = 0;
        for (String line : entries.values()) {
            if (idx > 0) sb.append("\n\n");
            sb.append(line);
            idx++;
            if (idx >= 30) break;
        }
        return sb.toString();
    }

    private static String formatBytes(long bytes) {
        if (bytes <= 0) return "0 B";
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb);
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }

    private void refreshSettingsUI() {
        if (statusLabel == null) return;
        if (DirectSendService.isRunning) {
            if (DirectSendService.isConnected) {
                statusDot.setTextColor(Color.parseColor("#82cfa6"));
                statusLabel.setText("Terhubung ke Laptop • Direct Send Aktif");
            } else {
                statusDot.setTextColor(Color.parseColor("#d4b88a"));
                statusLabel.setText(DirectSendService.lastStatusText);
            }
            toggleBtn.setText("⏹ Hentikan Background Service");
            styleButton(toggleBtn, false);
        } else {
            statusDot.setTextColor(Color.parseColor("#e59389"));
            statusLabel.setText("Background Service Nonaktif");
            toggleBtn.setText("⚡ Aktifkan Background Service (Zero-Click)");
            styleButton(toggleBtn, true);
        }
        if (historyView != null) {
            historyView.setText(buildHistoryReport());
        }
    }

    private void saveServerUrl() {
        String raw = serverInput.getText().toString().trim();
        if (raw.isEmpty()) {
            raw = getString(R.string.default_server);
        }
        if (!raw.startsWith("http://") && !raw.startsWith("https://")) {
            raw = "http://" + raw;
        }
        while (raw.endsWith("/")) {
            raw = raw.substring(0, raw.length() - 1);
        }
        serverInput.setText(raw);
        getSharedPreferences("phonedrop", MODE_PRIVATE)
                .edit()
                .putString("server_url", raw)
                .apply();
    }

    private void startBgService() {
        Intent svc = new Intent(this, DirectSendService.class);
        svc.setAction(DirectSendService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(svc);
        } else {
            startService(svc);
        }
        if (toggleBtn != null) {
            toggleBtn.postDelayed(this::refreshSettingsUI, 300);
        }
    }

    private void stopBgService() {
        Intent svc = new Intent(this, DirectSendService.class);
        svc.setAction(DirectSendService.ACTION_STOP);
        startService(svc);
        if (toggleBtn != null) {
            toggleBtn.postDelayed(this::refreshSettingsUI, 300);
        }
    }

    // Menangani "Bagikan ke PhoneDrop" langsung dari aplikasi lain di Android
    private void handleIncomingShareIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            return;
        }

        final String baseUrl = getSavedServerUrl();
        final String sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
        final ArrayList<Uri> uris = new ArrayList<>();

        if (Intent.ACTION_SEND.equals(action)) {
            Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
        } else {
            ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) uris.addAll(list);
        }

        if ((sharedText == null || sharedText.isEmpty()) && uris.isEmpty()) {
            return;
        }

        Toast.makeText(this, "Mengirim ke Laptop...", Toast.LENGTH_SHORT).show();
        intent.setAction(null);

        new Thread(() -> {
            boolean ok = uploadSharedContent(baseUrl, sharedText, uris);
            runOnUiThread(() -> {
                Toast.makeText(
                        MainActivity.this,
                        ok ? "✓ Terkirim ke Laptop!" : "✕ Gagal mengirim ke Laptop",
                        Toast.LENGTH_SHORT).show();
                if (ok && webView != null) {
                    webView.reload();
                }
            });
        }).start();
    }

    private boolean uploadSharedContent(String baseUrl, String text, ArrayList<Uri> uris) {
        String boundary = "----PhoneDropBoundary" + System.currentTimeMillis();
        try {
            URL url = new URL(baseUrl + "/share-target");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(60000);
            conn.setDoOutput(true);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod("POST");
            conn.setChunkedStreamingMode(32 * 1024);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

            try (OutputStream out = conn.getOutputStream()) {
                if (text != null && !text.isEmpty()) {
                    writeFormField(out, boundary, "text", text);
                }
                for (Uri uri : uris) {
                    String name = queryFileName(uri);
                    String head = "--" + boundary + "\r\n"
                            + "Content-Disposition: form-data; name=\"files\"; filename=\"" + name + "\"\r\n"
                            + "Content-Type: application/octet-stream\r\n\r\n";
                    out.write(head.getBytes(StandardCharsets.UTF_8));
                    try (InputStream in = getContentResolver().openInputStream(uri)) {
                        if (in != null) {
                            byte[] buf = new byte[32 * 1024];
                            int n;
                            while ((n = in.read(buf)) != -1) {
                                out.write(buf, 0, n);
                            }
                        }
                    }
                    out.write("\r\n".getBytes(StandardCharsets.UTF_8));
                }
                String end = "--" + boundary + "--\r\n";
                out.write(end.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }

            int code = conn.getResponseCode();
            conn.disconnect();
            return code >= 200 && code < 400;
        } catch (Exception e) {
            return false;
        }
    }

    private static void writeFormField(OutputStream out, String boundary, String name, String val) throws Exception {
        String part = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + val + "\r\n";
        out.write(part.getBytes(StandardCharsets.UTF_8));
    }

    private String queryFileName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String n = c.getString(idx);
                    if (n != null && !n.isEmpty()) return n;
                }
            }
        } catch (Exception ignored) {
        }
        return "shared_" + System.currentTimeMillis();
    }

    private LinearLayout makeCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int p = dp(18);
        card.setPadding(p, p, p, p);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#161f1a"));
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), Color.parseColor("#352f25"));
        card.setBackground(bg);
        return card;
    }

    private Button makeButton(String label, boolean primary) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setPadding(dp(16), dp(12), dp(16), dp(12));
        styleButton(b, primary);
        return b;
    }

    private void styleButton(Button b, boolean primary) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(11));
        if (primary) {
            bg.setColor(Color.parseColor("#c8a97e"));
            b.setTextColor(Color.parseColor("#120e0a"));
        } else {
            bg.setColor(Color.parseColor("#241e19"));
            bg.setStroke(dp(1), Color.parseColor("#4a3f31"));
            b.setTextColor(Color.parseColor("#f3efe6"));
        }
        b.setBackground(bg);
    }

    private void addMarginTop(View v, int topPx) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = topPx;
        v.setLayoutParams(lp);
    }

    private int dp(int val) {
        return Math.round(val * getResources().getDisplayMetrics().density);
    }
}
