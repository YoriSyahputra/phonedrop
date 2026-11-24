package com.phonedrop.v2.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.phonedrop.v2.data.repository.PhoneDropSessionHolder
import com.phonedrop.v2.domain.model.ConnectionState
import com.phonedrop.v2.domain.model.TransferDirection
import com.phonedrop.v2.domain.model.TransferState
import com.phonedrop.v2.domain.model.TransferStatus
import com.phonedrop.v2.presentation.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Foreground Service managing the persistent PDP2 TCP tunnel, clipboard sync,
 * and live rate-throttled progress notifications (<= 4 Hz intermediate, guaranteed 0% & 100%).
 */
class FileTransferService : Service() {

    companion object {
        const val ACTION_START = "com.phonedrop.v2.ACTION_START"
        const val ACTION_STOP = "com.phonedrop.v2.ACTION_STOP"
        const val ACTION_SEND_URI = "com.phonedrop.v2.ACTION_SEND_URI"
        const val EXTRA_HOST = "extra_host"
        const val EXTRA_PORT = "extra_port"
        const val EXTRA_URI = "extra_uri"

        private const val CHANNEL_SERVICE = "phonedrop_v2_tunnel"
        private const val CHANNEL_PROGRESS = "phonedrop_v2_transfer"
        private const val NOTIF_SERVICE_ID = 4101
        private const val NOTIF_TRANSFER_BASE = 4200
        private const val NOTIF_THROTTLE_MS = 250L
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var sessionHolder: PhoneDropSessionHolder
    private lateinit var notifMgr: NotificationManager
    private var clipboardMgr: ClipboardManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val lastNotifMs = ConcurrentHashMap<String, Long>()
    private val notifIds = ConcurrentHashMap<String, Int>()
    private var nextNotifOffset = 0
    private var observersJob: Job? = null

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        val clip = clipboardMgr?.primaryClip ?: return@OnPrimaryClipChangedListener
        if (clip.itemCount <= 0) return@OnPrimaryClipChangedListener
        val text = clip.getItemAt(0).coerceToText(this)?.toString() ?: return@OnPrimaryClipChangedListener
        if (text.isNotBlank() && sessionHolder.connectionState.value.isConnected) {
            serviceScope.launch {
                runCatching { sessionHolder.sendClipboard(text) }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        sessionHolder = PhoneDropSessionHolder.getInstance(this)
        notifMgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        clipboardMgr = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        createChannels()

        sessionHolder.onRemoteClipboardReceived = { payload ->
            mainHandler.post {
                runCatching {
                    clipboardMgr?.setPrimaryClip(ClipData.newPlainText("PhoneDrop", payload.text))
                }
            }
        }
        runCatching { clipboardMgr?.addPrimaryClipChangedListener(clipListener) }
        startObservers()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            serviceScope.launch { sessionHolder.disconnect() }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val initialNotif = buildServiceNotification(sessionHolder.connectionState.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_SERVICE_ID, initialNotif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_SERVICE_ID, initialNotif)
        }

        when (intent?.action) {
            ACTION_START, null -> {
                val host = intent?.getStringExtra(EXTRA_HOST)
                val port = intent?.getIntExtra(EXTRA_PORT, 28200) ?: 28200
                serviceScope.launch {
                    if (!sessionHolder.connectionState.value.isConnected || !host.isNullOrBlank()) {
                        sessionHolder.connectAuto(host, port)
                    }
                }
            }
            ACTION_SEND_URI -> {
                @Suppress("DEPRECATION")
                val uri = intent.getParcelableExtra<Uri>(EXTRA_URI)
                if (uri != null) {
                    serviceScope.launch {
                        runCatching {
                            if (!sessionHolder.connectionState.value.isConnected) {
                                sessionHolder.connectAuto(null, 28200)
                            }
                            sessionHolder.sendFile(uri)
                        }
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun startObservers() {
        observersJob?.cancel()
        observersJob = serviceScope.launch {
            launch {
                sessionHolder.connectionState.collect { state ->
                    notifMgr.notify(NOTIF_SERVICE_ID, buildServiceNotification(state))
                }
            }
            launch {
                sessionHolder.transfers.collect { transferMap ->
                    transferMap.values.forEach { updateTransferNotification(it) }
                }
            }
        }
    }

    private fun updateTransferNotification(tr: TransferState) {
        val now = SystemClock.elapsedRealtime()
        val prev = lastNotifMs[tr.transferIdHex] ?: 0L
        val isTerminal = tr.status != TransferStatus.IN_PROGRESS
        val isInitial = tr.bytesTransferred == 0L

        // Always emit 0% and 100%/Error unconditionally; throttle intermediate progress to <= 4 Hz
        if (!isTerminal && !isInitial && (now - prev) < NOTIF_THROTTLE_MS) {
            return
        }
        lastNotifMs[tr.transferIdHex] = now

        val nid = notifIds.getOrPut(tr.transferIdHex) {
            NOTIF_TRANSFER_BASE + ((nextNotifOffset++) % 100)
        }

        val dirLabel = if (tr.direction == TransferDirection.SEND) "Mengirim" else "Menerima"
        val title = when (tr.status) {
            TransferStatus.IN_PROGRESS -> "$dirLabel ${tr.fileName}"
            TransferStatus.VERIFIED -> "Selesai: ${tr.fileName}"
            TransferStatus.FAILED -> "Gagal: ${tr.fileName}"
        }

        val pctInt = tr.percent.toInt().coerceIn(0, 100)
        val contentText = when (tr.status) {
            TransferStatus.IN_PROGRESS -> String.format(
                Locale.US,
                "%.1f%% • %.1f MB/s (%s / %s)",
                tr.percent,
                tr.speedMBps,
                formatBytes(tr.bytesTransferred),
                formatBytes(tr.totalBytes)
            )
            TransferStatus.VERIFIED -> String.format(
                Locale.US,
                "100%% • %.1f MB/s • SHA-256 Verified",
                tr.speedMBps
            )
            TransferStatus.FAILED -> tr.errorMessage ?: "Integrity verification failed"
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(contentText)
            .setOnlyAlertOnce(true)
            .setOngoing(tr.status == TransferStatus.IN_PROGRESS)
            .setContentIntent(mainActivityPendingIntent())

        if (tr.status == TransferStatus.IN_PROGRESS) {
            builder.setSmallIcon(
                if (tr.direction == TransferDirection.SEND) {
                    android.R.drawable.stat_sys_upload
                } else {
                    android.R.drawable.stat_sys_download
                }
            )
            builder.setProgress(100, pctInt, false)
        } else {
            builder.setProgress(0, 0, false)
            builder.setAutoCancel(true)
        }

        notifMgr.notify(nid, builder.build())
    }

    private fun buildServiceNotification(state: ConnectionState): Notification {
        val statusText = when (state) {
            ConnectionState.Disconnected -> "Disconnected — tap to connect"
            is ConnectionState.Connecting -> "Connecting: ${state.endpoint}"
            is ConnectionState.UsbConnected -> "USB Tunnel Active (${state.endpoint})"
            is ConnectionState.WifiConnected -> "Wi-Fi LAN Active (${state.endpoint})"
            is ConnectionState.Error -> "Error: ${state.message}"
        }
        val stopIntent = Intent(this, FileTransferService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this,
            10,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_SERVICE)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("PhoneDrop")
            .setContentText(statusText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(mainActivityPendingIntent())
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPending)
            .build()
    }

    private fun mainActivityPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createChannels() {
        val svcChannel = NotificationChannel(
            CHANNEL_SERVICE,
            "PhoneDrop Tunnel Service",
            NotificationManager.IMPORTANCE_MIN
        )
        val progChannel = NotificationChannel(
            CHANNEL_PROGRESS,
            "File Transfer Progress",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(true)
        }
        notifMgr.createNotificationChannel(svcChannel)
        notifMgr.createNotificationChannel(progChannel)
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
        return String.format(Locale.US, "%.2f GB", mb / 1024.0)
    }

    override fun onDestroy() {
        observersJob?.cancel()
        runCatching { clipboardMgr?.removePrimaryClipChangedListener(clipListener) }
        sessionHolder.onRemoteClipboardReceived = null
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
