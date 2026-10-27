package com.weenas.castbay.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class MdnsZoneTest {
    private val ip = byteArrayOf(10, 1, 1, 152.toByte())
    private val zone = MdnsZone(
        host = "castbay-test",
        addresses = { listOf(ip) },
        services = listOf(
            MdnsService("_airplay._tcp", "CastBay Dev (TV)", 7000, linkedMapOf("deviceid" to "AA:BB", "features" to "0x1")),
            MdnsService("_raop._tcp", "AABB@CastBay Dev (TV)", 7000, linkedMapOf("ft" to "0x2"))
        )
    )

    /** A query for one name and type, as a browser sends it. */
    private fun query(name: String, type: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        for (label in name.split('.')) {
            out.write(label.length)
            out.write(label.toByteArray())
        }
        out.write(0)
        out.write(byteArrayOf((type shr 8).toByte(), type.toByte(), 0, 1))
        return out.toByteArray()
    }

    private class Rr(val name: String, val type: Int, val ttl: Long, val data: ByteArray, val full: ByteArray) {
        /** A PTR record's target, read from its data (names here are not compressed). */
        fun target(): String {
            val labels = mutableListOf<String>(); var i = 0
            while (data[i].toInt() != 0) { val n = data[i].toInt(); labels += String(data, i + 1, n); i += 1 + n }
            return labels.joinToString(".")
        }
    }

    /** Reads the records of a response (names written without compression, as [MdnsZone] writes them). */
    private fun records(packet: ByteArray): List<Rr> {
        fun u16(at: Int) = ((packet[at].toInt() and 0xFF) shl 8) or (packet[at + 1].toInt() and 0xFF)
        val total = u16(6) + u16(8) + u16(10)
        var pos = 12
        fun name(): String {
            val labels = mutableListOf<String>()
            while (packet[pos].toInt() != 0) {
                val n = packet[pos].toInt(); labels += String(packet, pos + 1, n); pos += 1 + n
            }
            pos++
            return labels.joinToString(".")
        }
        val out = mutableListOf<Rr>()
        repeat(total) {
            val owner = name()
            val type = u16(pos)
            val ttl = (u16(pos + 4).toLong() shl 16) or u16(pos + 6).toLong()
            val length = u16(pos + 8)
            val start = pos + 10
            out += Rr(owner, type, ttl, packet.copyOfRange(start, start + length), packet)
            pos = start + length
        }
        return out
    }

    @Test
    fun `a PTR query for the service type is answered with the instance and its records`() {
        val response = zone.answer(query("_airplay._tcp.local", MdnsZone.TYPE_PTR))
        assertNotNull(response)
        val rr = records(response!!)
        assertEquals(1, rr.count { it.type == MdnsZone.TYPE_PTR && it.name == "_airplay._tcp.local" })
        val srv = rr.single { it.type == MdnsZone.TYPE_SRV }
        assertEquals("CastBay Dev (TV)._airplay._tcp.local", srv.name)
        // priority, weight, then the port.
        assertEquals(7000, ((srv.data[4].toInt() and 0xFF) shl 8) or (srv.data[5].toInt() and 0xFF))
        assertTrue(String(rr.single { it.type == MdnsZone.TYPE_TXT }.data).contains("deviceid=AA:BB"))
        assertTrue(rr.single { it.type == MdnsZone.TYPE_A }.data.contentEquals(ip))
        // The response flags: a response, authoritative.
        assertEquals(0x84, response[2].toInt() and 0xFF)
    }

    @Test
    fun `the other service type is not mixed in`() {
        val rr = records(zone.answer(query("_raop._tcp.local", MdnsZone.TYPE_PTR))!!)
        assertTrue(rr.none { it.name.contains("_airplay") })
        assertEquals("AABB@CastBay Dev (TV)._raop._tcp.local", rr.single { it.type == MdnsZone.TYPE_SRV }.name)
    }

    @Test
    fun `the host name and the instance name are answered`() {
        val a = records(zone.answer(query("castbay-test.local", MdnsZone.TYPE_A))!!)
        assertTrue(a.single().data.contentEquals(ip))
        val txt = records(zone.answer(query("CastBay Dev (TV)._airplay._tcp.local", MdnsZone.TYPE_TXT))!!)
        assertTrue(txt.any { it.type == MdnsZone.TYPE_TXT })
    }

    @Test
    fun `service type enumeration lists every type`() {
        val rr = records(zone.answer(query("_services._dns-sd._udp.local", MdnsZone.TYPE_PTR))!!)
        assertEquals(setOf("_airplay._tcp.local", "_raop._tcp.local"), rr.map { it.target() }.toSet())
        assertTrue(rr.all { it.name == "_services._dns-sd._udp.local" })
    }

    @Test
    fun `names are compared without case and unknown names are not answered`() {
        assertNotNull(zone.answer(query("_AirPlay._TCP.local", MdnsZone.TYPE_PTR)))
        assertNull(zone.answer(query("_printer._tcp.local", MdnsZone.TYPE_PTR)))
        assertNull(zone.answer(query("_airplay._tcp.local", 28)))
    }

    @Test
    fun `responses and garbage are not answered`() {
        val response = zone.announcement()
        assertNull(zone.answer(response))
        assertNull(zone.answer(ByteArray(5)))
        assertNull(zone.answer(ByteArray(40) { 0xFF.toByte() }))
    }

    @Test
    fun `the announcement holds every record and the goodbye has no lifetime`() {
        val hello = records(zone.announcement())
        assertEquals(2, hello.count { it.type == MdnsZone.TYPE_SRV })
        assertEquals(2, hello.count { it.type == MdnsZone.TYPE_TXT })
        assertEquals(1, hello.count { it.type == MdnsZone.TYPE_A })
        assertTrue(hello.all { it.ttl > 0 })
        val bye = records(zone.announcement(0))
        assertEquals(hello.size, bye.size)
        assertTrue(bye.all { it.ttl == 0L })
    }

    @Test
    fun `a query with a compressed name is understood`() {
        // "_airplay._tcp.local" once, then a second question whose name points back at it.
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0, 0, 0, 0, 0, 2, 0, 0, 0, 0, 0, 0))
        val nameAt = out.size()
        for (label in "_airplay._tcp.local".split('.')) { out.write(label.length); out.write(label.toByteArray()) }
        out.write(0)
        out.write(byteArrayOf(0, 255.toByte(), 0, 1))
        out.write(byteArrayOf((0xC0 or (nameAt shr 8)).toByte(), nameAt.toByte(), 0, 12, 0, 1))
        assertNotNull(zone.answer(out.toByteArray()))
    }

    // ---- hostile input: whatever arrives on the multicast port goes through answer()

    private fun valid(name: String = "_airplay._tcp.local", type: Int = MdnsZone.TYPE_PTR) = query(name, type)

    @Test
    fun `random bytes never make the zone throw`() {
        val random = java.util.Random(20261027)
        repeat(20_000) {
            val packet = ByteArray(random.nextInt(300)).also { random.nextBytes(it) }
            zone.answer(packet)
        }
    }

    @Test
    fun `every truncation and every single-byte corruption of a good query is survived`() {
        val good = valid()
        for (cut in good.indices) zone.answer(good.copyOf(cut))
        for (i in good.indices) for (b in intArrayOf(0x00, 0x3F, 0x40, 0x7F, 0x80, 0xBF, 0xC0, 0xFF)) {
            val bad = good.copyOf()
            bad[i] = b.toByte()
            zone.answer(bad)
        }
    }

    @Test
    fun `a name that points back at itself is refused, not followed for ever`() {
        // The pointer at offset 12 points at offset 12.
        val loop = byteArrayOf(0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0xC0.toByte(), 12, 0, 12, 0, 1)
        assertNull(zone.answer(loop))
        // Two pointers pointing at each other.
        val pair = byteArrayOf(0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0xC0.toByte(), 14, 0xC0.toByte(), 12, 0, 12, 0, 1)
        assertNull(zone.answer(pair))
    }

    @Test
    fun `a question count larger than the packet and absurd counts are refused`() {
        val many = byteArrayOf(0, 0, 0, 0, 0xFF.toByte(), 0xFF.toByte(), 0, 0, 0, 0, 0, 0) + ByteArray(40)
        assertNull(zone.answer(many))
        val claimed = byteArrayOf(0, 0, 0, 0, 0, 5, 0, 0, 0, 0, 0, 0) + valid().copyOfRange(12, valid().size)
        assertNull(zone.answer(claimed))
    }

    @Test
    fun `a label longer than the packet or than 63 bytes is refused`() {
        val longLabel = byteArrayOf(0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 62) + ByteArray(10)
        assertNull(zone.answer(longLabel))
        val tooLong = byteArrayOf(0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 70) + ByteArray(80)
        assertNull(zone.answer(tooLong))
    }

    @Test
    fun `the answer to the biggest query is a bounded packet`() {
        // 32 questions for the same name: the answer still holds each record once.
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0, 0, 0, 0, 0, 32, 0, 0, 0, 0, 0, 0))
        repeat(32) {
            for (label in "_airplay._tcp.local".split('.')) { out.write(label.length); out.write(label.toByteArray()) }
            out.write(0)
            out.write(byteArrayOf(0, 12, 0, 1))
        }
        val response = zone.answer(out.toByteArray())!!
        assertTrue(response.size < 1500)
        assertEquals(1, records(response).count { it.type == MdnsZone.TYPE_PTR })
    }
}
