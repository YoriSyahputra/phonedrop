package com.phonedrop.v2.data.protocol

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

object ProtocolConstants {
    val MAGIC_BYTES = byteArrayOf(0x50, 0x44, 0x50, 0x32) // "PDP2"
    const val PROTOCOL_VERSION: Byte = 0x02
    const val HEADER_SIZE = 16
    const val CHUNK_SIZE = 64 * 1024 // 65,536 bytes
    const val MAX_PAYLOAD_SIZE = 4 * 1024 * 1024 // 4 MiB
    const val DEFAULT_PORT = 28200

    const val PACKET_TYPE_CLIP: Byte = 0x01
    const val PACKET_TYPE_FILE_INIT: Byte = 0x02
    const val PACKET_TYPE_FILE_CHUNK: Byte = 0x03
    const val PACKET_TYPE_FILE_DONE: Byte = 0x04
    const val PACKET_TYPE_VIDEO_FRAME: Byte = 0x05
    const val PACKET_TYPE_INPUT_EVENT: Byte = 0x06

    const val FLAG_ACK: Int = 0x01
    const val FLAG_ERROR: Int = 0x02
    const val FLAG_KEYFRAME: Int = 0x04
    const val FLAG_CODEC_CONFIG: Int = 0x08
    const val FLAG_DIR_LINUX_TO_PHONE: Int = 0x10
    const val FLAG_HEARTBEAT: Int = 0x80
}

data class FrameHeader(
    val version: Byte,
    val type: Byte,
    val flags: Int,
    val reserved: Byte,
    val seqId: Long,
    val payloadLen: Int
)

data class RawFrame(
    val header: FrameHeader,
    val payload: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RawFrame) return false
        return header == other.header && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = header.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

class ProtocolException(message: String) : IOException(message)

/**
 * Big-Endian PDP2 binary wire framer matching desktop-linux/core/protocol/framer.go.
 */
class PacketFramer {
    private val seqCounter = AtomicInteger(0)
    private val writeLock = Any()

    fun nextSeq(): Int = seqCounter.incrementAndGet()

    @Throws(IOException::class)
    fun writeFrame(out: OutputStream, type: Byte, flags: Int, payload: ByteArray) {
        if (payload.size > ProtocolConstants.MAX_PAYLOAD_SIZE) {
            throw ProtocolException("Payload exceeds MaxPayloadSize: ${payload.size}")
        }
        val hdr = encodeHeader(type, flags, nextSeq(), payload.size)
        synchronized(writeLock) {
            out.write(hdr)
            if (payload.isNotEmpty()) {
                out.write(payload)
            }
            out.flush()
        }
    }

    @Throws(IOException::class)
    fun writeChunkFrame(
        out: OutputStream,
        flags: Int,
        transferId: ByteArray,
        chunkIndex: Int,
        data: ByteArray,
        dataLen: Int
    ) {
        require(transferId.size == 16) { "transferId must be 16 bytes" }
        require(dataLen in 1..ProtocolConstants.CHUNK_SIZE) { "invalid dataLen=$dataLen" }

        val totalPayload = 24 + dataLen
        val hdr = encodeHeader(ProtocolConstants.PACKET_TYPE_FILE_CHUNK, flags, nextSeq(), totalPayload)
        val chunkMeta = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN).apply {
            put(transferId)
            putInt(chunkIndex)
            putInt(dataLen)
        }.array()

        synchronized(writeLock) {
            out.write(hdr)
            out.write(chunkMeta)
            out.write(data, 0, dataLen)
            out.flush()
        }
    }

    @Throws(IOException::class)
    fun writeVideoFrame(
        out: OutputStream,
        flags: Int,
        header: VideoFrameHeader,
        nalData: ByteArray,
        nalOffset: Int = 0,
        nalLength: Int = nalData.size
    ) {
        val totalPayload = 14 + nalLength
        if (totalPayload > ProtocolConstants.MAX_PAYLOAD_SIZE) {
            throw ProtocolException("Video payload exceeds MaxPayloadSize: $totalPayload")
        }
        val frameHdr = encodeHeader(ProtocolConstants.PACKET_TYPE_VIDEO_FRAME, flags, nextSeq(), totalPayload)
        val vidMeta = ByteBuffer.allocate(14).order(ByteOrder.BIG_ENDIAN).apply {
            put(header.streamId)
            put(header.codec)
            putShort(header.width.toShort())
            putShort(header.height.toShort())
            putLong(header.ptsUs)
        }.array()

        synchronized(writeLock) {
            out.write(frameHdr)
            out.write(vidMeta)
            if (nalLength > 0) {
                out.write(nalData, nalOffset, nalLength)
            }
            out.flush()
        }
    }

    companion object {
        fun encodeHeader(type: Byte, flags: Int, seqId: Int, payloadLen: Int): ByteArray {
            return ByteBuffer.allocate(ProtocolConstants.HEADER_SIZE).order(ByteOrder.BIG_ENDIAN).apply {
                put(ProtocolConstants.MAGIC_BYTES)
                put(ProtocolConstants.PROTOCOL_VERSION)
                put(type)
                put((flags and 0xFF).toByte())
                put(0.toByte())
                putInt(seqId)
                putInt(payloadLen)
            }.array()
        }

        @Throws(IOException::class)
        fun readFrame(input: InputStream): RawFrame? {
            val dis = input as? DataInputStream ?: DataInputStream(input)
            val hdrBuf = ByteArray(ProtocolConstants.HEADER_SIZE)
            try {
                dis.readFully(hdrBuf)
            } catch (_: EOFException) {
                return null
            }

            for (i in 0..3) {
                if (hdrBuf[i] != ProtocolConstants.MAGIC_BYTES[i]) {
                    throw ProtocolException("Invalid PDP2 magic bytes")
                }
            }
            val version = hdrBuf[4]
            if (version != ProtocolConstants.PROTOCOL_VERSION) {
                throw ProtocolException("Unsupported protocol version: $version")
            }
            val type = hdrBuf[5]
            val typeInt = type.toInt() and 0xFF
            if (typeInt < 0x01 || typeInt > 0x06) {
                throw ProtocolException("Unknown packet type: $typeInt")
            }
            val flags = hdrBuf[6].toInt() and 0xFF
            val reserved = hdrBuf[7]

            val bb = ByteBuffer.wrap(hdrBuf, 8, 8).order(ByteOrder.BIG_ENDIAN)
            val seqId = bb.int.toLong() and 0xFFFFFFFFL
            val payloadLenLong = bb.int.toLong() and 0xFFFFFFFFL
            if (payloadLenLong > ProtocolConstants.MAX_PAYLOAD_SIZE.toLong()) {
                throw ProtocolException("Payload length exceeds MaxPayloadSize: $payloadLenLong")
            }

            val payloadLen = payloadLenLong.toInt()
            val payload = ByteArray(payloadLen)
            if (payloadLen > 0) {
                dis.readFully(payload)
            }
            return RawFrame(
                header = FrameHeader(version, type, flags, reserved, seqId, payloadLen),
                payload = payload
            )
        }
    }
}
