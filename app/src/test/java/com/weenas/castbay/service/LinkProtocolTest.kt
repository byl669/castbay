package com.weenas.castbay.service

import com.weenas.castbay.service.desktop.LinkMessage
import com.weenas.castbay.service.desktop.LinkProtocol
import com.weenas.castbay.service.desktop.LinkProtocolException
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream

class LinkProtocolTest {
    private fun reader(bytes: ByteArray) = DataInputStream(ByteArrayInputStream(bytes))

    private fun assertProtocolError(bytes: ByteArray) {
        try {
            LinkProtocol.read(reader(bytes))
            fail("must be a protocol error")
        } catch (e: LinkProtocolException) {
            // expected
        }
    }

    private fun controlBytes(json: String): ByteArray {
        val out = ByteArrayOutputStream()
        LinkProtocol.writeControl(out, JSONObject(json))
        return out.toByteArray()
    }

    @Test
    fun `a control message round trips`() {
        val message = LinkProtocol.read(reader(controlBytes("""{"t":"hello","v":1,"name":"PC"}""")))
        assertTrue(message is LinkMessage.Control)
        message as LinkMessage.Control
        assertEquals("hello", message.type)
        assertEquals("PC", message.json.getString("name"))
    }

    @Test
    fun `an audio message round trips with its time`() {
        val frame = byteArrayOf(0x21, 0x10, 0x04, 0x60, 0x8c.toByte())
        val message = LinkProtocol.read(reader(LinkProtocol.frameAudio(987_654_321L, frame)))
        message as LinkMessage.Audio
        assertEquals(987_654_321L, message.presentationTimeUs)
        assertArrayEquals(frame, message.data)
    }

    @Test
    fun `an audio message too short or too long is a protocol error`() {
        // Shorter than its time stamp.
        assertProtocolError(byteArrayOf(3, 0, 0, 0, 4, 1, 2, 3, 4))
        // Larger than any AAC frame has a reason to be: refused before reading the body.
        assertProtocolError(byteArrayOf(3, 0, 2, 0, 0))
    }

    @Test
    fun `an AAC config is a few bytes of hex`() {
        assertArrayEquals(byteArrayOf(0x11, 0x90.toByte()), com.weenas.castbay.service.desktop.LinkAudioPlayer.parseConfig("1190"))
        assertNull(com.weenas.castbay.service.desktop.LinkAudioPlayer.parseConfig("119"))
        assertNull(com.weenas.castbay.service.desktop.LinkAudioPlayer.parseConfig("zz90"))
        assertNull(com.weenas.castbay.service.desktop.LinkAudioPlayer.parseConfig(""))
        assertNull(com.weenas.castbay.service.desktop.LinkAudioPlayer.parseConfig("00".repeat(20)))
    }

    @Test
    fun `a video message round trips with its time and key flag`() {
        val unit = byteArrayOf(0, 0, 0, 1, 0x65, 1, 2, 3)
        val message = LinkProtocol.read(reader(LinkProtocol.frameVideo(1_234_567_890_123L, true, unit)))
        message as LinkMessage.Video
        assertEquals(1_234_567_890_123L, message.presentationTimeUs)
        assertTrue(message.keyFrame)
        assertArrayEquals(unit, message.data)

        val delta = LinkProtocol.read(reader(LinkProtocol.frameVideo(5, false, unit))) as LinkMessage.Video
        assertFalse(delta.keyFrame)
    }

    @Test
    fun `end of stream before a message is null, in the middle of one is an error`() {
        assertNull(LinkProtocol.read(reader(ByteArray(0))))
        val cut = controlBytes("""{"t":"ping"}""").copyOf(8)
        try {
            LinkProtocol.read(reader(cut))
            fail("a cut message must not parse")
        } catch (e: java.io.EOFException) {
            // expected
        }
    }

    @Test
    fun `unknown message types are skipped over with their payload`() {
        val bytes = byteArrayOf(0x7F, 0, 0, 0, 2, 9, 9) + controlBytes("""{"t":"ping"}""")
        val input = reader(bytes)
        assertEquals(LinkMessage.Unknown(0x7F), LinkProtocol.read(input))
        assertEquals("ping", (LinkProtocol.read(input) as LinkMessage.Control).type)
    }

    @Test
    fun `oversized and negative lengths are refused before anything is allocated`() {
        val tooBig = byteArrayOf(LinkProtocol.TYPE_VIDEO.toByte(), 0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        val negative = byteArrayOf(LinkProtocol.TYPE_VIDEO.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        val bigControl = byteArrayOf(LinkProtocol.TYPE_CONTROL.toByte(), 0, 0x10, 0, 0)
        for (bytes in listOf(tooBig, negative, bigControl)) {
            try {
                LinkProtocol.read(reader(bytes))
                fail("must be refused")
            } catch (e: LinkProtocolException) {
                // expected
            }
        }
    }

    @Test
    fun `malformed control and short video messages are protocol errors`() {
        try {
            LinkProtocol.read(reader(byteArrayOf(LinkProtocol.TYPE_CONTROL.toByte(), 0, 0, 0, 3, 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte())))
            fail("not JSON")
        } catch (e: LinkProtocolException) {
            // expected
        }
        try {
            LinkProtocol.read(reader(byteArrayOf(LinkProtocol.TYPE_VIDEO.toByte(), 0, 0, 0, 2, 1, 2)))
            fail("short video")
        } catch (e: LinkProtocolException) {
            // expected
        }
    }

    @Test
    fun `deeply nested JSON is refused before it can overflow the parser's stack`() {
        val deep = "[".repeat(50_000).toByteArray()
        val frame = byteArrayOf(LinkProtocol.TYPE_CONTROL.toByte(), 0, 0, (deep.size shr 8).toByte(), deep.size.toByte()) + deep
        try {
            LinkProtocol.read(reader(frame))
            fail("must be refused")
        } catch (e: LinkProtocolException) {
            // expected
        }
    }

    @Test
    fun `nesting depth ignores brackets inside strings`() {
        assertEquals(1, LinkProtocol.nestingDepth("""{"t":"[[[[[[[[[[[[]]]]","x":"a\"b[["}""".toByteArray()))
        assertEquals(3, LinkProtocol.nestingDepth("""{"a":{"b":[1]}}""".toByteArray()))
        assertEquals(0, LinkProtocol.nestingDepth(ByteArray(0)))
    }

    @Test
    fun `the verification code is six digits, the same on both sides and different for another peer`() {
        val receiver = ByteArray(32) { it.toByte() }
        val sender = ByteArray(32) { (it * 3).toByte() }
        val code = LinkProtocol.verificationCode(receiver, sender)
        assertEquals(6, code.length)
        assertTrue(code.all { it.isDigit() })
        assertEquals(code, LinkProtocol.verificationCode(receiver, sender))
        // A man in the middle has another certificate towards each side.
        assertNotEquals(code, LinkProtocol.verificationCode(receiver, ByteArray(32) { 7 }))
        // The order matters: receiver first.
        assertNotEquals(code, LinkProtocol.verificationCode(sender, receiver))
    }

    @Test
    fun `verification code fixture`() {
        // The desktop sender's tests use the same value (desktop/docs/protocol.md).
        val receiver = ByteArray(32) { 0x11 }
        val sender = ByteArray(32) { 0x22 }
        assertEquals("130806", LinkProtocol.verificationCode(receiver, sender))
    }
}
