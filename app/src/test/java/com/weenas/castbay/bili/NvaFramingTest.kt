package com.weenas.castbay.bili

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * The framing is hand-ported from the reference implementations, so the byte offsets are the
 * part most likely to be wrong: a mistake there looks exactly like "the phone ignores us".
 * These encode frames the way the phone does and read them back.
 */
class NvaFramingTest {

    /** A command as the phone sends it: type, kind, counter, "Command", method, [payload]. */
    private fun fromPhone(counter: Int, method: String, payload: String? = null): ByteArray {
        val name = method.toByteArray(Charsets.UTF_8)
        val body = payload?.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            writeByte(Nva.SEND)
            writeByte(if (body == null) 2 else 3)
            writeInt(counter)
            writeByte(1)
            writeByte(7)
            write("Command".toByteArray(Charsets.UTF_8))
            writeByte(name.size)
            write(name)
            if (body != null) {
                writeInt(body.size)
                write(body)
            }
        }
        return out.toByteArray()
    }

    private fun parse(bytes: ByteArray): Pair<List<Nva.Command>, Int> {
        val commands = mutableListOf<Nva.Command>()
        val consumed = Nva.parse(bytes, bytes.size, commands)
        return commands to consumed
    }

    @Test
    fun `reads a command carrying a payload`() {
        val payload = """{"aid":80433022,"cid":137649199,"qn":80,"userDesireQn":80,"accessKey":"abcdef0123456789"}"""
        val (commands, consumed) = parse(fromPhone(7, "Play", payload))

        assertEquals(1, commands.size)
        assertEquals(7, commands[0].counter)
        assertEquals("Play", commands[0].method)
        assertEquals(payload, commands[0].payload)
        assertEquals(fromPhone(7, "Play", payload).size, consumed)
    }

    @Test
    fun `reads a command without a payload`() {
        val frame = fromPhone(3, "Pause")
        val (commands, consumed) = parse(frame)

        assertEquals(1, commands.size)
        assertEquals("Pause", commands[0].method)
        assertEquals(null, commands[0].payload)
        assertEquals(6 + 10 + "Pause".length, consumed)
        assertEquals(frame.size, consumed)
    }

    @Test
    fun `reads several commands and skips a ping between them`() {
        val ping = Nva.ping(9)
        val bytes = fromPhone(1, "Play", """{"cid":1}""") + ping + fromPhone(2, "Seek", """{"seekTs":30}""")
        val (commands, consumed) = parse(bytes)

        assertEquals(2, commands.size)
        assertEquals("Play", commands[0].method)
        assertEquals("Seek", commands[1].method)
        assertEquals(2, commands[1].counter)
        assertEquals(bytes.size, consumed)
    }

    @Test
    fun `waits for the rest of a frame split across reads`() {
        val frame = fromPhone(1, "Play", """{"cid":137649199}""")
        val firstHalf = frame.copyOfRange(0, 12)
        val (partial, partialConsumed) = parse(firstHalf)
        assertTrue(partial.isEmpty())
        assertEquals(0, partialConsumed)

        val rest = firstHalf + frame.copyOfRange(12, frame.size)
        val (commands, consumed) = parse(rest)
        assertEquals(1, commands.size)
        assertEquals("""{"cid":137649199}""", commands[0].payload)
        assertEquals(frame.size, consumed)
    }

    @Test
    fun `acknowledgement has the reply layout`() {
        val ack = Nva.ack(258)
        assertEquals(6, ack.size)
        assertEquals(Nva.RET.toByte(), ack[0])
        assertEquals(0.toByte(), ack[1])
        // 258 = 0x00000102, big endian.
        assertArrayEquals(byteArrayOf(0, 0, 1, 2), ack.copyOfRange(2, 6))
    }

    @Test
    fun `reply to a command carries length-prefixed json`() {
        val json = """{"volume":42}"""
        val reply = Nva.reply(5, json)
        assertEquals(Nva.RET.toByte(), reply[0])
        assertEquals(1.toByte(), reply[1])
        assertEquals(json.toByteArray().size, reply.size - 10)
        assertEquals(json, String(reply.copyOfRange(10, reply.size), Charsets.UTF_8))
    }

    @Test
    fun `broadcast matches the layout the phone expects`() {
        val json = """{"playState":4}"""
        val bytes = Nva.broadcast(6, "OnPlayState", json)
        assertEquals(Nva.SEND.toByte(), bytes[0])
        assertEquals(3.toByte(), bytes[1])
        assertEquals(1.toByte(), bytes[6])
        assertEquals(7.toByte(), bytes[7])
        assertEquals("Command", String(bytes.copyOfRange(8, 15), Charsets.US_ASCII))
        assertEquals("OnPlayState".length.toByte(), bytes[15])
        assertEquals("OnPlayState", String(bytes.copyOfRange(16, 16 + 11), Charsets.US_ASCII))
        assertEquals(json, String(bytes.copyOfRange(16 + 11 + 4, bytes.size), Charsets.UTF_8))
    }

    @Test
    fun `announces the service the phone searches for`() {
        val targets = Nva.targets("1e2d3c4b-0000-4000-8000-000000000001").map { it.first }
        assertTrue(targets.contains(Nva.SERVICE_TYPE))
        assertTrue(targets.contains("urn:schemas-upnp-org:device:MediaRenderer:1"))
    }
}
