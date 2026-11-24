package com.phonedrop.v2.data.repository

import android.content.Context
import android.net.Uri
import com.phonedrop.v2.data.network.BinarySocketClient
import com.phonedrop.v2.data.network.MdnsDiscoveryManager
import com.phonedrop.v2.data.protocol.ClipPayload
import com.phonedrop.v2.data.protocol.ControlMessage
import com.phonedrop.v2.data.protocol.ControlOps
import com.phonedrop.v2.data.protocol.FileChunkPayload
import com.phonedrop.v2.data.protocol.FileDonePayload
import com.phonedrop.v2.data.protocol.FileInitPayload
import com.phonedrop.v2.data.protocol.InputEventPayload
import com.phonedrop.v2.data.protocol.ProtocolConstants
import com.phonedrop.v2.data.protocol.RemoteFileEntry
import com.phonedrop.v2.data.protocol.VideoFrameHeader
import com.phonedrop.v2.data.protocol.VideoFramePayload
import com.phonedrop.v2.domain.model.ConnectionState
import com.phonedrop.v2.domain.model.LocalReceivedFile
import com.phonedrop.v2.domain.model.MirrorMode
import com.phonedrop.v2.domain.model.TransferDirection
import com.phonedrop.v2.domain.model.TransferState
import com.phonedrop.v2.domain.model.TransferStatus
import com.phonedrop.v2.domain.repository.PhoneDropRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Process-wide singleton coordinator bridging FileTransferService, ScreenCaptureService,
 * VideoStreamDecoder, and Jetpack Compose ViewModels via reactive StateFlow/SharedFlow streams.
 */
class PhoneDropSessionHolder private constructor(
    private val appContext: Context
) : PhoneDropRepository {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val socketClient = BinarySocketClient(scope, MdnsDiscoveryManager(appContext))
    val fileTransferManager = FileTransferManager(
        socketClient = socketClient,
        outputSink = MediaStoreTransferSink(appContext),
        onProgressCallback = { state ->
            if (state.direction == TransferDirection.RECEIVE && state.status == TransferStatus.VERIFIED) {
                scope.launch { refreshLocalFiles() }
            }
        }
    )

    override val connectionState: StateFlow<ConnectionState> = socketClient.connectionState
    override val transfers: StateFlow<Map<String, TransferState>> = fileTransferManager.transfers

    private val _latestClipboard = MutableStateFlow("")
    override val latestClipboard: StateFlow<String> = _latestClipboard.asStateFlow()

    private val _mirrorMode = MutableStateFlow(MirrorMode.IDLE)
    override val mirrorMode: StateFlow<MirrorMode> = _mirrorMode.asStateFlow()

    private val _remoteFiles = MutableStateFlow<List<RemoteFileEntry>>(emptyList())
    override val remoteFiles: StateFlow<List<RemoteFileEntry>> = _remoteFiles.asStateFlow()

    private val _localFiles = MutableStateFlow<List<LocalReceivedFile>>(emptyList())
    override val localFiles: StateFlow<List<LocalReceivedFile>> = _localFiles.asStateFlow()

    private val _statusToast = MutableStateFlow<String?>(null)
    val statusToast: StateFlow<String?> = _statusToast.asStateFlow()

    private val _incomingVideoFrames = MutableSharedFlow<Pair<Int, VideoFramePayload>>(extraBufferCapacity = 96)
    val incomingVideoFrames: SharedFlow<Pair<Int, VideoFramePayload>> = _incomingVideoFrames.asSharedFlow()

    @Volatile
    var onRemoteClipboardReceived: ((ClipPayload) -> Unit)? = null

    @Volatile
    private var lastOriginHash: ByteArray = ByteArray(0)

    init {
        scope.launch { refreshLocalFiles() }

        scope.launch {
            socketClient.connectionState.collect { st ->
                if (st.isConnected) {
                    runCatching { requestFileList() }
                }
            }
        }

        scope.launch {
            socketClient.incomingFrames.collect { frame ->
                runCatching {
                    when (frame.header.type) {
                        ProtocolConstants.PACKET_TYPE_CLIP -> {
                            val clip = ClipPayload.decode(frame.payload)
                            if (!clip.originHash.contentEquals(lastOriginHash)) {
                                lastOriginHash = clip.originHash
                                _latestClipboard.value = clip.text
                                onRemoteClipboardReceived?.invoke(clip)
                            }
                        }
                        ProtocolConstants.PACKET_TYPE_FILE_INIT -> {
                            fileTransferManager.handleFileInit(FileInitPayload.decode(frame.payload))
                        }
                        ProtocolConstants.PACKET_TYPE_FILE_CHUNK -> {
                            fileTransferManager.handleFileChunk(FileChunkPayload.decode(frame.payload))
                        }
                        ProtocolConstants.PACKET_TYPE_FILE_DONE -> {
                            fileTransferManager.handleFileDone(
                                frame.header.flags,
                                FileDonePayload.decode(frame.payload)
                            )
                        }
                        ProtocolConstants.PACKET_TYPE_VIDEO_FRAME -> {
                            val vid = VideoFramePayload.decode(frame.payload)
                            _incomingVideoFrames.tryEmit(frame.header.flags to vid)
                        }
                        ProtocolConstants.PACKET_TYPE_CONTROL -> {
                            val ctrl = ControlMessage.decode(frame.payload)
                            handleControlMessage(ctrl)
                        }
                        else -> Unit
                    }
                    Unit
                }
            }
        }
    }

    private fun handleControlMessage(ctrl: ControlMessage) {
        when (ctrl.op) {
            ControlOps.FILE_LIST -> _remoteFiles.value = ctrl.files
            ControlOps.ERROR -> if (ctrl.message.isNotBlank()) _statusToast.value = ctrl.message
        }
    }

    override suspend fun connectAuto(manualHost: String?, port: Int) {
        socketClient.connectAuto(manualHost, port)
    }

    override suspend fun disconnect() {
        socketClient.disconnect()
    }

    override suspend fun sendFile(uri: Uri) {
        fileTransferManager.sendContentUri(appContext, uri)
    }

    override suspend fun sendClipboard(text: String) {
        val hash = ClipPayload.computeOriginHash(text)
        lastOriginHash = hash
        _latestClipboard.value = text
        val payload = ClipPayload(
            timestampMs = System.currentTimeMillis(),
            originHash = hash,
            mime = "text/plain;charset=utf-8",
            text = text
        ).encode()
        socketClient.sendFrame(ProtocolConstants.PACKET_TYPE_CLIP, 0, payload)
    }

    suspend fun sendInputEvent(ev: InputEventPayload) {
        socketClient.sendFrame(ProtocolConstants.PACKET_TYPE_INPUT_EVENT, 0, ev.encode())
    }

    suspend fun sendVideoFrame(
        flags: Int,
        header: VideoFrameHeader,
        nalData: ByteArray,
        nalOffset: Int = 0,
        nalLength: Int = nalData.size
    ) {
        socketClient.sendVideoFrame(flags, header, nalData, nalOffset, nalLength)
    }

    suspend fun sendControl(msg: ControlMessage) {
        socketClient.sendFrame(ProtocolConstants.PACKET_TYPE_CONTROL, 0, msg.encode())
    }

    suspend fun requestFileList() {
        sendControl(ControlMessage(op = ControlOps.LIST_FILES))
    }

    suspend fun pullRemoteFile(name: String) {
        sendControl(ControlMessage(op = ControlOps.PULL_FILE, name = name))
    }

    suspend fun deleteRemoteFile(name: String) {
        sendControl(ControlMessage(op = ControlOps.DELETE_FILE, name = name))
    }

    suspend fun requestStartDesktopStream() {
        sendControl(ControlMessage(op = ControlOps.START_DESKTOP_STREAM))
    }

    suspend fun requestStopDesktopStream() {
        sendControl(ControlMessage(op = ControlOps.STOP_DESKTOP_STREAM))
    }

    suspend fun requestLaunchScrcpy() {
        sendControl(ControlMessage(op = ControlOps.START_PHONE_SCRCPY))
    }

    suspend fun requestStopScrcpy() {
        sendControl(ControlMessage(op = ControlOps.STOP_PHONE_SCRCPY))
    }

    suspend fun refreshLocalFiles() {
        _localFiles.value = FileTransferManager.loadLocalReceivedFiles(appContext)
    }

    suspend fun deleteLocalFile(uriString: String) {
        if (FileTransferManager.deleteLocalFile(appContext, uriString)) {
            refreshLocalFiles()
        }
    }

    fun clearStatusToast() {
        _statusToast.value = null
    }

    override fun setMirrorMode(mode: MirrorMode) {
        _mirrorMode.value = mode
    }

    companion object {
        @Volatile
        private var instance: PhoneDropSessionHolder? = null

        fun getInstance(context: Context): PhoneDropSessionHolder {
            return instance ?: synchronized(this) {
                instance ?: PhoneDropSessionHolder(context.applicationContext).also { instance = it }
            }
        }
    }
}
