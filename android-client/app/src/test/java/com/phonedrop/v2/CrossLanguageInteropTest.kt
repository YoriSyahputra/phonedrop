package com.phonedrop.v2

import com.phonedrop.v2.data.network.BinarySocketClient
import com.phonedrop.v2.data.protocol.ClipPayload
import com.phonedrop.v2.data.protocol.FileDonePayload
import com.phonedrop.v2.data.protocol.PacketFramer
import com.phonedrop.v2.data.protocol.ProtocolConstants
import com.phonedrop.v2.data.protocol.toHexString
import com.phonedrop.v2.data.repository.DirectoryTransferSink
import com.phonedrop.v2.data.repository.FileTransferManager
import com.phonedrop.v2.domain.model.TransferState
import com.phonedrop.v2.domain.model.TransferStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList

class CrossLanguageInteropTest {

    @Test
    fun verifyBinaryHeaderBytesMatchGoSpec() {
        val raw = PacketFramer.encodeHeader(
            type = ProtocolConstants.PACKET_TYPE_FILE_DONE,
            flags = ProtocolConstants.FLAG_ACK,
            seqId = 0x01020304,
            payloadLen = 57
        )
        val expected = byteArrayOf(
            'P'.code.toByte(), 'D'.code.toByte(), 'P'.code.toByte(), '2'.code.toByte(),
            0x02, 0x04, 0x01, 0x00,
            0x01, 0x02, 0x03, 0x04,
            0x00, 0x00, 0x00, 0x39
        )
        assertArrayEquals(expected, raw)
    }

    @Test
    fun liveKotlinToGoDaemonChunkedTransferAndClipboard() = runBlocking {
        val portStr = System.getenv("PHONEDROP_LIVE_GO_PORT")
        val destStr = System.getenv("PHONEDROP_LIVE_GO_DEST")
        assumeTrue("PHONEDROP_LIVE_GO_PORT not set; skipping live Go interop", !portStr.isNullOrBlank() && !destStr.isNullOrBlank())

        val port = portStr!!.toInt()
        val destDir = File(destStr!!)
        val tmpSinkDir = Files.createTempDirectory("pd2-kt-tmp").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = BinarySocketClient(scope)

        try {
            assertTrue("Failed connecting Kotlin client to Go daemon :$port", client.connectDirect("127.0.0.1", port, isUsb = true))

            val progressEvents = CopyOnWriteArrayList<TransferState>()
            val mgr = FileTransferManager(
                socketClient = client,
                outputSink = DirectoryTransferSink(tmpSinkDir),
                onProgressCallback = { progressEvents.add(it) }
            )

            val ackCollector = scope.launch {
                client.incomingFrames.collect { frame ->
                    if (frame.header.type == ProtocolConstants.PACKET_TYPE_FILE_DONE) {
                        mgr.handleFileDone(frame.header.flags, FileDonePayload.decode(frame.payload))
                    }
                }
            }

            // 1. Send 0x01 Clip packet to Go daemon
            val clipText = "Cross-language Go<->Kotlin clip interop ✓"
            val clipPayload = ClipPayload(
                timestampMs = System.currentTimeMillis(),
                originHash = ClipPayload.computeOriginHash(clipText),
                mime = "text/plain;charset=utf-8",
                text = clipText
            ).encode()
            client.sendFrame(ProtocolConstants.PACKET_TYPE_CLIP, 0, clipPayload)

            // 2. Send 200 KiB payload (4 chunks: 3 x 64KiB + 8KiB) to Go daemon & verify Go SHA-256 ACK
            val payload = ByteArray(200 * 1024)
            Random(2026).nextBytes(payload)
            val expectedSha = MessageDigest.getInstance("SHA-256").digest(payload).toHexString()

            mgr.sendStream(
                input = ByteArrayInputStream(payload),
                fileName = "cross_lang_200k.bin",
                totalBytes = payload.size.toLong()
            )
            ackCollector.cancel()

            assertTrue(progressEvents.isNotEmpty())
            val terminal = progressEvents.last()
            assertEquals(TransferStatus.VERIFIED, terminal.status)
            assertEquals(100f, terminal.percent)
            assertEquals(expectedSha, terminal.sha256Hex)
            assertEquals(4, terminal.chunkIndex)

            val landed = File(destDir, "cross_lang_200k.bin")
            assertTrue("Expected Go daemon to persist file at ${landed.absolutePath}", landed.exists())
            assertArrayEquals(payload, landed.readBytes())
        } finally {
            client.disconnect()
            scope.cancel()
            tmpSinkDir.deleteRecursively()
        }
    }
}
