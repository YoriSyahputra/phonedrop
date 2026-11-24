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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Hardware MediaCodec H.264/H.265 decoder rendering incoming Linux desktop frames
 * (StreamID = STREAM_LINUX_TO_PHONE) directly to an Android SurfaceView Surface.
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
    private var feedJob: Job? = null

    fun attachSurface(surface: Surface) {
        synchronized(lock) {
            activeSurface = surface
        }
        startCollectLoop()
    }

    fun detach() {
        feedJob?.cancel()
        feedJob = null
        synchronized(lock) {
            activeSurface = null
            releaseDecoderLocked()
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
                    ensureDecoder(vid.header.codec, w, h) ?: return@collect
                    feedSample(flags, vid.header.ptsUs, vid.nalData)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("VideoStreamDecoder", "Decoder stream stopped: ${e.message}")
            }
        }
    }

    private fun ensureDecoder(codecByte: Byte, width: Int, height: Int): MediaCodec? {
        synchronized(lock) {
            val surf = activeSurface ?: return null
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
            }
            val mc = MediaCodec.createDecoderByType(mime)
            mc.configure(format, surf, null, 0)
            mc.start()
            decoder = mc
            currentWidth = width
            currentHeight = height
            currentCodec = codecByte
            return mc
        }
    }

    private fun feedSample(flags: Int, ptsUs: Long, nalData: ByteArray) {
        val mc = synchronized(lock) { decoder } ?: return
        try {
            val inIdx = mc.dequeueInputBuffer(10_000)
            if (inIdx >= 0) {
                val inputBuf = mc.getInputBuffer(inIdx) ?: return
                inputBuf.clear()
                if (nalData.size > inputBuf.capacity()) {
                    mc.queueInputBuffer(inIdx, 0, 0, ptsUs, 0)
                } else {
                    inputBuf.put(nalData)
                    var codecFlags = 0
                    if ((flags and ProtocolConstants.FLAG_CODEC_CONFIG) != 0) {
                        codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                    }
                    if ((flags and ProtocolConstants.FLAG_KEYFRAME) != 0) {
                        codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_KEY_FRAME
                    }
                    mc.queueInputBuffer(inIdx, 0, nalData.size, ptsUs, codecFlags)
                }
            }

            val info = MediaCodec.BufferInfo()
            while (true) {
                val outIdx = mc.dequeueOutputBuffer(info, 0)
                if (outIdx >= 0) {
                    mc.releaseOutputBuffer(outIdx, true)
                } else {
                    break
                }
            }
        } catch (e: Exception) {
            Log.w("VideoStreamDecoder", "Decode frame error: ${e.message}")
            synchronized(lock) { releaseDecoderLocked() }
        }
    }

    private fun releaseDecoderLocked() {
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
}
