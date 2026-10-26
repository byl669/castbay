package com.weenas.castbay.service.desktop

import org.json.JSONException
import org.json.JSONObject
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Thrown for anything a sender must not do; the session ends. */
class LinkProtocolException(message: String) : Exception(message)

/** One message of CastBay Link (desktop/docs/protocol.md, section 5). */
sealed class LinkMessage {
    data class Control(val json: JSONObject) : LinkMessage() {
        val type: String get() = json.optString("t")
    }

    /** One H.264 access unit in Annex B format. */
    class Video(val presentationTimeUs: Long, val keyFrame: Boolean, val data: ByteArray) : LinkMessage()

    /** One AAC-LC frame (raw, no ADTS header): 1024 samples per channel. */
    class Audio(val presentationTimeUs: Long, val data: ByteArray) : LinkMessage()

    /** A type this version does not know; ignored. */
    data class Unknown(val type: Int) : LinkMessage()
}

object LinkProtocol {
    const val VERSION = 1
    const val TYPE_CONTROL = 1
    const val TYPE_VIDEO = 2
    const val TYPE_AUDIO = 3

    /** An AAC frame is a few hundred bytes; no reason to take more from the network. */
    const val MAX_AUDIO_BYTES = 64 * 1024

    /** Larger messages are protocol errors; no allocation happens before the length is checked. */
    const val MAX_MESSAGE_BYTES = 8 * 1024 * 1024

    /** Control messages are small; a limit of their own keeps a sender from filling memory with JSON. */
    const val MAX_CONTROL_BYTES = 64 * 1024

    /** Real messages are flat; a little room for the future. */
    const val MAX_JSON_DEPTH = 8

    private const val VIDEO_HEADER_BYTES = 9
    private const val AUDIO_HEADER_BYTES = 8
    private const val FLAG_KEY_FRAME = 1

    /**
     * The six-digit code both sides compute after the handshake from the two certificate
     * fingerprints (32 raw bytes each); a man in the middle has a different certificate
     * towards each side, so the codes differ.
     */
    fun verificationCode(receiverFingerprint: ByteArray, senderFingerprint: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").run {
            update("castbay-link-sas-v1".toByteArray(Charsets.US_ASCII))
            update(receiverFingerprint)
            update(senderFingerprint)
            digest()
        }
        val value = ((digest[0].toLong() and 0xFF) shl 24) or ((digest[1].toLong() and 0xFF) shl 16) or
            ((digest[2].toLong() and 0xFF) shl 8) or (digest[3].toLong() and 0xFF)
        return "%06d".format(value % 1_000_000)
    }

    /** The deepest nesting of JSON arrays and objects in [json] (brackets inside strings do not count). */
    fun nestingDepth(json: ByteArray): Int {
        var depth = 0
        var deepest = 0
        var inString = false
        var escaped = false
        for (byte in json) {
            val c = byte.toInt().toChar()
            when {
                escaped -> escaped = false
                inString -> if (c == '\\') escaped = true else if (c == '"') inString = false
                c == '"' -> inString = true
                c == '[' || c == '{' -> if (++depth > deepest) deepest = depth
                c == ']' || c == '}' -> depth--
            }
        }
        return deepest
    }

    fun read(input: DataInputStream): LinkMessage? {
        val type = try {
            input.readUnsignedByte()
        } catch (e: EOFException) {
            return null
        }
        val length = input.readInt()
        val limit = when (type) {
            TYPE_CONTROL -> MAX_CONTROL_BYTES
            TYPE_AUDIO -> MAX_AUDIO_BYTES
            else -> MAX_MESSAGE_BYTES
        }
        if (length < 0 || length > limit) throw LinkProtocolException("message of $length bytes (type $type)")
        val payload = ByteArray(length)
        input.readFully(payload)
        return when (type) {
            TYPE_CONTROL -> {
                // org.json recurses on nesting: a deep array from the network overflows the stack.
                if (nestingDepth(payload) > MAX_JSON_DEPTH) throw LinkProtocolException("control message nested too deep")
                try {
                    LinkMessage.Control(JSONObject(String(payload, Charsets.UTF_8)))
                } catch (e: JSONException) {
                    throw LinkProtocolException("control message is not JSON")
                }
            }
            TYPE_VIDEO -> {
                if (length < VIDEO_HEADER_BYTES) throw LinkProtocolException("video message too short")
                var time = 0L
                for (i in 0 until 8) time = (time shl 8) or (payload[i].toLong() and 0xFF)
                val flags = payload[8].toInt()
                LinkMessage.Video(time, flags and FLAG_KEY_FRAME != 0, payload.copyOfRange(VIDEO_HEADER_BYTES, length))
            }
            TYPE_AUDIO -> {
                if (length < AUDIO_HEADER_BYTES) throw LinkProtocolException("audio message too short")
                var time = 0L
                for (i in 0 until 8) time = (time shl 8) or (payload[i].toLong() and 0xFF)
                LinkMessage.Audio(time, payload.copyOfRange(AUDIO_HEADER_BYTES, length))
            }
            else -> LinkMessage.Unknown(type)
        }
    }

    fun writeControl(output: OutputStream, json: JSONObject) {
        val payload = json.toString().toByteArray(Charsets.UTF_8)
        synchronized(output) {
            output.write(TYPE_CONTROL)
            output.write(intBytes(payload.size))
            output.write(payload)
            output.flush()
        }
    }

    /** For tests and the test sender: the same framing a sender writes. */
    fun frameVideo(presentationTimeUs: Long, keyFrame: Boolean, data: ByteArray): ByteArray {
        val length = VIDEO_HEADER_BYTES + data.size
        val out = ByteArray(5 + length)
        out[0] = TYPE_VIDEO.toByte()
        intBytes(length).copyInto(out, 1)
        for (i in 0 until 8) out[5 + i] = (presentationTimeUs shr (56 - 8 * i)).toByte()
        out[13] = if (keyFrame) FLAG_KEY_FRAME.toByte() else 0
        data.copyInto(out, 14)
        return out
    }

    /** For tests and the test sender: the same framing a sender writes. */
    fun frameAudio(presentationTimeUs: Long, data: ByteArray): ByteArray {
        val length = AUDIO_HEADER_BYTES + data.size
        val out = ByteArray(5 + length)
        out[0] = TYPE_AUDIO.toByte()
        intBytes(length).copyInto(out, 1)
        for (i in 0 until 8) out[5 + i] = (presentationTimeUs shr (56 - 8 * i)).toByte()
        data.copyInto(out, 13)
        return out
    }

    private fun intBytes(value: Int) = byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
}
