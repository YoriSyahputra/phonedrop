package com.phonedrop.v2.data.protocol

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
    const val UPDATE_MIRROR_CONFIG = "update_mirror_config"
    const val CONN_INFO = "conn_info"
    const val ERROR = "error"
}

data class MirrorConfig(
    val fps: Int = 0,               // 0 = Auto, 24, 30, 60, 90, 120, 144
    val bitrateMbps: Int = 8,       // 2, 4, 8, 12, 16, 24
    val maxSize: Int = 0,           // 0 = Native/Asli, 720, 1024, 1280, 1600, 1920
    val audio: Boolean = true,      // true = Audio ON, false = --no-audio
    val codec: String = "h264",     // "h264" | "h265"
    val turnScreenOff: Boolean = false,
    val stayAwake: Boolean = true,
    val alwaysOnTop: Boolean = false,
    val fullscreen: Boolean = false,
    val showTouches: Boolean = false
) {
    fun sanitize(): MirrorConfig = copy(
        fps = if (fps <= 0) 0 else fps.coerceIn(15, 240),
        bitrateMbps = if (bitrateMbps <= 0) 8 else bitrateMbps.coerceIn(1, 50),
        maxSize = if (maxSize <= 0) 0 else maxSize.coerceIn(480, 4096),
        codec = if (codec == "h265" || codec == "hevc") "h265" else "h264"
    )

    fun toJsonString(): String = buildString {
        append('{')
        append("\"fps\":").append(fps)
        append(",\"bitrate_mbps\":").append(bitrateMbps)
        append(",\"max_size\":").append(maxSize)
        append(",\"audio\":").append(audio)
        append(",\"codec\":").append(jsonQuote(codec))
        append(",\"turn_screen_off\":").append(turnScreenOff)
        append(",\"stay_awake\":").append(stayAwake)
        append(",\"always_on_top\":").append(alwaysOnTop)
        append(",\"fullscreen\":").append(fullscreen)
        append(",\"show_touches\":").append(showTouches)
        append('}')
    }

    companion object {
        fun fromJsonString(raw: String): MirrorConfig {
            val map = MiniJsonParser(raw).parseObjectOrNull()
                ?: throw ProtocolException("Invalid MirrorConfig JSON")
            return fromMap(map)
        }

        internal fun fromMap(map: Map<String, Any?>): MirrorConfig = MirrorConfig(
            fps = (map["fps"] as? Number)?.toInt() ?: 0,
            bitrateMbps = (map["bitrate_mbps"] as? Number)?.toInt() ?: 8,
            maxSize = (map["max_size"] as? Number)?.toInt() ?: 0,
            audio = (map["audio"] as? Boolean) ?: true,
            codec = (map["codec"] as? String) ?: "h264",
            turnScreenOff = (map["turn_screen_off"] as? Boolean) ?: false,
            stayAwake = (map["stay_awake"] as? Boolean) ?: true,
            alwaysOnTop = (map["always_on_top"] as? Boolean) ?: false,
            fullscreen = (map["fullscreen"] as? Boolean) ?: false,
            showTouches = (map["show_touches"] as? Boolean) ?: false
        ).sanitize()
    }
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
    val height: Int = 0,
    val mirror: MirrorConfig? = null
) {
    fun encode(): ByteArray {
        val json = buildString {
            append("{\"op\":").append(jsonQuote(op))
            if (name.isNotEmpty()) append(",\"name\":").append(jsonQuote(name))
            if (message.isNotEmpty()) append(",\"message\":").append(jsonQuote(message))
            if (linkType.isNotEmpty()) append(",\"link_type\":").append(jsonQuote(linkType))
            if (width > 0) append(",\"width\":").append(width)
            if (height > 0) append(",\"height\":").append(height)
            if (mirror != null) append(",\"mirror\":").append(mirror.toJsonString())
            if (files.isNotEmpty()) {
                append(",\"files\":[")
                files.forEachIndexed { idx, f ->
                    if (idx > 0) append(',')
                    append("{\"name\":").append(jsonQuote(f.name))
                    append(",\"size\":").append(f.size)
                    append(",\"mod_ms\":").append(f.modMs)
                    if (f.origin.isNotEmpty()) append(",\"origin\":").append(jsonQuote(f.origin))
                    append('}')
                }
                append(']')
            }
            append('}')
        }
        return json.toByteArray(Charsets.UTF_8)
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun decode(bytes: ByteArray): ControlMessage {
            val map = MiniJsonParser(String(bytes, Charsets.UTF_8)).parseObjectOrNull()
                ?: throw ProtocolException("Invalid ControlMessage JSON")
            val op = (map["op"] as? String).orEmpty()
            if (op.isEmpty()) throw ProtocolException("ControlMessage missing op")

            val list = mutableListOf<RemoteFileEntry>()
            val rawFiles = map["files"] as? List<*>
            rawFiles?.forEach { entry ->
                val item = entry as? Map<String, Any?> ?: return@forEach
                val n = (item["name"] as? String).orEmpty()
                if (n.isNotEmpty()) {
                    list.add(
                        RemoteFileEntry(
                            name = n,
                            size = (item["size"] as? Number)?.toLong() ?: 0L,
                            modMs = (item["mod_ms"] as? Number)?.toLong() ?: 0L,
                            origin = (item["origin"] as? String).orEmpty()
                        )
                    )
                }
            }
            val mirrorMap = map["mirror"] as? Map<String, Any?>
            return ControlMessage(
                op = op,
                name = (map["name"] as? String).orEmpty(),
                files = list,
                message = (map["message"] as? String).orEmpty(),
                linkType = (map["link_type"] as? String).orEmpty(),
                width = (map["width"] as? Number)?.toInt() ?: 0,
                height = (map["height"] as? Number)?.toInt() ?: 0,
                mirror = mirrorMap?.let { MirrorConfig.fromMap(it) }
            )
        }
    }
}

private fun jsonQuote(value: String): String = buildString(value.length + 4) {
    append('"')
    for (ch in value) {
        when (ch) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
        }
    }
    append('"')
}

private class MiniJsonParser(private val src: String) {
    private var idx = 0

    fun parseObjectOrNull(): Map<String, Any?>? {
        skipWs()
        if (idx >= src.length || src[idx] != '{') return null
        return runCatching { parseObject() }.getOrNull()
    }

    private fun skipWs() {
        while (idx < src.length && src[idx].isWhitespace()) idx++
    }

    private fun parseValue(): Any? {
        skipWs()
        if (idx >= src.length) throw IllegalStateException("EOF")
        return when (src[idx]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't' -> if (src.startsWith("true", idx)) { idx += 4; true } else throw IllegalStateException("bad bool")
            'f' -> if (src.startsWith("false", idx)) { idx += 5; false } else throw IllegalStateException("bad bool")
            'n' -> if (src.startsWith("null", idx)) { idx += 4; null } else throw IllegalStateException("bad null")
            else -> parseNumber()
        }
    }

    private fun parseObject(): Map<String, Any?> {
        idx++ // '{'
        val out = LinkedHashMap<String, Any?>()
        skipWs()
        if (idx < src.length && src[idx] == '}') {
            idx++
            return out
        }
        while (idx < src.length) {
            skipWs()
            val key = parseString()
            skipWs()
            if (idx >= src.length || src[idx] != ':') throw IllegalStateException("expected ':'")
            idx++
            out[key] = parseValue()
            skipWs()
            if (idx < src.length && src[idx] == ',') {
                idx++
            } else if (idx < src.length && src[idx] == '}') {
                idx++
                return out
            } else {
                throw IllegalStateException("expected ',' or '}'")
            }
        }
        return out
    }

    private fun parseArray(): List<Any?> {
        idx++ // '['
        val out = ArrayList<Any?>()
        skipWs()
        if (idx < src.length && src[idx] == ']') {
            idx++
            return out
        }
        while (idx < src.length) {
            out.add(parseValue())
            skipWs()
            if (idx < src.length && src[idx] == ',') {
                idx++
            } else if (idx < src.length && src[idx] == ']') {
                idx++
                return out
            } else {
                throw IllegalStateException("expected ',' or ']'")
            }
        }
        return out
    }

    private fun parseString(): String {
        if (src[idx] != '"') throw IllegalStateException("expected quote")
        idx++
        val sb = StringBuilder()
        while (idx < src.length) {
            val ch = src[idx++]
            when (ch) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (idx >= src.length) throw IllegalStateException("dangling escape")
                    when (val esc = src[idx++]) {
                        '"', '\\', '/' -> sb.append(esc)
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (idx + 4 > src.length) throw IllegalStateException("short unicode")
                            sb.append(src.substring(idx, idx + 4).toInt(16).toChar())
                            idx += 4
                        }
                        else -> sb.append(esc)
                    }
                }
                else -> sb.append(ch)
            }
        }
        throw IllegalStateException("unterminated string")
    }

    private fun parseNumber(): Number {
        val start = idx
        if (src[idx] == '-') idx++
        while (idx < src.length && (src[idx].isDigit() || src[idx] == '.' || src[idx] == 'e' || src[idx] == 'E' || src[idx] == '+' || src[idx] == '-')) {
            idx++
        }
        val token = src.substring(start, idx)
        return if (token.contains('.') || token.contains('e') || token.contains('E')) {
            token.toDouble()
        } else {
            token.toLong()
        }
    }
}

fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
