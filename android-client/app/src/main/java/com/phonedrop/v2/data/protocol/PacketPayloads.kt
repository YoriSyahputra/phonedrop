package com.phonedrop.v2.data.protocol

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

object VideoConstants {
    const val STREAM_PHONE_TO_LINUX: Byte = 0x01
    const val STREAM_LINUX_TO_PHONE: Byte = 0x02

    const val CODEC_H264: Byte = 0x01
    const val CODEC_H265: Byte = 0x02
}

object InputConstants {
    const val INPUT_MOVE_REL: Byte = 0x01
    const val INPUT_TAP_ABS: Byte = 0x02
    const val INPUT_MOUSE_BUTTON: Byte = 0x03
    const val INPUT_SCROLL: Byte = 0x04
    const val INPUT_KEY_CODE: Byte = 0x05
    const val INPUT_TEXT_UTF8: Byte = 0x06
    const val INPUT_SWIPE_ABS: Byte = 0x07
}

data class ClipPayload(
    val timestampMs: Long,
    val originHash: ByteArray,
    val mime: String,
    val text: String
) {
    fun encode(): ByteArray {
        val mimeBytes = mime.toByteArray(Charsets.UTF_8)
        val textBytes = text.toByteArray(Charsets.UTF_8)
        require(originHash.size == 8) { "originHash must be 8 bytes" }
        require(mimeBytes.size <= 0xFFFF) { "mime too long" }
        return ByteBuffer.allocate(8 + 8 + 2 + mimeBytes.size + 4 + textBytes.size)
            .order(ByteOrder.BIG_ENDIAN)
            .apply {
                putLong(timestampMs)
                put(originHash)
                putShort(mimeBytes.size.toShort())
                put(mimeBytes)
                putInt(textBytes.size)
                put(textBytes)
            }.array()
    }

    companion object {
        fun computeOriginHash(text: String): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            return digest.copyOfRange(0, 8)
        }

        fun decode(bytes: ByteArray): ClipPayload {
            if (bytes.size < 22) throw ProtocolException("Clip payload too short: ${bytes.size}")
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val ts = bb.long
            val hash = ByteArray(8)
            bb.get(hash)
            val mimeLen = bb.short.toInt() and 0xFFFF
            if (bb.remaining() < mimeLen + 4) throw ProtocolException("Clip mime bounds invalid")
            val mimeBytes = ByteArray(mimeLen)
            bb.get(mimeBytes)
            val textLenLong = bb.int.toLong() and 0xFFFFFFFFL
            if (textLenLong != bb.remaining().toLong()) throw ProtocolException("Clip text bounds invalid")
            val textBytes = ByteArray(textLenLong.toInt())
            bb.get(textBytes)
            return ClipPayload(ts, hash, String(mimeBytes, Charsets.UTF_8), String(textBytes, Charsets.UTF_8))
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ClipPayload) return false
        return timestampMs == other.timestampMs &&
            originHash.contentEquals(other.originHash) &&
            mime == other.mime &&
            text == other.text
    }

    override fun hashCode(): Int {
        var r = timestampMs.hashCode()
        r = 31 * r + originHash.contentHashCode()
        r = 31 * r + mime.hashCode()
        r = 31 * r + text.hashCode()
        return r
    }
}

data class FileInitPayload(
    val transferId: ByteArray,
    val totalSize: Long,
    val chunkSize: Int,
    val totalChunks: Int,
    val fileName: String,
    val mimeType: String
) {
    fun encode(): ByteArray {
        val safeName = sanitizeFileName(fileName)
        val nameBytes = safeName.toByteArray(Charsets.UTF_8)
        val mimeBytes = mimeType.toByteArray(Charsets.UTF_8)
        require(transferId.size == 16) { "transferId must be 16 bytes" }
        return ByteBuffer.allocate(16 + 8 + 4 + 4 + 2 + nameBytes.size + 2 + mimeBytes.size)
            .order(ByteOrder.BIG_ENDIAN)
            .apply {
                put(transferId)
                putLong(totalSize)
                putInt(chunkSize)
                putInt(totalChunks)
                putShort(nameBytes.size.toShort())
                put(nameBytes)
                putShort(mimeBytes.size.toShort())
                put(mimeBytes)
            }.array()
    }

    companion object {
        fun sanitizeFileName(name: String): String {
            val normalized = name.replace('\\', '/').trim()
            val idx = normalized.lastIndexOf('/')
            val base = if (idx >= 0) normalized.substring(idx + 1) else normalized
            return if (base.isEmpty() || base == "." || base == "..") "phonedrop-file.bin" else base
        }

        fun decode(bytes: ByteArray): FileInitPayload {
            if (bytes.size < 36) throw ProtocolException("FileInit payload too short: ${bytes.size}")
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val tid = ByteArray(16)
            bb.get(tid)
            val totalSize = bb.long
            val chunkSize = bb.int
            val totalChunks = bb.int
            val nameLen = bb.short.toInt() and 0xFFFF
            if (bb.remaining() < nameLen + 2) throw ProtocolException("FileInit name bounds invalid")
            val nameBytes = ByteArray(nameLen)
            bb.get(nameBytes)
            val mimeLen = bb.short.toInt() and 0xFFFF
            if (bb.remaining() != mimeLen) throw ProtocolException("FileInit mime bounds invalid")
            val mimeBytes = ByteArray(mimeLen)
            bb.get(mimeBytes)
            return FileInitPayload(
                transferId = tid,
                totalSize = totalSize,
                chunkSize = chunkSize,
                totalChunks = totalChunks,
                fileName = sanitizeFileName(String(nameBytes, Charsets.UTF_8)),
                mimeType = String(mimeBytes, Charsets.UTF_8)
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FileInitPayload) return false
        return transferId.contentEquals(other.transferId) &&
            totalSize == other.totalSize &&
            chunkSize == other.chunkSize &&
            totalChunks == other.totalChunks &&
            fileName == other.fileName &&
            mimeType == other.mimeType
    }

    override fun hashCode(): Int {
        var r = transferId.contentHashCode()
        r = 31 * r + totalSize.hashCode()
        r = 31 * r + chunkSize
        r = 31 * r + totalChunks
        r = 31 * r + fileName.hashCode()
        r = 31 * r + mimeType.hashCode()
        return r
    }
}

data class FileChunkPayload(
    val transferId: ByteArray,
    val chunkIndex: Int,
    val data: ByteArray
) {
    companion object {
        fun decode(bytes: ByteArray): FileChunkPayload {
            if (bytes.size < 24) throw ProtocolException("FileChunk payload too short: ${bytes.size}")
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val tid = ByteArray(16)
            bb.get(tid)
            val chunkIndex = bb.int
            val dataLen = bb.int
            if (dataLen <= 0 || dataLen > ProtocolConstants.CHUNK_SIZE || bb.remaining() != dataLen) {
                throw ProtocolException("Invalid FileChunk dataLen=$dataLen remaining=${bb.remaining()}")
            }
            val data = ByteArray(dataLen)
            bb.get(data)
            return FileChunkPayload(tid, chunkIndex, data)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FileChunkPayload) return false
        return transferId.contentEquals(other.transferId) &&
            chunkIndex == other.chunkIndex &&
            data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var r = transferId.contentHashCode()
        r = 31 * r + chunkIndex
        r = 31 * r + data.contentHashCode()
        return r
    }
}

data class FileDonePayload(
    val transferId: ByteArray,
    val totalBytes: Long,
    val sha256: ByteArray,
    val statusCode: Byte
) {
    fun encode(): ByteArray {
        require(transferId.size == 16)
        require(sha256.size == 32)
        return ByteBuffer.allocate(57).order(ByteOrder.BIG_ENDIAN).apply {
            put(transferId)
            putLong(totalBytes)
            put(sha256)
            put(statusCode)
        }.array()
    }

    companion object {
        fun decode(bytes: ByteArray): FileDonePayload {
            if (bytes.size != 57) throw ProtocolException("FileDone payload must be 57 bytes, got ${bytes.size}")
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val tid = ByteArray(16)
            bb.get(tid)
            val totalBytes = bb.long
            val sha = ByteArray(32)
            bb.get(sha)
            val status = bb.get()
            return FileDonePayload(tid, totalBytes, sha, status)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FileDonePayload) return false
        return transferId.contentEquals(other.transferId) &&
            totalBytes == other.totalBytes &&
            sha256.contentEquals(other.sha256) &&
            statusCode == other.statusCode
    }

    override fun hashCode(): Int {
        var r = transferId.contentHashCode()
        r = 31 * r + totalBytes.hashCode()
        r = 31 * r + sha256.contentHashCode()
        r = 31 * r + statusCode
        return r
    }
}

data class VideoFrameHeader(
    val streamId: Byte,
    val codec: Byte,
    val width: Int,
    val height: Int,
    val ptsUs: Long
)

data class VideoFramePayload(
    val header: VideoFrameHeader,
    val nalData: ByteArray
) {
    companion object {
        fun decode(bytes: ByteArray): VideoFramePayload {
            if (bytes.size < 14) throw ProtocolException("VideoFrame too short: ${bytes.size}")
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val streamId = bb.get()
            val codec = bb.get()
            val width = bb.short.toInt() and 0xFFFF
            val height = bb.short.toInt() and 0xFFFF
            val ptsUs = bb.long
            val nal = ByteArray(bb.remaining())
            bb.get(nal)
            return VideoFramePayload(VideoFrameHeader(streamId, codec, width, height, ptsUs), nal)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VideoFramePayload) return false
        return header == other.header && nalData.contentEquals(other.nalData)
    }

    override fun hashCode(): Int = 31 * header.hashCode() + nalData.contentHashCode()
}

data class InputEventPayload(
    val eventType: Byte,
    val actionOrButton: Byte = 0,
    val x: Int = 0,
    val y: Int = 0,
    val arg1: Int = 0,
    val arg2: Int = 0,
    val text: String = ""
) {
    fun encode(): ByteArray {
        val textBytes = text.toByteArray(Charsets.UTF_8)
        require(textBytes.size <= 0xFFFF) { "input text exceeds uint16" }
        return ByteBuffer.allocate(22 + textBytes.size).order(ByteOrder.BIG_ENDIAN).apply {
            put(eventType)
            put(actionOrButton)
            putInt(x)
            putInt(y)
            putInt(arg1)
            putInt(arg2)
            putShort(textBytes.size.toShort())
            putShort(0) // reserved alignment bytes 20..21
            put(textBytes)
        }.array()
    }

    companion object {
        fun decode(bytes: ByteArray): InputEventPayload {
            if (bytes.size < 22) throw ProtocolException("InputEvent too short: ${bytes.size}")
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val evType = bb.get()
            val action = bb.get()
            val x = bb.int
            val y = bb.int
            val arg1 = bb.int
            val arg2 = bb.int
            val textLen = bb.short.toInt() and 0xFFFF
            bb.short // skip 2 reserved bytes
            if (bb.remaining() != textLen) throw ProtocolException("InputEvent text bounds mismatch")
            val textBytes = ByteArray(textLen)
            bb.get(textBytes)
            return InputEventPayload(evType, action, x, y, arg1, arg2, String(textBytes, Charsets.UTF_8))
        }
    }
}

object ControlOps {
    const val LIST_FILES = "list_files"
    const val FILE_LIST = "file_list"
    const val PULL_FILE = "pull_file"
    const val DELETE_FILE = "delete_file"
    const val START_DESKTOP_STREAM = "start_desktop_stream"
    const val STOP_DESKTOP_STREAM = "stop_desktop_stream"
    const val START_PHONE_SCRCPY = "start_phone_scrcpy"
    const val STOP_PHONE_SCRCPY = "stop_phone_scrcpy"
    const val REQUEST_KEYFRAME = "request_keyframe"
    const val CONN_INFO = "conn_info"
    const val ERROR = "error"
}

data class RemoteFileEntry(
    val name: String,
    val size: Long,
    val modMs: Long,
    val origin: String = ""
)

data class ControlMessage(
    val op: String,
    val name: String = "",
    val files: List<RemoteFileEntry> = emptyList(),
    val message: String = "",
    val linkType: String = "",
    val width: Int = 0,
    val height: Int = 0
) {
    fun encode(): ByteArray {
        val obj = JSONObject().apply {
            put("op", op)
            if (name.isNotEmpty()) put("name", name)
            if (message.isNotEmpty()) put("message", message)
            if (linkType.isNotEmpty()) put("link_type", linkType)
            if (width > 0) put("width", width)
            if (height > 0) put("height", height)
            if (files.isNotEmpty()) {
                val arr = JSONArray()
                files.forEach { f ->
                    arr.put(JSONObject().apply {
                        put("name", f.name)
                        put("size", f.size)
                        put("mod_ms", f.modMs)
                        if (f.origin.isNotEmpty()) put("origin", f.origin)
                    })
                }
                put("files", arr)
            }
        }
        return obj.toString().toByteArray(Charsets.UTF_8)
    }

    companion object {
        fun decode(bytes: ByteArray): ControlMessage {
            val obj = JSONObject(String(bytes, Charsets.UTF_8))
            val op = obj.optString("op", "")
            if (op.isEmpty()) throw ProtocolException("ControlMessage missing op")
            val list = mutableListOf<RemoteFileEntry>()
            val arr = obj.optJSONArray("files")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val n = item.optString("name", "")
                    if (n.isNotEmpty()) {
                        list.add(
                            RemoteFileEntry(
                                name = n,
                                size = item.optLong("size", 0L),
                                modMs = item.optLong("mod_ms", 0L),
                                origin = item.optString("origin", "")
                            )
                        )
                    }
                }
            }
            return ControlMessage(
                op = op,
                name = obj.optString("name", ""),
                files = list,
                message = obj.optString("message", ""),
                linkType = obj.optString("link_type", ""),
                width = obj.optInt("width", 0),
                height = obj.optInt("height", 0)
            )
        }
    }
}

fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
