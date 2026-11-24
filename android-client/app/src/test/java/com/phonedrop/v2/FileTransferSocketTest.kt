package com.phonedrop.v2

import com.phonedrop.v2.data.network.BinarySocketClient
import com.phonedrop.v2.data.protocol.FileChunkPayload
import com.phonedrop.v2.data.protocol.FileDonePayload
import com.phonedrop.v2.data.protocol.FileInitPayload
import com.phonedrop.v2.data.protocol.PacketFramer
import com.phonedrop.v2.data.protocol.ProtocolConstants
import com.phonedrop.v2.data.protocol.toHexString
import com.phonedrop.v2.data.repository.DirectoryTransferSink
import com.phonedrop.v2.data.repository.FileTransferManager
import com.phonedrop.v2.domain.model.ConnectionState
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
import org.junit.Test
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class FileTransferSocketTest {

    @Test
    fun chunkedLoopbackTransferTracksProgressAndVerifiesSha256() = runBlocking {
        val destDir = Files.createTempDirectory("pd2-recv").toFile()
        val srcDir = Files.createTempDirectory("pd2-src").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val server = ServerSocket(0)
        val port = server.localPort
        val recvDoneLatch = CountDownLatch(1)
        val recvProgress = CopyOnWriteArrayList<TransferState>()

        val client = BinarySocketClient(scope)
        val receiverEngine = FileTransferManager(
            socketClient = BinarySocketClient(scope), // unused when we reply via server socket directly
            outputSink = DirectoryTransferSink(destDir),
            onProgressCallback = { recvProgress.add(it) }
        )

        // Start a real PDP2 TCP receiver loop acting like desktop-linux
        val serverJob = scope.launch {
            val sock = server.accept()
            sock.tcpNoDelay = true
            val inp = DataInputStream(sock.getInputStream())
            val out = BufferedOutputStream(sock.getOutputStream())
            val framer = PacketFramer()

            val srvScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            var currentDigest: MessageDigest? = null
            var outHandle: com.phonedrop.v2.data.repository.OutputHandle? = null
            var bytesWritten = 0L

            try {
                while (true) {
                    val frame = PacketFramer.readFrame(inp) ?: break
                    when (frame.header.type) {
                        ProtocolConstants.PACKET_TYPE_FILE_INIT -> {
                            val init = FileInitPayload.decode(frame.payload)
                            outHandle = DirectoryTransferSink(destDir).openOutput(init.fileName, init.mimeType)
                            currentDigest = MessageDigest.getInstance("SHA-256")
                            bytesWritten = 0L
                        }
                        ProtocolConstants.PACKET_TYPE_FILE_CHUNK -> {
                            val chunk = FileChunkPayload.decode(frame.payload)
                            outHandle?.stream?.write(chunk.data)
                            currentDigest?.update(chunk.data)
                            bytesWritten += chunk.data.size
                        }
                        ProtocolConstants.PACKET_TYPE_FILE_DONE -> {
                            val done = FileDonePayload.decode(frame.payload)
                            val computed = currentDigest!!.digest()
                            val ok = bytesWritten == done.totalBytes && MessageDigest.isEqual(computed, done.sha256)
                            if (ok) outHandle?.commit() else outHandle?.abort()
                            val ack = FileDonePayload(done.transferId, bytesWritten, computed, if (ok) 0 else 1)
                            val flags = if (ok) ProtocolConstants.FLAG_ACK else (ProtocolConstants.FLAG_ACK or ProtocolConstants.FLAG_ERROR)
                            framer.writeFrame(out, ProtocolConstants.PACKET_TYPE_FILE_DONE, flags, ack.encode())
                            recvDoneLatch.countDown()
                        }
                    }
                }
            } finally {
                srvScope.cancel()
                sock.close()
            }
        }

        try {
            assertTrue(client.connectDirect("127.0.0.1", port, isUsb = true))
            assertTrue(client.connectionState.value is ConnectionState.UsbConnected)

            val senderProgress = CopyOnWriteArrayList<TransferState>()
            val senderMgr = FileTransferManager(
                socketClient = client,
                outputSink = DirectoryTransferSink(srcDir),
                onProgressCallback = { senderProgress.add(it) }
            )

            // Route incoming FileDone ACK from socket to senderMgr
            val dispatchJob = scope.launch {
                client.incomingFrames.collect { frame ->
                    if (frame.header.type == ProtocolConstants.PACKET_TYPE_FILE_DONE) {
                        senderMgr.handleFileDone(frame.header.flags, FileDonePayload.decode(frame.payload))
                    }
                }
            }

            // 1.5 MiB + 321 bytes => 25 chunks of 64KB + remainder
            val payload = ByteArray(1536 * 1024 + 321)
            Random(42).nextBytes(payload)
            val expectedSha = MessageDigest.getInstance("SHA-256").digest(payload).toHexString()

            senderMgr.sendStream(
                input = ByteArrayInputStream(payload),
                fileName = "kotlin_test_archive.bin",
                totalBytes = payload.size.toLong()
            )

            assertTrue(recvDoneLatch.await(10, TimeUnit.SECONDS))
            dispatchJob.cancel()

            assertTrue("Expected multiple progress callbacks", senderProgress.size >= 2)
            val finalState = senderProgress.last()
            assertEquals(TransferStatus.VERIFIED, finalState.status)
            assertEquals(100f, finalState.percent)
            assertEquals(expectedSha, finalState.sha256Hex)
            assertEquals(25, finalState.chunkIndex)

            val receivedFile = File(destDir, "kotlin_test_archive.bin")
            assertTrue(receivedFile.exists())
            assertArrayEquals(payload, receivedFile.readBytes())
        } finally {
            client.disconnect()
            serverJob.cancel()
            server.close()
            scope.cancel()
            destDir.deleteRecursively()
            srcDir.deleteRecursively()
            receiverEngine.hashCode()
        }
    }
}
