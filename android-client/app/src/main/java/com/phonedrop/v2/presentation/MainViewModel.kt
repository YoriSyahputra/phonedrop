package com.phonedrop.v2.presentation

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.phonedrop.v2.data.protocol.InputEventPayload
import com.phonedrop.v2.data.repository.PhoneDropSessionHolder
import com.phonedrop.v2.domain.model.ConnectionState
import com.phonedrop.v2.domain.model.MirrorMode
import com.phonedrop.v2.domain.model.TransferState
import com.phonedrop.v2.domain.usecase.ConnectSessionUseCase
import com.phonedrop.v2.domain.usecase.SendFileUseCase
import com.phonedrop.v2.domain.usecase.SyncClipboardUseCase
import com.phonedrop.v2.service.FileTransferService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    val sessionHolder = PhoneDropSessionHolder.getInstance(application)

    private val connectSession = ConnectSessionUseCase(sessionHolder)
    private val sendFileUseCase = SendFileUseCase(sessionHolder)
    private val syncClipboard = SyncClipboardUseCase(sessionHolder)

    val connectionState: StateFlow<ConnectionState> = sessionHolder.connectionState
    val transferList: StateFlow<List<TransferState>> = sessionHolder.transfers
        .map { it.values.toList().reversed() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val latestClipboard: StateFlow<String> = sessionHolder.latestClipboard
    val mirrorMode: StateFlow<MirrorMode> = sessionHolder.mirrorMode

    fun startServiceAndConnect(manualHost: String? = null) {
        val ctx = getApplication<Application>()
        val intent = Intent(ctx, FileTransferService::class.java).apply {
            action = FileTransferService.ACTION_START
            if (!manualHost.isNullOrBlank()) {
                putExtra(FileTransferService.EXTRA_HOST, manualHost.trim())
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent)
        } else {
            ctx.startService(intent)
        }
        viewModelScope.launch {
            connectSession(manualHost?.takeIf { it.isNotBlank() }, 28200)
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            sessionHolder.disconnect()
        }
    }

    fun sendFile(uri: Uri) {
        viewModelScope.launch {
            runCatching { sendFileUseCase(uri) }
        }
    }

    fun sendClipboardText(text: String) {
        viewModelScope.launch {
            runCatching { syncClipboard(text) }
        }
    }

    fun sendInputEvent(ev: InputEventPayload) {
        viewModelScope.launch {
            runCatching { sessionHolder.sendInputEvent(ev) }
        }
    }

    fun setMirrorMode(mode: MirrorMode) {
        sessionHolder.setMirrorMode(mode)
    }
}
