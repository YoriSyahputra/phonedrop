package com.phonedrop.v2.service

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.phonedrop.v2.data.mirror.ScreenCaptureEncoder
import com.phonedrop.v2.data.repository.PhoneDropSessionHolder
import com.phonedrop.v2.domain.model.MirrorMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.math.roundToInt

/**
 * Foreground Service holding the Android 14-compliant MEDIA_PROJECTION type
 * to stream H.264 encoded phone screen frames to the Linux desktop.
 */
class ScreenCaptureService : Service() {

    companion object {
        const val ACTION_START = "com.phonedrop.v2.CAPTURE_START"
        const val ACTION_STOP = "com.phonedrop.v2.CAPTURE_STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        private const val CHANNEL_ID = "phonedrop_v2_mirror"
        private const val NOTIF_ID = 4301
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var sessionHolder: PhoneDropSessionHolder
    private var encoder: ScreenCaptureEncoder? = null

    override fun onCreate() {
        super.onCreate()
        sessionHolder = PhoneDropSessionHolder.getInstance(this)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "PhoneDrop Screen Mirroring", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCleanly()
            return START_NOT_STICKY
        }

        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this,
            20,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("PhoneDrop Screen Cast")
            .setContentText("Streaming H.264 display to Linux desktop")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPending)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultCode != Activity.RESULT_OK || data == null) {
            stopCleanly()
            return START_NOT_STICKY
        }

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = mpm.getMediaProjection(resultCode, data) ?: run {
            stopCleanly()
            return START_NOT_STICKY
        }

        val metrics = resources.displayMetrics
        val targetW = 720
        val scale = targetW.toFloat() / metrics.widthPixels.coerceAtLeast(1)
        val targetH = (metrics.heightPixels * scale).roundToInt().coerceAtLeast(720)

        encoder?.stop()
        encoder = ScreenCaptureEncoder(sessionHolder, scope) {
            stopCleanly()
        }.also {
            sessionHolder.setMirrorMode(MirrorMode.SENDING_PHONE_SCREEN)
            it.start(projection, targetW, targetH, metrics.densityDpi)
        }

        return START_NOT_STICKY
    }

    private fun stopCleanly() {
        encoder?.stop()
        encoder = null
        sessionHolder.setMirrorMode(MirrorMode.IDLE)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        encoder?.stop()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
