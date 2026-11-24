package com.phonedrop.v2.presentation

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.phonedrop.v2.data.mirror.DecoderStatus
import com.phonedrop.v2.data.protocol.InputEventPayload
import com.phonedrop.v2.data.protocol.RemoteFileEntry
import com.phonedrop.v2.data.repository.PhoneDropSessionHolder
import com.phonedrop.v2.domain.model.ConnectionState
import com.phonedrop.v2.domain.model.LocalReceivedFile
import com.phonedrop.v2.domain.model.MirrorMode
import com.phonedrop.v2.domain.model.TransferState
import com.phonedrop.v2.domain.usecase.ConnectSessionUseCase
import com.phonedrop.v2.domain.usecase.SendFileUseCase
import com.phonedrop.v2.domain.usecase.SyncClipboardUseCase
import com.phonedrop.v2.service.FileTransferService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val remoteFiles: StateFlow<List<RemoteFileEntry>> = sessionHolder.remoteFiles
    val localFiles: StateFlow<List<LocalReceivedFile>> = sessionHolder.localFiles
    val statusToast: StateFlow<String?> = sessionHolder.statusToast

    private val _decoderStatus = MutableStateFlow<DecoderStatus>(DecoderStatus.Idle)
    val decoderStatus: StateFlow<DecoderStatus> = _decoderStatus.asStateFlow()

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
        viewModelScope.launch { sessionHolder.disconnect() }
    }

    fun sendFiles(uris: List<Uri>) {
        viewModelScope.launch {
            for (u in uris) {
                runCatching { sendFileUseCase(u) }
            }
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

    fun requestFileList() {
        viewModelScope.launch {
            runCatching { sessionHolder.requestFileList() }
            sessionHolder.refreshLocalFiles()
        }
    }

    fun pullRemoteFile(name: String) {
        viewModelScope.launch {
            runCatching { sessionHolder.pullRemoteFile(name) }
        }
    }

    fun deleteRemoteFile(name: String) {
        viewModelScope.launch {
            runCatching { sessionHolder.deleteRemoteFile(name) }
        }
    }

    fun refreshLocalFiles() {
        viewModelScope.launch { sessionHolder.refreshLocalFiles() }
    }

    fun deleteLocalFile(uriString: String) {
        viewModelScope.launch { sessionHolder.deleteLocalFile(uriString) }
    }

    fun startDesktopViewing() {
        sessionHolder.setMirrorMode(MirrorMode.RECEIVING_LINUX_DESKTOP)
        viewModelScope.launch {
            runCatching { sessionHolder.requestStartDesktopStream() }
        }
    }

    fun stopDesktopViewing() {
        if (sessionHolder.mirrorMode.value == MirrorMode.RECEIVING_LINUX_DESKTOP) {
            sessionHolder.setMirrorMode(MirrorMode.IDLE)
        }
        viewModelScope.launch {
            runCatching { sessionHolder.requestStopDesktopStream() }
        }
    }

    fun launchPhoneScrcpyOnLaptop() {
        viewModelScope.launch {
            runCatching { sessionHolder.requestLaunchScrcpy() }
        }
    }

    fun setMirrorMode(mode: MirrorMode) {
        sessionHolder.setMirrorMode(mode)
    }

    fun updateDecoderStatus(status: DecoderStatus) {
        _decoderStatus.value = status
    }

    fun clearStatusToast() {
        sessionHolder.clearStatusToast()
    }
}
