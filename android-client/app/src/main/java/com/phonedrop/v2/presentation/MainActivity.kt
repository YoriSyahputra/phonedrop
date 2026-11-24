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
import com.phonedrop.v2.domain.model.MirrorMode
import com.phonedrop.v2.presentation.ui.PhoneDropScreen
import com.phonedrop.v2.service.FileTransferService

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    private val openFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            viewModel.sendFile(uri)
        }
    }

    private val notifPermLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    private val mediaProjLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == RESULT_OK && res.data != null) {
            viewModel.setMirrorMode(MirrorMode.SENDING_PHONE_SCREEN)
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

    // Wired to ScreenCaptureService & VideoStreamDecoder in Phase 4.
    protected open fun startPhoneScreenCapture(resultCode: Int, data: Intent) {}
    protected open fun stopPhoneScreenCapture() {
        viewModel.setMirrorMode(MirrorMode.IDLE)
    }
    protected open fun attachVideoDecoderSurface(surfaceView: SurfaceView) {}
    protected open fun detachVideoDecoderSurface() {}
}
