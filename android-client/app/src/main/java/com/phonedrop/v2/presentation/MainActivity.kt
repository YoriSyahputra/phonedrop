package com.phonedrop.v2.presentation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.core.content.ContextCompat
import com.phonedrop.v2.data.mirror.VideoStreamDecoder
import com.phonedrop.v2.domain.model.MirrorMode
import com.phonedrop.v2.presentation.ui.PhoneDropScreen
import com.phonedrop.v2.service.FileTransferService
import com.phonedrop.v2.service.ScreenCaptureService

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private var videoDecoder: VideoStreamDecoder? = null

    private val openFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            viewModel.sendFile(uri)
        }
    }

    private val notifPermLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    private val mediaProjLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == RESULT_OK && res.data != null) {
            startPhoneScreenCapture(res.resultCode, res.data!!)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        viewModel.startServiceAndConnect()
        handleShareIntent(intent)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                PhoneDropScreen(
                    viewModel = viewModel,
                    onPickFile = { openFileLauncher.launch(arrayOf("*/*")) },
                    onStartPhoneMirror = { requestScreenCapture() },
                    onStopPhoneMirror = { stopPhoneScreenCapture() },
                    onSurfaceReady = { sv -> attachVideoDecoderSurface(sv) },
                    onSurfaceDestroyed = { detachVideoDecoderSurface() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    override fun onDestroy() {
        detachVideoDecoderSurface()
        super.onDestroy()
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return
        if (Intent.ACTION_SEND == intent.action) {
            @Suppress("DEPRECATION")
            val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            if (uri != null) {
                val svcIntent = Intent(this, FileTransferService::class.java).apply {
                    action = FileTransferService.ACTION_SEND_URI
                    putExtra(FileTransferService.EXTRA_URI, uri)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(svcIntent)
                } else {
                    startService(svcIntent)
                }
                return
            }
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!text.isNullOrBlank()) {
                viewModel.sendClipboardText(text)
            }
        }
    }

    private fun requestScreenCapture() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun startPhoneScreenCapture(resultCode: Int, data: Intent) {
        viewModel.setMirrorMode(MirrorMode.SENDING_PHONE_SCREEN)
        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_START
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopPhoneScreenCapture() {
        viewModel.setMirrorMode(MirrorMode.IDLE)
        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP
        }
        startService(intent)
    }

    private fun attachVideoDecoderSurface(surfaceView: SurfaceView) {
        detachVideoDecoderSurface()
        val holder = viewModel.sessionHolder
        videoDecoder = VideoStreamDecoder(holder, holder.scope).also {
            it.attachSurface(surfaceView.holder.surface)
        }
    }

    private fun detachVideoDecoderSurface() {
        videoDecoder?.detach()
        videoDecoder = null
    }
}
