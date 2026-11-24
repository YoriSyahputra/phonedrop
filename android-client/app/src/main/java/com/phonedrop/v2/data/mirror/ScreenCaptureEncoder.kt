package com.phonedrop.v2.data.mirror

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.phonedrop.v2.data.protocol.ProtocolConstants
import com.phonedrop.v2.data.protocol.VideoConstants
import com.phonedrop.v2.data.protocol.VideoFrameHeader
import com.phonedrop.v2.data.repository.PhoneDropSessionHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Hardware MediaCodec H.264 encoder driven by Android MediaProjection VirtualDisplay
 * streaming Annex-B NAL units to Linux over PDP2 VideoFrame packets.
 */
class ScreenCaptureEncoder(
    private val sessionHolder: PhoneDropSessionHolder,
    private val scope: CoroutineScope,
    private val onStopped: () -> Unit
) {
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var drainJob: Job? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stop()
            onStopped()
        }
    }

    fun start(projection: MediaProjection, width: Int, height: Int, densityDpi: Int, targetFps: Int = 60) {
        stop()
        val evenW = (width and 1.inv()).coerceAtLeast(360)
        val evenH = (height and 1.inv()).coerceAtLeast(640)

        mediaProjection = projection
        // Android 14 (API 34) requires callback registration BEFORE createVirtualDisplay.
        projection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))

        val candidates = listOf(targetFps, 120, 90, 60, 30)
            .filter { it in 30..240 && (it <= targetFps || it <= 60) }
            .distinct()

        var configuredCodec: MediaCodec? = null
        for (fps in candidates) {
            val bitrate = if (fps >= 90) 6_000_000 else 4_000_000
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, evenW, evenH).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            }
            var attempt: MediaCodec? = null
            try {
                attempt = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                attempt.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                configuredCodec = attempt
                break
            } catch (e: Exception) {
                Log.w("ScreenCaptureEncoder", "AVC encoder rejected ${fps}fps: ${e.message}")
                runCatching { attempt?.release() }
            }
        }

        val codec = configuredCodec ?: return
        val surf = codec.createInputSurface()
        codec.start()

        val vd = projection.createVirtualDisplay(
            "PhoneDropV2Capture",
            evenW,
            evenH,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surf,
            null,
            null
        )

        encoder = codec
        inputSurface = surf
        virtualDisplay = vd

        drainJob = scope.launch(Dispatchers.IO) {
            drainLoop(codec, evenW, evenH)
        }
    }

    private suspend fun drainLoop(codec: MediaCodec, width: Int, height: Int) {
        val info = MediaCodec.BufferInfo()
        try {
            while (scope.isActive && encoder === codec) {
                val idx = codec.dequeueOutputBuffer(info, 20_000)
                if (idx < 0) continue
                try {
                    val outBuf = codec.getOutputBuffer(idx)
                    if (outBuf != null && info.size > 0) {
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        val bytes = ByteArray(info.size)
                        outBuf.get(bytes)

                        var flags = 0
                        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            flags = flags or ProtocolConstants.FLAG_CODEC_CONFIG
                        }
                        if ((info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                            flags = flags or ProtocolConstants.FLAG_KEYFRAME
                        }

                        runCatching {
                            sessionHolder.sendVideoFrame(
                                flags = flags,
                                header = VideoFrameHeader(
                                    streamId = VideoConstants.STREAM_PHONE_TO_LINUX,
                                    codec = VideoConstants.CODEC_H264,
                                    width = width,
                                    height = height,
                                    ptsUs = info.presentationTimeUs
                                ),
                                nalData = bytes
                            )
                        }
                    }
                } finally {
                    codec.releaseOutputBuffer(idx, false)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("ScreenCaptureEncoder", "Encode drain stopped: ${e.message}")
        }
    }

    fun stop() {
        drainJob?.cancel()
        drainJob = null
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null
        try {
            encoder?.stop()
        } catch (_: Exception) {
        }
        try {
            encoder?.release()
        } catch (_: Exception) {
        }
        encoder = null
        try {
            inputSurface?.release()
        } catch (_: Exception) {
        }
        inputSurface = null
        try {
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
        } catch (_: Exception) {
        }
        mediaProjection = null
    }
}
