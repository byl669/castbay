package com.weenas.castbay.service

import java.io.ByteArrayOutputStream

/** One DNS-SD service to announce: `_airplay._tcp` named "CastBay" on a port, with its TXT attributes. */
data class MdnsService(val type: String, val instance: String, val port: Int, val txt: Map<String, String>) {
    /** The service's full name: "CastBay._airplay._tcp.local". */
    val fullName: String get() = "$instance.$type.local"
}

/**
 * The multicast DNS records of this receiver, and the packets that announce them or answer a
 * question. Used when Android's own NsdManager does not register the services (some TV boxes
 * answer "internal error" or never answer), see [MdnsResponder]. Pure: no sockets here.
 *
 * Only what DNS-SD browsing needs is answered: the service type's PTR, the instance's SRV and TXT,
 * and the host's A records.
 */
class MdnsZone(
    /** The host's name without ".local". */
    private val host: String,
    /** IPv4 addresses (4 bytes each) of this device, read when a packet is made. */
    private val addresses: () -> List<ByteArray>,
    private val services: List<MdnsService>
) {
    private val hostName get() = "$host.local"

    /** An unsolicited response with every record: sent when starting (TTL > 0) and when stopping (TTL 0). */
    fun announcement(ttlSeconds: Int = TTL_RECORD): ByteArray {
        val records = mutableListOf<Record>()
        for (service in services) {
            records += ptr(service, ttlSeconds)
            records += srv(service, ttlSeconds)
            records += txt(service, ttlSeconds)
            records += META.let { Record(it, TYPE_PTR, CLASS_IN, ttlSeconds, name(service.type + ".local")) }
        }
        records += aRecords(ttlSeconds)
        return packet(records, emptyList())
    }

    /** The answer to a query packet, or null when it asks nothing this receiver knows. */
    fun answer(query: ByteArray): ByteArray? {
        val questions = parseQuestions(query) ?: return null
        val answers = linkedSetOf<Record>()
        val extra = linkedSetOf<Record>()
        for (q in questions) {
            val wanted = q.name.lowercase()
            for (service in services) {
                when {
                    wanted == "${service.type}.local".lowercase() && q.type in PTR_OR_ANY -> {
                        answers += ptr(service, TTL_RECORD)
                        extra += srv(service, TTL_RECORD)
                        extra += txt(service, TTL_RECORD)
                        extra += aRecords(TTL_HOST)
                    }
                    wanted == service.fullName.lowercase() && q.type in SRV_TXT_ANY -> {
                        if (q.type == TYPE_SRV || q.type == TYPE_ANY) answers += srv(service, TTL_HOST)
                        if (q.type == TYPE_TXT || q.type == TYPE_ANY) answers += txt(service, TTL_RECORD)
                        extra += aRecords(TTL_HOST)
                    }
                }
            }
            if (wanted == META_NAME && q.type in PTR_OR_ANY) {
                for (service in services) answers += Record(META, TYPE_PTR, CLASS_IN, TTL_RECORD, name(service.type + ".local"))
            }
            if (wanted == hostName.lowercase() && q.type in A_OR_ANY) answers += aRecords(TTL_HOST)
        }
        if (answers.isEmpty()) return null
        extra.removeAll(answers)
        return packet(answers.toList(), extra.toList())
    }

    // ---- records

    private class Record(val owner: ByteArray, val type: Int, val cls: Int, val ttl: Int, val data: ByteArray) {
        override fun equals(other: Any?) =
            other is Record && owner.contentEquals(other.owner) && type == other.type && data.contentEquals(other.data)
        override fun hashCode() = 31 * (31 * owner.contentHashCode() + type) + data.contentHashCode()
    }

    private fun ptr(service: MdnsService, ttl: Int) =
        Record(name("${service.type}.local"), TYPE_PTR, CLASS_IN, ttl, name(service.fullName))

    private fun srv(service: MdnsService, ttl: Int): Record {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0, 0, 0, 0, (service.port shr 8).toByte(), service.port.toByte()))
        out.write(name(hostName))
        return Record(name(service.fullName), TYPE_SRV, CLASS_IN or CACHE_FLUSH, ttl, out.toByteArray())
    }

    private fun txt(service: MdnsService, ttl: Int): Record {
        val out = ByteArrayOutputStream()
        for ((key, value) in service.txt) {
            val entry = "$key=$value".toByteArray(Charsets.UTF_8).let { if (it.size > 255) it.copyOf(255) else it }
            out.write(entry.size)
            out.write(entry)
        }
        if (service.txt.isEmpty()) out.write(0)
        return Record(name(service.fullName), TYPE_TXT, CLASS_IN or CACHE_FLUSH, ttl, out.toByteArray())
    }

    private fun aRecords(ttl: Int) = addresses().filter { it.size == 4 }.map {
        Record(name(hostName), TYPE_A, CLASS_IN or CACHE_FLUSH, ttl, it)
    }

    // ---- wire format

    private fun packet(answers: List<Record>, additional: List<Record>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0, 0, 0x84.toByte(), 0, 0, 0))
        out.write(shortBytes(answers.size))
        out.write(shortBytes(0))
        out.write(shortBytes(additional.size))
        for (record in answers + additional) {
            out.write(record.owner)
            out.write(shortBytes(record.type))
            out.write(shortBytes(record.cls))
            out.write(intBytes(record.ttl))
            out.write(shortBytes(record.data.size))
            out.write(record.data)
        }
        return out.toByteArray()
    }

    private class Question(val name: String, val type: Int)

    private fun parseQuestions(data: ByteArray): List<Question>? {
        if (data.size < 12) return null
        // Queries only (the QR bit is clear); a response from another host is not for us.
        if (data[2].toInt() and 0x80 != 0) return null
        val count = u16(data, 4)
        if (count == 0 || count > 32) return null
        var pos = 12
        val questions = mutableListOf<Question>()
        repeat(count) {
            val (qname, after) = readName(data, pos) ?: return null
            if (after + 4 > data.size) return null
            questions += Question(qname, u16(data, after))
            pos = after + 4
        }
        return questions
    }

    /** Reads a (possibly compressed) name at [start]; returns it and the position after it in the packet. */
    private fun readName(data: ByteArray, start: Int): Pair<String, Int>? {
        val labels = mutableListOf<String>()
        var pos = start
        var end = -1
        var jumps = 0
        while (true) {
            if (pos >= data.size) return null
            val length = data[pos].toInt() and 0xFF
            when {
                length == 0 -> {
                    if (end < 0) end = pos + 1
                    return labels.joinToString(".") to end
                }
                length and 0xC0 == 0xC0 -> {
                    if (pos + 1 >= data.size || ++jumps > 8) return null
                    if (end < 0) end = pos + 2
                    pos = ((length and 0x3F) shl 8) or (data[pos + 1].toInt() and 0xFF)
                }
                length > 63 -> return null
                else -> {
                    if (pos + 1 + length > data.size) return null
                    labels += String(data, pos + 1, length, Charsets.UTF_8)
                    pos += 1 + length
                }
            }
        }
    }

    private fun u16(data: ByteArray, at: Int) = ((data[at].toInt() and 0xFF) shl 8) or (data[at + 1].toInt() and 0xFF)

    companion object {
        const val TYPE_A = 1
        const val TYPE_PTR = 12
        const val TYPE_TXT = 16
        const val TYPE_SRV = 33
        const val TYPE_ANY = 255
        private const val CLASS_IN = 1
        private const val CACHE_FLUSH = 0x8000
        /** Records about the host (A, SRV) live 2 minutes, the others 75 minutes, as the standard advises. */
        private const val TTL_HOST = 120
        const val TTL_RECORD = 4500
        private const val META_NAME = "_services._dns-sd._udp.local"
        private val META = name(META_NAME)
        private val PTR_OR_ANY = setOf(TYPE_PTR, TYPE_ANY)
        private val SRV_TXT_ANY = setOf(TYPE_SRV, TYPE_TXT, TYPE_ANY)
        private val A_OR_ANY = setOf(TYPE_A, TYPE_ANY)

        private fun shortBytes(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
        private fun intBytes(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

        /** A name as DNS labels, without compression. */
        internal fun name(dotted: String): ByteArray {
            val out = ByteArrayOutputStream()
            // The instance label may hold dots ("v1.2 TV"): only the service part after it is split.
            for (label in splitLabels(dotted)) {
                val bytes = label.toByteArray(Charsets.UTF_8).let { if (it.size > 63) it.copyOf(63) else it }
                out.write(bytes.size)
                out.write(bytes)
            }
            out.write(0)
            return out.toByteArray()
        }

        /**
         * Splits "Instance._type._tcp.local" keeping dots inside the instance: the last three
         * labels are the service type and ".local"; the rest, if any, is one label.
         */
        private fun splitLabels(dotted: String): List<String> {
            val parts = dotted.split('.')
            val serviceLabels = if (parts.size >= 3 && parts[parts.size - 2].startsWith("_") && parts.last() == "local") 3 else 0
            if (serviceLabels == 0 || parts.size == 3) return parts
            return listOf(parts.dropLast(3).joinToString(".")) + parts.takeLast(3)
        }
    }
}
