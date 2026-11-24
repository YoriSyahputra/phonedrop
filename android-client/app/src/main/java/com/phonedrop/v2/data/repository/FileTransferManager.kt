package com.phonedrop.v2.data.repository

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.phonedrop.v2.data.network.BinarySocketClient
import com.phonedrop.v2.data.protocol.FileChunkPayload
import com.phonedrop.v2.data.protocol.FileDonePayload
import com.phonedrop.v2.data.protocol.FileInitPayload
import com.phonedrop.v2.data.protocol.ProtocolConstants
import com.phonedrop.v2.data.protocol.toHexString
import com.phonedrop.v2.domain.model.LocalReceivedFile
import com.phonedrop.v2.domain.model.TransferDirection
import com.phonedrop.v2.domain.model.TransferState
import com.phonedrop.v2.domain.model.TransferStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Abstraction for opening destination files cleanly on Android Scoped Storage (MediaStore)
 * or plain directory sinks (API 26-28 and JVM integration tests).
 */
interface TransferOutputSink {
    fun openOutput(fileName: String, mimeType: String): OutputHandle
}

interface OutputHandle {
    val stream: OutputStream
    fun commit(): String?
    fun abort()
}

class DirectoryTransferSink(private val dir: File) : TransferOutputSink {
    override fun openOutput(fileName: String, mimeType: String): OutputHandle {
        if (!dir.exists()) dir.mkdirs()
        val safeName = FileInitPayload.sanitizeFileName(fileName)
        val partFile = File(dir, ".phonedrop-${System.nanoTime()}-$safeName.part")
        val fos = BufferedOutputStream(FileOutputStream(partFile), ProtocolConstants.CHUNK_SIZE)
        return object : OutputHandle {
            override val stream: OutputStream = fos
            override fun commit(): String {
                fos.flush()
                fos.close()
                val target = uniqueFile(dir, safeName)
                if (!partFile.renameTo(target)) {
                    partFile.copyTo(target, overwrite = true)
                    partFile.delete()
                }
                return target.toURI().toString()
            }

            override fun abort() {
                try {
                    fos.close()
                } catch (_: Exception) {
                }
                partFile.delete()
            }
        }
    }

    private fun uniqueFile(dir: File, name: String): File {
        val candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        for (i in 1..9999) {
            val alt = File(dir, "$stem ($i)$ext")
            if (!alt.exists()) return alt
        }
        return File(dir, "${System.currentTimeMillis()}-$name")
    }
}

class MediaStoreTransferSink(private val context: Context) : TransferOutputSink {
    private val legacyFallback = DirectoryTransferSink(
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "PhoneDrop"
        )
    )

    override fun openOutput(fileName: String, mimeType: String): OutputHandle {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return legacyFallback.openOutput(fileName, mimeType)
        }
        val resolver = context.contentResolver
        val safeName = FileInitPayload.sanitizeFileName(fileName)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, safeName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType.ifBlank { "application/octet-stream" })
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/PhoneDrop")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return legacyFallback.openOutput(safeName, mimeType)
        val rawStream = resolver.openOutputStream(uri)
            ?: return legacyFallback.openOutput(safeName, mimeType)
        val buffered = BufferedOutputStream(rawStream, ProtocolConstants.CHUNK_SIZE)

        return object : OutputHandle {
            override val stream: OutputStream = buffered
            override fun commit(): String {
                buffered.flush()
                buffered.close()
                val doneValues = ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }
                resolver.update(uri, doneValues, null, null)
                return uri.toString()
            }

            override fun abort() {
                try {
                    buffered.close()
                } catch (_: Exception) {
                }
                resolver.delete(uri, null, null)
            }
        }
    }
}

/**
 * Streaming 64 KiB chunk sender/receiver with zero full-file memory buffering,
 * sliding-window EMA MB/s speed tracking, and SHA-256 FileDone ACK verification.
 */
class FileTransferManager(
    private val socketClient: BinarySocketClient,
    private val outputSink: TransferOutputSink,
    private val onProgressCallback: ((TransferState) -> Unit)? = null
) {
    private class SpeedTracker(startNs: Long = System.nanoTime()) {
        private val startTimeNs = startNs
        private var lastSampleNs = startNs
        private var lastBytes = 0L
        private var emaMBps = 0.0

        fun update(totalTransferred: Long, nowNs: Long = System.nanoTime()): Pair<Double, Boolean> {
            val elapsedNs = nowNs - lastSampleNs
            if (elapsedNs < 50_000_000L) {
                return emaMBps to false
            }
            val deltaBytes = (totalTransferred - lastBytes).coerceAtLeast(0L)
            val secs = elapsedNs / 1_000_000_000.0
            val inst = (deltaBytes / (1024.0 * 1024.0)) / secs
            emaMBps = if (emaMBps == 0.0) inst else 0.35 * inst + 0.65 * emaMBps
            lastSampleNs = nowNs
            lastBytes = totalTransferred
            return emaMBps to true
        }

        fun finalSpeed(totalBytes: Long, nowNs: Long = System.nanoTime()): Double {
            if (emaMBps > 0.0) return emaMBps
            val secs = (nowNs - startTimeNs) / 1_000_000_000.0
            return if (secs > 0.0) (totalBytes / (1024.0 * 1024.0)) / secs else 0.0
        }
    }

    private class InboundTransfer(
        val idHex: String,
        val fileName: String,
        val totalBytes: Long,
        val totalChunks: Int,
        val handle: OutputHandle,
        val digest: MessageDigest = MessageDigest.getInstance("SHA-256"),
        val speed: SpeedTracker = SpeedTracker(),
        var bytesWritten: Long = 0L,
        var chunksWritten: Int = 0
    )

    private val _transfers = MutableStateFlow<Map<String, TransferState>>(emptyMap())
    val transfers: StateFlow<Map<String, TransferState>> = _transfers.asStateFlow()

    private val inboundMap = ConcurrentHashMap<String, InboundTransfer>()
    private val pendingAcks = ConcurrentHashMap<String, CompletableDeferred<Pair<Int, FileDonePayload>>>()
    private val rng = SecureRandom()

    private fun publishState(state: TransferState) {
        _transfers.update { current -> current + (state.transferIdHex to state) }
        onProgressCallback?.invoke(state)
    }

    fun dismissTransfer(transferIdHex: String) {
        _transfers.update { current -> current - transferIdHex }
    }

    suspend fun handleFileInit(init: FileInitPayload) {
        val tidHex = init.transferId.toHexString()
        try {
            val handle = withContext(Dispatchers.IO) {
                outputSink.openOutput(init.fileName, init.mimeType)
            }
            val tr = InboundTransfer(
                idHex = tidHex,
                fileName = init.fileName,
                totalBytes = init.totalSize,
                totalChunks = init.totalChunks,
                handle = handle
            )
            inboundMap.put(tidHex, tr)?.handle?.abort()
            publishState(
                TransferState(
                    transferIdHex = tidHex,
                    fileName = init.fileName,
                    direction = TransferDirection.RECEIVE,
                    bytesTransferred = 0L,
                    totalBytes = init.totalSize,
                    percent = 0f,
                    speedMBps = 0.0,
                    chunkIndex = 0,
                    totalChunks = init.totalChunks,
                    status = TransferStatus.IN_PROGRESS
                )
            )
        } catch (e: Exception) {
            publishState(
                TransferState(
                    transferIdHex = tidHex,
                    fileName = init.fileName,
                    direction = TransferDirection.RECEIVE,
                    bytesTransferred = 0L,
                    totalBytes = init.totalSize,
                    percent = 0f,
                    speedMBps = 0.0,
                    chunkIndex = 0,
                    totalChunks = init.totalChunks,
                    status = TransferStatus.FAILED,
                    errorMessage = e.message ?: "Failed opening destination file"
                )
            )
        }
    }

    suspend fun handleFileChunk(chunk: FileChunkPayload) {
        val tidHex = chunk.transferId.toHexString()
        val tr = inboundMap[tidHex] ?: return
        try {
            withContext(Dispatchers.IO) {
                tr.handle.stream.write(chunk.data)
            }
            tr.digest.update(chunk.data)
            tr.bytesWritten += chunk.data.size
            tr.chunksWritten = chunk.chunkIndex + 1

            val (speedMBps, shouldReport) = tr.speed.update(tr.bytesWritten)
            if (shouldReport || tr.bytesWritten >= tr.totalBytes) {
                publishState(
                    TransferState(
                        transferIdHex = tidHex,
                        fileName = tr.fileName,
                        direction = TransferDirection.RECEIVE,
                        bytesTransferred = tr.bytesWritten,
                        totalBytes = tr.totalBytes,
                        percent = computeIntermediatePercent(tr.bytesWritten, tr.totalBytes),
                        speedMBps = speedMBps,
                        chunkIndex = tr.chunksWritten,
                        totalChunks = tr.totalChunks,
                        status = TransferStatus.IN_PROGRESS
                    )
                )
            }
        } catch (e: Exception) {
            inboundMap.remove(tidHex)
            tr.handle.abort()
            publishState(
                TransferState(
                    transferIdHex = tidHex,
                    fileName = tr.fileName,
                    direction = TransferDirection.RECEIVE,
                    bytesTransferred = tr.bytesWritten,
                    totalBytes = tr.totalBytes,
                    percent = 0f,
                    speedMBps = 0.0,
                    chunkIndex = tr.chunksWritten,
                    totalChunks = tr.totalChunks,
                    status = TransferStatus.FAILED,
                    errorMessage = e.message ?: "Disk write failure"
                )
            )
        }
    }

    suspend fun handleFileDone(flags: Int, done: FileDonePayload) {
        val tidHex = done.transferId.toHexString()
        if ((flags and ProtocolConstants.FLAG_ACK) != 0) {
            pendingAcks[tidHex]?.complete(flags to done)
            return
        }

        val tr = inboundMap.remove(tidHex)
        if (tr == null) {
            val errAck = FileDonePayload(done.transferId, 0L, ByteArray(32), statusCode = 2)
            socketClient.sendFrame(
                ProtocolConstants.PACKET_TYPE_FILE_DONE,
                ProtocolConstants.FLAG_ACK or ProtocolConstants.FLAG_ERROR,
                errAck.encode()
            )
            return
        }

        val computed = tr.digest.digest()
        val computedHex = computed.toHexString()
        val valid = tr.bytesWritten == done.totalBytes &&
            MessageDigest.isEqual(computed, done.sha256)

        if (!valid) {
            withContext(Dispatchers.IO) { tr.handle.abort() }
            val errAck = FileDonePayload(done.transferId, tr.bytesWritten, computed, statusCode = 1)
            socketClient.sendFrame(
                ProtocolConstants.PACKET_TYPE_FILE_DONE,
                ProtocolConstants.FLAG_ACK or ProtocolConstants.FLAG_ERROR,
                errAck.encode()
            )
            publishState(
                TransferState(
                    transferIdHex = tidHex,
                    fileName = tr.fileName,
                    direction = TransferDirection.RECEIVE,
                    bytesTransferred = tr.bytesWritten,
                    totalBytes = tr.totalBytes,
                    percent = 0f,
                    speedMBps = 0.0,
                    chunkIndex = tr.chunksWritten,
                    totalChunks = tr.totalChunks,
                    status = TransferStatus.FAILED,
                    sha256Hex = computedHex,
                    errorMessage = "SHA-256 mismatch (bytes=${tr.bytesWritten}/${done.totalBytes})"
                )
            )
            return
        }

        val savedUri = withContext(Dispatchers.IO) { tr.handle.commit() }
        val okAck = FileDonePayload(done.transferId, tr.bytesWritten, computed, statusCode = 0)
        socketClient.sendFrame(
            ProtocolConstants.PACKET_TYPE_FILE_DONE,
            ProtocolConstants.FLAG_ACK,
            okAck.encode()
        )
        publishState(
            TransferState(
                transferIdHex = tidHex,
                fileName = tr.fileName,
                direction = TransferDirection.RECEIVE,
                bytesTransferred = tr.bytesWritten,
                totalBytes = tr.totalBytes,
                percent = 100f,
                speedMBps = tr.speed.finalSpeed(tr.bytesWritten),
                chunkIndex = tr.chunksWritten,
                totalChunks = tr.totalChunks,
                status = TransferStatus.VERIFIED,
                sha256Hex = computedHex,
                savedUri = savedUri
            )
        )
    }

    suspend fun sendContentUri(context: Context, uri: Uri) {
        val resolver = context.contentResolver
        var fileName = "shared_file.bin"
        var totalBytes = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val nIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sIdx = c.getColumnIndex(OpenableColumns.SIZE)
                if (nIdx >= 0) fileName = c.getString(nIdx) ?: fileName
                if (sIdx >= 0 && !c.isNull(sIdx)) totalBytes = c.getLong(sIdx)
            }
        }
        val mimeType = resolver.getType(uri) ?: "application/octet-stream"
        val stream = resolver.openInputStream(uri) ?: throw IllegalStateException("Unable to open URI: $uri")
        stream.use { inp ->
            if (totalBytes < 0L) {
                totalBytes = inp.available().toLong().coerceAtLeast(0L)
            }
            sendStream(inp, fileName, totalBytes, mimeType)
        }
    }

    suspend fun sendLocalFile(file: File, mimeType: String = "application/octet-stream") {
        FileInputStream(file).use { fis ->
            sendStream(fis, file.name, file.length(), mimeType)
        }
    }

    suspend fun sendStream(
        input: InputStream,
        fileName: String,
        totalBytes: Long,
        mimeType: String = "application/octet-stream"
    ) = withContext(Dispatchers.IO) {
        val tid = ByteArray(16).also { rng.nextBytes(it) }
        val tidHex = tid.toHexString()
        val safeName = FileInitPayload.sanitizeFileName(fileName)
        val chunkSize = ProtocolConstants.CHUNK_SIZE
        val totalChunks = ((totalBytes + chunkSize - 1) / chunkSize).toInt().coerceAtLeast(0)

        val ackDeferred = CompletableDeferred<Pair<Int, FileDonePayload>>()
        pendingAcks[tidHex] = ackDeferred

        try {
            val initPayload = FileInitPayload(
                transferId = tid,
                totalSize = totalBytes,
                chunkSize = chunkSize,
                totalChunks = totalChunks,
                fileName = safeName,
                mimeType = mimeType
            )
            socketClient.sendFrame(ProtocolConstants.PACKET_TYPE_FILE_INIT, 0, initPayload.encode())

            publishState(
                TransferState(
                    transferIdHex = tidHex,
                    fileName = safeName,
                    direction = TransferDirection.SEND,
                    bytesTransferred = 0L,
                    totalBytes = totalBytes,
                    percent = 0f,
                    speedMBps = 0.0,
                    chunkIndex = 0,
                    totalChunks = totalChunks,
                    status = TransferStatus.IN_PROGRESS
                )
            )

            val buffer = ByteArray(chunkSize)
            val digest = MessageDigest.getInstance("SHA-256")
            val tracker = SpeedTracker()
            var sent = 0L
            var chunkIdx = 0

            while (true) {
                var filled = 0
                while (filled < chunkSize) {
                    val read = input.read(buffer, filled, chunkSize - filled)
                    if (read <= 0) break
                    filled += read
                }
                if (filled <= 0) break

                digest.update(buffer, 0, filled)
                socketClient.sendChunkFrame(0, tid, chunkIdx, buffer, filled)
                sent += filled
                chunkIdx++

                val (speedMBps, shouldReport) = tracker.update(sent)
                if (shouldReport || sent >= totalBytes) {
                    publishState(
                        TransferState(
                            transferIdHex = tidHex,
                            fileName = safeName,
                            direction = TransferDirection.SEND,
                            bytesTransferred = sent,
                            totalBytes = totalBytes,
                            percent = computeIntermediatePercent(sent, totalBytes),
                            speedMBps = speedMBps,
                            chunkIndex = chunkIdx,
                            totalChunks = totalChunks,
                            status = TransferStatus.IN_PROGRESS
                        )
                    )
                }
            }

            val sha = digest.digest()
            val shaHex = sha.toHexString()
            val done = FileDonePayload(tid, sent, sha, statusCode = 0)
            socketClient.sendFrame(ProtocolConstants.PACKET_TYPE_FILE_DONE, 0, done.encode())

            val (ackFlags, ackPayload) = withTimeout(30_000) { ackDeferred.await() }
            if ((ackFlags and ProtocolConstants.FLAG_ERROR) != 0 ||
                ackPayload.statusCode != 0.toByte() ||
                !MessageDigest.isEqual(ackPayload.sha256, sha)
            ) {
                val errMsg = "Receiver integrity check failed (status=${ackPayload.statusCode})"
                publishState(
                    TransferState(
                        transferIdHex = tidHex,
                        fileName = safeName,
                        direction = TransferDirection.SEND,
                        bytesTransferred = sent,
                        totalBytes = totalBytes,
                        percent = 0f,
                        speedMBps = 0.0,
                        chunkIndex = chunkIdx,
                        totalChunks = totalChunks,
                        status = TransferStatus.FAILED,
                        sha256Hex = shaHex,
                        errorMessage = errMsg
                    )
                )
                throw IllegalStateException(errMsg)
            }

            publishState(
                TransferState(
                    transferIdHex = tidHex,
                    fileName = safeName,
                    direction = TransferDirection.SEND,
                    bytesTransferred = sent,
                    totalBytes = totalBytes,
                    percent = 100f,
                    speedMBps = tracker.finalSpeed(sent),
                    chunkIndex = chunkIdx,
                    totalChunks = totalChunks,
                    status = TransferStatus.VERIFIED,
                    sha256Hex = shaHex
                )
            )
        } finally {
            pendingAcks.remove(tidHex)
        }
    }

    private fun computeIntermediatePercent(done: Long, total: Long): Float {
        if (total <= 0L) return 99.9f
        val pct = ((done.toDouble() * 100.0) / total.toDouble()).toFloat()
        return pct.coerceIn(0f, 99.9f)
    }

    companion object {
        suspend fun loadLocalReceivedFiles(context: Context): List<LocalReceivedFile> = withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val out = mutableListOf<LocalReceivedFile>()
                val projection = arrayOf(
                    MediaStore.Downloads._ID,
                    MediaStore.Downloads.DISPLAY_NAME,
                    MediaStore.Downloads.SIZE,
                    MediaStore.Downloads.MIME_TYPE,
                    MediaStore.Downloads.DATE_MODIFIED
                )
                val sel = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ? AND ${MediaStore.Downloads.IS_PENDING} = 0"
                val args = arrayOf("%PhoneDrop%")
                runCatching {
                    context.contentResolver.query(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        projection,
                        sel,
                        args,
                        "${MediaStore.Downloads.DATE_MODIFIED} DESC"
                    )?.use { c ->
                        val idCol = c.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                        val nameCol = c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                        val sizeCol = c.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
                        val mimeCol = c.getColumnIndexOrThrow(MediaStore.Downloads.MIME_TYPE)
                        val modCol = c.getColumnIndexOrThrow(MediaStore.Downloads.DATE_MODIFIED)
                        while (c.moveToNext()) {
                            val id = c.getLong(idCol)
                            val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                            out.add(
                                LocalReceivedFile(
                                    uriString = uri.toString(),
                                    name = c.getString(nameCol) ?: "file",
                                    sizeBytes = c.getLong(sizeCol),
                                    mimeType = c.getString(mimeCol) ?: "application/octet-stream",
                                    modifiedMs = c.getLong(modCol) * 1000L
                                )
                            )
                        }
                    }
                }
                return@withContext out
            }

            val legacyDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "PhoneDrop"
            )
            if (!legacyDir.exists()) return@withContext emptyList()
            legacyDir.listFiles()
                ?.filter { it.isFile && !it.name.startsWith(".phonedrop-") && !it.name.endsWith(".part") }
                ?.sortedByDescending { it.lastModified() }
                ?.map { f ->
                    LocalReceivedFile(
                        uriString = f.toURI().toString(),
                        name = f.name,
                        sizeBytes = f.length(),
                        mimeType = "application/octet-stream",
                        modifiedMs = f.lastModified()
                    )
                } ?: emptyList()
        }

        suspend fun deleteLocalFile(context: Context, uriString: String): Boolean = withContext(Dispatchers.IO) {
            runCatching {
                val uri = Uri.parse(uriString)
                if (uri.scheme == "content") {
                    context.contentResolver.delete(uri, null, null) > 0
                } else {
                    val path = uri.path ?: return@runCatching false
                    File(path).delete()
                }
            }.getOrDefault(false)
        }
    }
}
