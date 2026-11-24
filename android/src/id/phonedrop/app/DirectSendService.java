package id.phonedrop.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class DirectSendService extends Service {
    public static final String ACTION_START = "id.phonedrop.app.ACTION_START";
    public static final String ACTION_STOP = "id.phonedrop.app.ACTION_STOP";
    public static final String ACTION_STATUS = "id.phonedrop.app.ACTION_STATUS";
    public static final String ACTION_DOWNLOAD_URL = "id.phonedrop.app.ACTION_DOWNLOAD_URL";

    private static final String CHANNEL_SERVICE = "phonedrop_bg_service";
    private static final String CHANNEL_FILES = "phonedrop_file_drop";
    private static final int NOTIF_ID_SERVICE = 1001;
    private static int fileNotifCounter = 2000;

    public static volatile boolean isRunning = false;
    public static volatile boolean isConnected = false;
    public static volatile String lastStatusText = "Service belum aktif";

    private Thread workerThread;
    private volatile boolean stopRequested = false;
    private HttpURLConnection activeConn;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String act = intent.getAction();
            if (ACTION_STOP.equals(act)) {
                stopSelfCleanly();
                return START_NOT_STICKY;
            }
            if (ACTION_DOWNLOAD_URL.equals(act)) {
                final String dlUrl = intent.getStringExtra("url");
                final String fileName = intent.getStringExtra("filename");
                if (dlUrl != null && fileName != null) {
                    new Thread(() -> downloadFromUrlZeroClick(dlUrl, fileName)).start();
                }
                if (!isRunning) {
                    return START_NOT_STICKY;
                }
            }
        }

        String serverUrl = getServerUrl();
        Notification notif = buildServiceNotification("Menghubungkan ke " + serverUrl + "...");
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID_SERVICE, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID_SERVICE, notif);
        }

        if (!isRunning) {
            isRunning = true;
            stopRequested = false;
            workerThread = new Thread(this::runEventLoop, "PhoneDrop-DirectSend");
            workerThread.start();
        }
        return START_STICKY;
    }

    private void stopSelfCleanly() {
        stopRequested = true;
        isRunning = false;
        isConnected = false;
        updateStatus("Service dihentikan", null);
        try {
            if (activeConn != null) {
                activeConn.disconnect();
            }
        } catch (Exception ignored) {
        }
        if (workerThread != null) {
            workerThread.interrupt();
        }
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        stopRequested = true;
        isRunning = false;
        isConnected = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private String getServerUrl() {
        SharedPreferences prefs = getSharedPreferences("phonedrop", MODE_PRIVATE);
        String def = getString(R.string.default_server);
        String url = prefs.getString("server_url", def);
        if (url == null || url.trim().isEmpty()) {
            url = def;
        }
        url = url.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    private void runEventLoop() {
        while (!stopRequested) {
            String baseUrl = getServerUrl();
            String eventsUrl = baseUrl + "/api/direct-send/events?device=" + encode(Build.MODEL);
            try {
                updateStatus("Menghubungkan ke Laptop (" + baseUrl + ")...", null);
                URL url = new URL(eventsUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                activeConn = conn;
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(45000);
                conn.setRequestProperty("Accept", "application/x-ndjson");

                int code = conn.getResponseCode();
                if (code != 200) {
                    throw new Exception("HTTP " + code);
                }

                isConnected = true;
                updateServiceNotification("Terhubung • Siap menerima file di Downloads/PhoneDrop");
                updateStatus("Terhubung ke Laptop • Direct Send Aktif", null);

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while (!stopRequested && (line = reader.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty()) continue;
                        handleEventJson(baseUrl, line);
                    }
                }
            } catch (Exception e) {
                isConnected = false;
                if (!stopRequested) {
                    updateServiceNotification("Menunggu server Laptop (" + baseUrl + ")...");
                    updateStatus("Menunggu koneksi ke Laptop...", null);
                    try {
                        Thread.sleep(4000);
                    } catch (InterruptedException ie) {
                        break;
                    }
                }
            } finally {
                if (activeConn != null) {
                    try {
                        activeConn.disconnect();
                    } catch (Exception ignored) {
                    }
                    activeConn = null;
                }
            }
        }
    }

    private void handleEventJson(String baseUrl, String jsonLine) {
        try {
            JSONObject obj = new JSONObject(jsonLine);
            String type = obj.optString("type", "");
            if ("file".equals(type)) {
                JSONArray arr = obj.optJSONArray("files");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        String fileName = arr.optString(i, "");
                        if (!fileName.isEmpty()) {
                            String dlUrl = baseUrl + "/api/download?name=" + encode(fileName);
                            downloadFromUrlZeroClick(dlUrl, fileName);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void downloadFromUrlZeroClick(String dlUrl, String fileName) {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wl = null;
        if (pm != null) {
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhoneDrop:FileDownload");
            wl.acquire(10 * 60 * 1000L);
        }

        try {
            updateServiceNotification("Menerima file: " + fileName + "...");
            HttpURLConnection conn = (HttpURLConnection) new URL(dlUrl).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(60000);

            if (conn.getResponseCode() != 200) {
                conn.disconnect();
                return;
            }

            String mime = guessMimeType(fileName);
            long totalBytes = 0;
            boolean saved = false;

            if (Build.VERSION.SDK_INT >= 29) {
                ContentResolver resolver = getContentResolver();
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                values.put(MediaStore.Downloads.MIME_TYPE, mime);
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/PhoneDrop");
                values.put(MediaStore.Downloads.IS_PENDING, 1);

                Uri itemUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (itemUri != null) {
                    try (InputStream in = conn.getInputStream();
                         OutputStream out = resolver.openOutputStream(itemUri)) {
                        if (out != null) {
                            totalBytes = copyStreamCount(in, out);
                            saved = true;
                        }
                    }
                    ContentValues done = new ContentValues();
                    done.put(MediaStore.Downloads.IS_PENDING, 0);
                    resolver.update(itemUri, done, null, null);
                }
            } else {
                File dir = new File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        "PhoneDrop");
                if (!dir.exists()) {
                    dir.mkdirs();
                }
                File outFile = uniqueFile(dir, fileName);
                try (InputStream in = conn.getInputStream();
                     OutputStream out = new FileOutputStream(outFile)) {
                    totalBytes = copyStreamCount(in, out);
                    saved = true;
                }
                MediaScannerConnection.scanFile(this,
                        new String[]{outFile.getAbsolutePath()},
                        new String[]{mime}, null);
            }

            conn.disconnect();

            if (saved) {
                recordHistoryPersistent(this, fileName, totalBytes);
                notifyFileSaved(fileName);
                updateServiceNotification("Terhubung • Terakhir diterima: " + fileName);
                updateStatus("Terhubung ke Laptop • Direct Send Aktif", fileName);
            }
        } catch (Exception e) {
            updateServiceNotification("Gagal menerima " + fileName);
        } finally {
            if (wl != null && wl.isHeld()) {
                wl.release();
            }
        }
    }

    public static synchronized void recordHistoryPersistent(Context ctx, String fileName, long bytes) {
        try {
            SharedPreferences prefs = ctx.getSharedPreferences("phonedrop", MODE_PRIVATE);
            String raw = prefs.getString("history_json", "[]");
            JSONArray arr = new JSONArray(raw);

            JSONObject item = new JSONObject();
            item.put("name", fileName);
            item.put("size", bytes);
            item.put("time", new SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()).format(new Date()));

            JSONArray next = new JSONArray();
            next.put(item);
            for (int i = 0; i < arr.length() && i < 49; i++) {
                next.put(arr.getJSONObject(i));
            }
            prefs.edit().putString("history_json", next.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private static long copyStreamCount(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[32 * 1024];
        long total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
            total += n;
        }
        out.flush();
        return total;
    }

    private static File uniqueFile(File dir, String name) {
        File candidate = new File(dir, name);
        if (!candidate.exists()) return candidate;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        int i = 1;
        while (candidate.exists()) {
            candidate = new File(dir, base + "_" + i + ext);
            i++;
        }
        return candidate;
    }

    private static String guessMimeType(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot > 0 && dot < fileName.length() - 1) {
            String ext = fileName.substring(dot + 1).toLowerCase();
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (mime != null) return mime;
        }
        return "application/octet-stream";
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private void updateStatus(String status, String newFile) {
        lastStatusText = status;
        Intent b = new Intent(ACTION_STATUS);
        b.setPackage(getPackageName());
        b.putExtra("status", status);
        b.putExtra("connected", isConnected);
        b.putExtra("running", isRunning);
        if (newFile != null) {
            b.putExtra("file", newFile);
        }
        sendBroadcast(b);
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;

            NotificationChannel svc = new NotificationChannel(
                    CHANNEL_SERVICE,
                    "PhoneDrop Background Service",
                    NotificationManager.IMPORTANCE_LOW);
            svc.setDescription("Menjaga koneksi Direct Send dari Laptop");
            nm.createNotificationChannel(svc);

            NotificationChannel files = new NotificationChannel(
                    CHANNEL_FILES,
                    "File Masuk (Direct Send)",
                    NotificationManager.IMPORTANCE_HIGH);
            files.setDescription("Pemberitahuan saat file dari Laptop tersimpan di Downloads/PhoneDrop");
            nm.createNotificationChannel(files);
        }
    }

    private Notification buildServiceNotification(String text) {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent contentPi = PendingIntent.getActivity(
                this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent stopIntent = new Intent(this, DirectSendService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(
                this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b = new Notification.Builder(this, CHANNEL_SERVICE)
                .setContentTitle("PhoneDrop Direct Send")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentIntent(contentPi)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_close_clear_cancel, "Matikan", stopPi).build());
        return b.build();
    }

    private void updateServiceNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null && isRunning) {
            nm.notify(NOTIF_ID_SERVICE, buildServiceNotification(text));
        }
    }

    private void notifyFileSaved(String fileName) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;

        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent contentPi = PendingIntent.getActivity(
                this, 2, openIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b = new Notification.Builder(this, CHANNEL_FILES)
                .setContentTitle("✓ File Diterima dari Laptop")
                .setContentText(fileName + " → Downloads/PhoneDrop")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentIntent(contentPi)
                .setAutoCancel(true);

        nm.notify(fileNotifCounter++, b.build());
    }
}
