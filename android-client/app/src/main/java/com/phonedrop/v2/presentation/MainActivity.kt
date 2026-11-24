package com.phonedrop.v2.presentation

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.SurfaceView
import android.widget.Toast
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private var videoDecoder: VideoStreamDecoder? = null
    private var decoderStatusJob: Job? = null

    private val openFilesLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.sendFiles(uris)
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
                    onPickFiles = { openFilesLauncher.launch(arrayOf("*/*")) },
                    onOpenFile = { uriStr, mime -> openLocalUri(uriStr, mime) },
                    onShareFile = { uriStr, mime -> shareLocalUri(uriStr, mime) },
                    onReadPhoneClipboard = { readPhoneClipboard() },
                    onCopyToPhoneClipboard = { copyToPhoneClipboard(it) },
                    onStartPhoneMirror = { requestScreenCapture() },
                    onStopPhoneMirror = { stopPhoneScreenCapture() },
                    onSurfaceReady = { sv -> attachVideoDecoderSurface(sv) },
                    onSurfaceDestroyed = { detachVideoDecoderSurface() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshLocalFiles()
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

    private fun openLocalUri(uriStr: String, mimeType: String) {
        runCatching {
            val uri = Uri.parse(uriStr)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType.ifBlank { "*/*" })
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Buka file dengan"))
        }.onFailure {
            Toast.makeText(this, "Tidak ada aplikasi untuk membuka file ini", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareLocalUri(uriStr: String, mimeType: String) {
        runCatching {
            val uri = Uri.parse(uriStr)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType.ifBlank { "*/*" }
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Bagikan file"))
        }
    }

    private fun readPhoneClipboard(): String {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return ""
        val clip = cm.primaryClip ?: return ""
        if (clip.itemCount <= 0) return ""
        return clip.getItemAt(0).coerceToText(this)?.toString() ?: ""
    }

    private fun copyToPhoneClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("PhoneDrop", text))
        Toast.makeText(this, "Disalin ke clipboard HP", Toast.LENGTH_SHORT).show()
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
        val dec = VideoStreamDecoder(holder, holder.scope)
        videoDecoder = dec
        decoderStatusJob = holder.scope.launch {
            dec.status.collect { st ->
                viewModel.updateDecoderStatus(st)
            }
        }
        dec.attachSurface(surfaceView.holder.surface)
        if (viewModel.mirrorMode.value == MirrorMode.RECEIVING_LINUX_DESKTOP) {
            viewModel.startDesktopViewing()
        }
    }

    private fun detachVideoDecoderSurface() {
        decoderStatusJob?.cancel()
        decoderStatusJob = null
        videoDecoder?.detach()
        videoDecoder = null
    }
}
