package com.phonedrop.v2.data.mirror

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import com.phonedrop.v2.data.protocol.ProtocolConstants
import com.phonedrop.v2.data.protocol.VideoConstants
import com.phonedrop.v2.data.repository.PhoneDropSessionHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

sealed class DecoderStatus {
    data object Idle : DecoderStatus()
    data object WaitingForStream : DecoderStatus()
    data class Live(val width: Int, val height: Int) : DecoderStatus()
    data class Error(val message: String) : DecoderStatus()
}

/**
 * Hardware MediaCodec H.264/H.265 decoder rendering incoming Linux desktop frames
 * (StreamID = STREAM_LINUX_TO_PHONE) directly onto an Android SurfaceView Surface.
 * Buffers SPS (csd-0) + PPS (csd-1) and gates decoding on the first IDR keyframe.
 */
class VideoStreamDecoder(
    private val sessionHolder: PhoneDropSessionHolder,
    private val scope: CoroutineScope
) {
    private val lock = Any()
    private var decoder: MediaCodec? = null
    private var activeSurface: Surface? = null
    private var currentWidth = 0
    private var currentHeight = 0
    private var currentCodec: Byte = VideoConstants.CODEC_H264

    private var cachedSps: ByteArray? = null
    private var cachedPps: ByteArray? = null
    private var seenKeyframe = false

    private var feedJob: Job? = null
    private var drainJob: Job? = null

    private val _status = MutableStateFlow<DecoderStatus>(DecoderStatus.Idle)
    val status: StateFlow<DecoderStatus> = _status.asStateFlow()

    fun attachSurface(surface: Surface) {
        synchronized(lock) {
            activeSurface = surface
            seenKeyframe = false
            _status.value = DecoderStatus.WaitingForStream
        }
        startCollectLoop()
    }

    fun detach() {
        feedJob?.cancel()
        feedJob = null
        drainJob?.cancel()
        drainJob = null
        synchronized(lock) {
            activeSurface = null
            cachedSps = null
            cachedPps = null
            seenKeyframe = false
            releaseDecoderLocked()
            _status.value = DecoderStatus.Idle
        }
    }

    private fun startCollectLoop() {
        feedJob?.cancel()
        feedJob = scope.launch(Dispatchers.IO) {
            try {
                sessionHolder.incomingVideoFrames.collect { (flags, vid) ->
                    if (!isActive) return@collect
                    if (vid.header.streamId != VideoConstants.STREAM_LINUX_TO_PHONE) return@collect
                    if (vid.nalData.isEmpty()) return@collect

                    val w = if (vid.header.width > 0) vid.header.width else 1280
                    val h = if (vid.header.height > 0) vid.header.height else 720
                    handleIncomingPacket(flags, vid.header.codec, w, h, vid.header.ptsUs, vid.nalData)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("VideoStreamDecoder", "Decoder stream stopped: ${e.message}")
                _status.value = DecoderStatus.Error(e.message ?: "Stream stopped")
            }
        }
    }

    private fun handleIncomingPacket(
        flags: Int,
        codecByte: Byte,
        width: Int,
        height: Int,
        ptsUs: Long,
        nalData: ByteArray
    ) {
        val units = splitAnnexBUnits(nalData)
        val nonConfigUnits = mutableListOf<ByteArray>()
        var packetHasKeyframe = (flags and ProtocolConstants.FLAG_KEYFRAME) != 0

        for (u in units) {
            val scLen = startCodeLen(u)
            if (scLen <= 0 || u.size <= scLen) continue
            if (codecByte == VideoConstants.CODEC_H264) {
                val nalType = u[scLen].toInt() and 0x1F
                when (nalType) {
                    7 -> {
                        synchronized(lock) {
                            if (cachedSps == null || !cachedSps!!.contentEquals(u)) {
                                cachedSps = u
                            }
                        }
                        continue
                    }
                    8 -> {
                        synchronized(lock) {
                            if (cachedPps == null || !cachedPps!!.contentEquals(u)) {
                                cachedPps = u
                            }
                        }
                        continue
                    }
                    5 -> packetHasKeyframe = true
                }
            } else {
                val nalType = (u[scLen].toInt() shr 1) and 0x3F
                if (nalType in 32..34) continue
                if (nalType in 19..21) packetHasKeyframe = true
            }
            nonConfigUnits.add(u)
        }

        val mc = ensureConfiguredDecoder(codecByte, width, height) ?: return
        if (nonConfigUnits.isEmpty()) return

        if (!seenKeyframe) {
            if (!packetHasKeyframe) {
                return
            }
            seenKeyframe = true
        }

        val frameBytes = if (nonConfigUnits.size == 1) {
            nonConfigUnits[0]
        } else {
            val total = nonConfigUnits.sumOf { it.size }
            ByteArray(total).also { dst ->
                var off = 0
                for (u in nonConfigUnits) {
                    System.arraycopy(u, 0, dst, off, u.size)
                    off += u.size
                }
            }
        }

        val codecFlags = if (packetHasKeyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
        try {
            val inIdx = mc.dequeueInputBuffer(10_000)
            if (inIdx >= 0) {
                val inputBuf = mc.getInputBuffer(inIdx) ?: return
                inputBuf.clear()
                if (frameBytes.size <= inputBuf.capacity()) {
                    inputBuf.put(frameBytes)
                    mc.queueInputBuffer(inIdx, 0, frameBytes.size, ptsUs, codecFlags)
                } else {
                    mc.queueInputBuffer(inIdx, 0, 0, ptsUs, 0)
                }
            }
        } catch (e: Exception) {
            Log.w("VideoStreamDecoder", "Queue input error: ${e.message}")
            synchronized(lock) {
                seenKeyframe = false
                releaseDecoderLocked()
            }
            _status.value = DecoderStatus.Error(e.message ?: "Decoder reset")
        }
    }

    private fun ensureConfiguredDecoder(codecByte: Byte, width: Int, height: Int): MediaCodec? {
        synchronized(lock) {
            val surf = activeSurface ?: return null
            if (!surf.isValid) return null
            val sps = cachedSps
            val pps = cachedPps
            if (codecByte == VideoConstants.CODEC_H264 && (sps == null || pps == null)) {
                return null
            }

            if (decoder != null &&
                currentWidth == width &&
                currentHeight == height &&
                currentCodec == codecByte
            ) {
                return decoder
            }

            releaseDecoderLocked()

            val mime = if (codecByte == VideoConstants.CODEC_H265) {
                MediaFormat.MIMETYPE_VIDEO_HEVC
            } else {
                MediaFormat.MIMETYPE_VIDEO_AVC
            }
            val format = MediaFormat.createVideoFormat(mime, width, height).apply {
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
                if (sps != null && pps != null) {
                    setByteBuffer("csd-0", ByteBuffer.wrap(sps))
                    setByteBuffer("csd-1", ByteBuffer.wrap(pps))
                }
            }

            return try {
                val mc = MediaCodec.createDecoderByType(mime)
                mc.configure(format, surf, null, 0)
                mc.start()
                decoder = mc
                currentWidth = width
                currentHeight = height
                currentCodec = codecByte
                seenKeyframe = false
                _status.value = DecoderStatus.Live(width, height)
                startDrainLoop(mc)
                mc
            } catch (e: Exception) {
                Log.e("VideoStreamDecoder", "Failed configuring MediaCodec: ${e.message}", e)
                _status.value = DecoderStatus.Error("Codec error: ${e.message}")
                null
            }
        }
    }

    private fun startDrainLoop(mc: MediaCodec) {
        drainJob?.cancel()
        drainJob = scope.launch(Dispatchers.Default) {
            val info = MediaCodec.BufferInfo()
            while (isActive) {
                val active = synchronized(lock) { decoder === mc }
                if (!active) break
                try {
                    val outIdx = mc.dequeueOutputBuffer(info, 10_000)
                    when {
                        outIdx >= 0 -> mc.releaseOutputBuffer(outIdx, true)
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val fmt = mc.outputFormat
                            val w = fmt.getInteger(MediaFormat.KEY_WIDTH)
                            val h = fmt.getInteger(MediaFormat.KEY_HEIGHT)
                            _status.value = DecoderStatus.Live(w, h)
                        }
                    }
                } catch (_: IllegalStateException) {
                    break
                } catch (_: Exception) {
                    break
                }
            }
        }
    }

    private fun releaseDecoderLocked() {
        drainJob?.cancel()
        drainJob = null
        try {
            decoder?.stop()
        } catch (_: Exception) {
        }
        try {
            decoder?.release()
        } catch (_: Exception) {
        }
        decoder = null
        currentWidth = 0
        currentHeight = 0
    }

    private fun splitAnnexBUnits(data: ByteArray): List<ByteArray> {
        val starts = mutableListOf<Int>()
        var i = 0
        while (i + 2 < data.size) {
            if (i + 3 < data.size && data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                starts.add(i)
                i += 4
                continue
            }
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                starts.add(i)
                i += 3
                continue
            }
            i++
        }
        if (starts.isEmpty()) return listOf(data)
        val out = ArrayList<ByteArray>(starts.size)
        for (idx in starts.indices) {
            val from = starts[idx]
            val to = if (idx + 1 < starts.size) starts[idx + 1] else data.size
            if (to > from) {
                out.add(data.copyOfRange(from, to))
            }
        }
        return out
    }

    private fun startCodeLen(u: ByteArray): Int {
        if (u.size >= 4 && u[0] == 0.toByte() && u[1] == 0.toByte() && u[2] == 0.toByte() && u[3] == 1.toByte()) return 4
        if (u.size >= 3 && u[0] == 0.toByte() && u[1] == 0.toByte() && u[2] == 1.toByte()) return 3
        return 0
    }
}
