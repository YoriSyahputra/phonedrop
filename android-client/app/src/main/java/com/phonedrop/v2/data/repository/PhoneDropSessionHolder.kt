package com.phonedrop.v2.data.repository

import android.content.Context
import android.net.Uri
import com.phonedrop.v2.data.network.BinarySocketClient
import com.phonedrop.v2.data.network.MdnsDiscoveryManager
import com.phonedrop.v2.data.protocol.ClipPayload
import com.phonedrop.v2.data.protocol.FileChunkPayload
import com.phonedrop.v2.data.protocol.FileDonePayload
import com.phonedrop.v2.data.protocol.FileInitPayload
import com.phonedrop.v2.data.protocol.InputEventPayload
import com.phonedrop.v2.data.protocol.ProtocolConstants
import com.phonedrop.v2.data.protocol.VideoFrameHeader
import com.phonedrop.v2.data.protocol.VideoFramePayload
import com.phonedrop.v2.domain.model.ConnectionState
import com.phonedrop.v2.domain.model.MirrorMode
import com.phonedrop.v2.domain.model.TransferState
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
 * and Jetpack Compose ViewModels via reactive StateFlow/SharedFlow streams.
 */
class PhoneDropSessionHolder private constructor(
    private val appContext: Context
) : PhoneDropRepository {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val socketClient = BinarySocketClient(scope, MdnsDiscoveryManager(appContext))
    val fileTransferManager = FileTransferManager(
        socketClient = socketClient,
        outputSink = MediaStoreTransferSink(appContext)
    )

    override val connectionState: StateFlow<ConnectionState> = socketClient.connectionState
    override val transfers: StateFlow<Map<String, TransferState>> = fileTransferManager.transfers

    private val _latestClipboard = MutableStateFlow("")
    override val latestClipboard: StateFlow<String> = _latestClipboard.asStateFlow()

    private val _mirrorMode = MutableStateFlow(MirrorMode.IDLE)
    override val mirrorMode: StateFlow<MirrorMode> = _mirrorMode.asStateFlow()

    private val _incomingVideoFrames = MutableSharedFlow<Pair<Int, VideoFramePayload>>(extraBufferCapacity = 64)
    val incomingVideoFrames: SharedFlow<Pair<Int, VideoFramePayload>> = _incomingVideoFrames.asSharedFlow()

    @Volatile
    var onRemoteClipboardReceived: ((ClipPayload) -> Unit)? = null

    @Volatile
    private var lastOriginHash: ByteArray = ByteArray(0)

    init {
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
                        else -> Unit
                    }
                    Unit
                }
            }
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
        if (hash.contentEquals(lastOriginHash)) return
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
