package com.phonedrop.v2

import com.phonedrop.v2.data.protocol.ClipPayload
import com.phonedrop.v2.data.protocol.ControlMessage
import com.phonedrop.v2.data.protocol.ControlOps
import com.phonedrop.v2.data.protocol.FileChunkPayload
import com.phonedrop.v2.data.protocol.FileDonePayload
import com.phonedrop.v2.data.protocol.FileInitPayload
import com.phonedrop.v2.data.protocol.InputConstants
import com.phonedrop.v2.data.protocol.InputEventPayload
import com.phonedrop.v2.data.protocol.MirrorConfig
import com.phonedrop.v2.data.protocol.PacketFramer
import com.phonedrop.v2.data.protocol.ProtocolConstants
import com.phonedrop.v2.data.protocol.ProtocolException
import com.phonedrop.v2.data.protocol.VideoConstants
import com.phonedrop.v2.data.protocol.VideoFrameHeader
import com.phonedrop.v2.data.protocol.VideoFramePayload
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

class PacketFramerTest {

    @Test
    fun roundTripAllPacketTypesAndVerifyWireFormat() {
        val framer = PacketFramer()
        val wire = ByteArrayOutputStream()

        // 0x01 Clip
        val clipText = "PhoneDrop v2 Kotlin <-> Go clip test!"
        val clipIn = ClipPayload(
            timestampMs = 1732490000123L,
            originHash = ClipPayload.computeOriginHash(clipText),
            mime = "text/plain;charset=utf-8",
            text = clipText
        )
        framer.writeFrame(wire, ProtocolConstants.PACKET_TYPE_CLIP, ProtocolConstants.FLAG_DIR_LINUX_TO_PHONE, clipIn.encode())

        // 0x02 FileInit (verify sanitization too)
        val tid = ByteArray(16) { (it + 1).toByte() }
        val initIn = FileInitPayload(
            transferId = tid,
            totalSize = 131072L,
            chunkSize = ProtocolConstants.CHUNK_SIZE,
            totalChunks = 2,
            fileName = "../../tmp/photo.jpg",
            mimeType = "image/jpeg"
        )
        framer.writeFrame(wire, ProtocolConstants.PACKET_TYPE_FILE_INIT, 0, initIn.encode())

        // 0x03 FileChunk (64 KiB)
        val chunkBytes = ByteArray(ProtocolConstants.CHUNK_SIZE) { 0x5A }
        framer.writeChunkFrame(wire, 0, tid, 0, chunkBytes, chunkBytes.size)

        // 0x04 FileDone
        val sha = MessageDigest.getInstance("SHA-256").digest(chunkBytes)
        val doneIn = FileDonePayload(tid, chunkBytes.size.toLong(), sha, statusCode = 0)
        framer.writeFrame(wire, ProtocolConstants.PACKET_TYPE_FILE_DONE, ProtocolConstants.FLAG_ACK, doneIn.encode())

        // 0x05 VideoFrame
        val nal = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0xC0.toByte(), 0x1E)
        val vidHdr = VideoFrameHeader(
            streamId = VideoConstants.STREAM_LINUX_TO_PHONE,
            codec = VideoConstants.CODEC_H264,
            width = 1920,
            height = 1080,
            ptsUs = 5544332211L
        )
        framer.writeVideoFrame(wire, ProtocolConstants.FLAG_CODEC_CONFIG, vidHdr, nal)

        // 0x06 InputEvent
        val inputIn = InputEventPayload(
            eventType = InputConstants.INPUT_TAP_ABS,
            actionOrButton = 1,
            x = 32768,
            y = 16384,
            arg1 = 2,
            arg2 = 0,
            text = "Return"
        )
        framer.writeFrame(wire, ProtocolConstants.PACKET_TYPE_INPUT_EVENT, 0, inputIn.encode())

        // 0x07 Control
        val ctrlBytes = """{"op":"list_files"}""".toByteArray(Charsets.UTF_8)
        framer.writeFrame(wire, ProtocolConstants.PACKET_TYPE_CONTROL, 0, ctrlBytes)

        val rawBytes = wire.toByteArray()
        assertEquals('P'.code.toByte(), rawBytes[0])
        assertEquals('D'.code.toByte(), rawBytes[1])
        assertEquals('P'.code.toByte(), rawBytes[2])
        assertEquals('2'.code.toByte(), rawBytes[3])
        assertEquals(ProtocolConstants.PROTOCOL_VERSION, rawBytes[4])

        val inp = ByteArrayInputStream(rawBytes)

        val f1 = PacketFramer.readFrame(inp)!!
        assertEquals(ProtocolConstants.PACKET_TYPE_CLIP, f1.header.type)
        assertEquals(1L, f1.header.seqId)
        assertEquals(clipIn, ClipPayload.decode(f1.payload))

        val f2 = PacketFramer.readFrame(inp)!!
        val decodedInit = FileInitPayload.decode(f2.payload)
        assertEquals("photo.jpg", decodedInit.fileName)
        assertArrayEquals(tid, decodedInit.transferId)

        val f3 = PacketFramer.readFrame(inp)!!
        val decodedChunk = FileChunkPayload.decode(f3.payload)
        assertEquals(0, decodedChunk.chunkIndex)
        assertArrayEquals(chunkBytes, decodedChunk.data)

        val f4 = PacketFramer.readFrame(inp)!!
        assertTrue((f4.header.flags and ProtocolConstants.FLAG_ACK) != 0)
        assertEquals(doneIn, FileDonePayload.decode(f4.payload))

        val f5 = PacketFramer.readFrame(inp)!!
        val decodedVid = VideoFramePayload.decode(f5.payload)
        assertEquals(vidHdr, decodedVid.header)
        assertArrayEquals(nal, decodedVid.nalData)

        val f6 = PacketFramer.readFrame(inp)!!
        val decodedInp = InputEventPayload.decode(f6.payload)
        assertEquals(inputIn, decodedInp)

        val f7 = PacketFramer.readFrame(inp)!!
        assertEquals(ProtocolConstants.PACKET_TYPE_CONTROL, f7.header.type)
        assertArrayEquals(ctrlBytes, f7.payload)

        assertNull(PacketFramer.readFrame(inp))
    }

    @Test(expected = ProtocolException::class)
    fun rejectsInvalidMagicBytes() {
        val bad = ByteArray(16) { 0x41 }
        PacketFramer.readFrame(ByteArrayInputStream(bad))
    }

    @Test(expected = ProtocolException::class)
    fun rejectsOversizedPayloadHeader() {
        val hdr = PacketFramer.encodeHeader(
            ProtocolConstants.PACKET_TYPE_CLIP,
            0,
            1,
            ProtocolConstants.MAX_PAYLOAD_SIZE + 1
        )
        val res = PacketFramer.readFrame(ByteArrayInputStream(hdr))
        assertNotNull(res)
    }

    @Test
    fun mirrorConfigControlMessageRoundTrip() {
        val cfg = MirrorConfig(
            fps = 144,
            bitrateMbps = 16,
            maxSize = 1280,
            audio = false,
            codec = "h265",
            turnScreenOff = true,
            stayAwake = true,
            alwaysOnTop = true,
            fullscreen = false,
            showTouches = true
        )
        val encoded = ControlMessage(op = ControlOps.UPDATE_MIRROR_CONFIG, mirror = cfg).encode()
        val decoded = ControlMessage.decode(encoded)
        assertEquals(ControlOps.UPDATE_MIRROR_CONFIG, decoded.op)
        assertEquals(cfg, decoded.mirror)
    }
}
