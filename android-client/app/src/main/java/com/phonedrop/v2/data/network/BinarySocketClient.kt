package com.phonedrop.v2.data.network

import android.util.Log
import com.phonedrop.v2.data.protocol.PacketFramer
import com.phonedrop.v2.data.protocol.ProtocolConstants
import com.phonedrop.v2.data.protocol.RawFrame
import com.phonedrop.v2.data.protocol.VideoFrameHeader
import com.phonedrop.v2.domain.model.ConnectionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Dual-mode coroutine TCP client supporting zero-config ADB USB loopback (127.0.0.1:28200)
 * and Wi-Fi LAN endpoints discovered via mDNS.
 */
class BinarySocketClient(
    private val scope: CoroutineScope,
    private val mdnsDiscovery: MdnsDiscoveryManager? = null
) {
    private val framer = PacketFramer()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _incomingFrames = MutableSharedFlow<RawFrame>(extraBufferCapacity = 128)
    val incomingFrames: SharedFlow<RawFrame> = _incomingFrames.asSharedFlow()

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var outputStream: OutputStream? = null

    private var readerJob: Job? = null
    private var heartbeatJob: Job? = null

    suspend fun connectAuto(manualHost: String? = null, port: Int = ProtocolConstants.DEFAULT_PORT) {
        disconnect()

        if (!manualHost.isNullOrBlank()) {
            val cleanHost = manualHost.trim()
            val isUsb = cleanHost == "127.0.0.1" || cleanHost == "localhost"
            connectDirect(cleanHost, port, isUsb, timeoutMs = 2500)
            return
        }

        // Priority 1: Zero-config USB tunnel via `adb reverse tcp:28200 tcp:28200`
        _connectionState.value = ConnectionState.Connecting("127.0.0.1:$port (USB)")
        if (tryConnect("127.0.0.1", port, isUsb = true, timeoutMs = 650)) {
            return
        }

        // Priority 2: Wi-Fi LAN mDNS discovery (_phonedrop._tcp.)
        _connectionState.value = ConnectionState.Connecting("mDNS Wi-Fi LAN...")
        val discovered = mdnsDiscovery?.discoverFirst(timeoutMs = 3000)
        if (discovered != null) {
            if (tryConnect(discovered.first, discovered.second, isUsb = false, timeoutMs = 2500)) {
                return
            }
        }

        _connectionState.value = ConnectionState.Error("No PhoneDrop desktop found over USB or LAN")
    }

    suspend fun connectDirect(host: String, port: Int = ProtocolConstants.DEFAULT_PORT, isUsb: Boolean = false, timeoutMs: Int = 2500): Boolean {
        disconnect()
        _connectionState.value = ConnectionState.Connecting("$host:$port")
        val ok = tryConnect(host, port, isUsb, timeoutMs)
        if (!ok) {
            _connectionState.value = ConnectionState.Error("Failed to connect to $host:$port")
        }
        return ok
    }

    private suspend fun tryConnect(host: String, port: Int, isUsb: Boolean, timeoutMs: Int): Boolean =
        withContext(Dispatchers.IO) {
            val s = Socket()
            try {
                s.tcpNoDelay = true
                s.keepAlive = true
                s.soTimeout = 0
                s.connect(InetSocketAddress(host, port), timeoutMs)
                val out = BufferedOutputStream(s.getOutputStream(), 65536)
                val inp = DataInputStream(BufferedInputStream(s.getInputStream(), 65536))

                socket = s
                outputStream = out
                val endpoint = "$host:$port"
                _connectionState.value = if (isUsb) {
                    ConnectionState.UsbConnected(endpoint)
                } else {
                    ConnectionState.WifiConnected(endpoint)
                }

                startLoops(s, inp)
                true
            } catch (e: IOException) {
                try {
                    s.close()
                } catch (_: Exception) {
                }
                false
            }
        }

    private fun startLoops(sock: Socket, inp: DataInputStream) {
        readerJob = scope.launch(Dispatchers.IO) {
            try {
                while (isActive && !sock.isClosed) {
                    val frame = PacketFramer.readFrame(inp) ?: break
                    if ((frame.header.flags and ProtocolConstants.FLAG_HEARTBEAT) != 0) {
                        continue
                    }
                    _incomingFrames.emit(frame)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("BinarySocketClient", "Reader ended: ${e.message}")
            } finally {
                if (socket === sock) {
                    disconnectInternal()
                }
            }
        }

        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive && !sock.isClosed) {
                delay(10_000)
                try {
                    sendFrame(
                        ProtocolConstants.PACKET_TYPE_CLIP,
                        ProtocolConstants.FLAG_HEARTBEAT,
                        ByteArray(0)
                    )
                } catch (_: Exception) {
                    break
                }
            }
        }
    }

    suspend fun sendFrame(type: Byte, flags: Int, payload: ByteArray) = withContext(Dispatchers.IO) {
        val out = outputStream ?: throw IOException("Socket not connected")
        framer.writeFrame(out, type, flags, payload)
    }

    suspend fun sendChunkFrame(
        flags: Int,
        transferId: ByteArray,
        chunkIndex: Int,
        data: ByteArray,
        dataLen: Int
    ) = withContext(Dispatchers.IO) {
        val out = outputStream ?: throw IOException("Socket not connected")
        framer.writeChunkFrame(out, flags, transferId, chunkIndex, data, dataLen)
    }

    suspend fun sendVideoFrame(
        flags: Int,
        header: VideoFrameHeader,
        nalData: ByteArray,
        nalOffset: Int = 0,
        nalLength: Int = nalData.size
    ) = withContext(Dispatchers.IO) {
        val out = outputStream ?: throw IOException("Socket not connected")
        framer.writeVideoFrame(out, flags, header, nalData, nalOffset, nalLength)
    }

    fun disconnect() {
        readerJob?.cancel()
        heartbeatJob?.cancel()
        disconnectInternal()
    }

    private fun disconnectInternal() {
        heartbeatJob?.cancel()
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        outputStream = null
        _connectionState.value = ConnectionState.Disconnected
    }
}
