package com.weenas.castbay.service

import android.net.Uri
import android.util.Base64
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import com.weenas.castbay.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Subtitle files a sender names for its video (DLNA), fetched before playback and handed to
 * the player as UTF-8: Chinese .srt files are often GBK, which Media3 would read as UTF-8 and
 * garble. Blocking; call off the main thread.
 */
object SubtitleFiles {
    private const val TAG = "CastBaySubtitles"
    private const val TIMEOUT_MS = 5_000
    private const val MAX_BYTES = 2 * 1024 * 1024

    /** A side-loaded subtitle track for [url] in [format] ("srt", "vtt", "ass", "ttml"); null if it can't be had. */
    fun load(url: String, format: String): MediaItem.SubtitleConfiguration? {
        val mimeType = mimeType(format) ?: return null
        return try {
            val text = decode(fetch(url))
            val data = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            MediaItem.SubtitleConfiguration.Builder(Uri.parse("data:text/plain;base64,$data"))
                .setMimeType(mimeType)
                // Named in the quick menu ("Chinese" rather than "Track 1"): DLNA doesn't say.
                .setLanguage(language(text))
                // Sent with the video, so shown from the start; the quick menu turns them off.
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
                .also { Log.i(TAG, "Subtitles ($format, ${text.length} characters) from ${Log.personal(url)}") }
        } catch (error: Exception) {
            Log.w(TAG, "Couldn't load subtitles from ${Log.personal(url)}: ${error.message}")
            null
        }
    }

    /** "ja" with kana, "ko" with Hangul, "zh" with Han characters, else null (unknown). */
    internal fun language(text: String): String? {
        var han = 0
        var kana = 0
        var hangul = 0
        for (char in text) {
            when (Character.UnicodeBlock.of(char)) {
                Character.UnicodeBlock.HIRAGANA, Character.UnicodeBlock.KATAKANA -> kana++
                Character.UnicodeBlock.HANGUL_SYLLABLES -> hangul++
                Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS -> han++
                else -> Unit
            }
        }
        return when {
            kana >= MIN_SCRIPT_CHARS -> "ja"
            hangul >= MIN_SCRIPT_CHARS -> "ko"
            han >= MIN_SCRIPT_CHARS -> "zh"
            else -> null
        }
    }

    private const val MIN_SCRIPT_CHARS = 10

    internal fun mimeType(format: String): String? = when (format) {
        "srt" -> MimeTypes.APPLICATION_SUBRIP
        "vtt" -> MimeTypes.TEXT_VTT
        "ass" -> MimeTypes.TEXT_SSA
        "ttml" -> MimeTypes.APPLICATION_TTML
        else -> null
    }

    /** UTF-8 or UTF-16 (by their byte order marks), UTF-8 if it decodes as such, else GB18030 (GBK's superset). */
    internal fun decode(bytes: ByteArray): String {
        fun startsWith(vararg mark: Int) = bytes.size >= mark.size && mark.indices.all { bytes[it] == mark[it].toByte() }
        return when {
            startsWith(0xEF, 0xBB, 0xBF) -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            startsWith(0xFF, 0xFE) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            startsWith(0xFE, 0xFF) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            else -> try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString()
            } catch (notUtf8: CharacterCodingException) {
                String(bytes, Charset.forName("GB18030"))
            }
        }
    }

    private fun fetch(url: String): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw java.io.IOException("HTTP ${connection.responseCode}")
            connection.inputStream.use { input ->
                val bytes = input.readNBytesCompat(MAX_BYTES + 1)
                if (bytes.size > MAX_BYTES) throw java.io.IOException("over ${MAX_BYTES / 1024} KB")
                bytes
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Up to [limit] bytes (InputStream.readNBytes needs Android 13). */
    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (out.size() < limit) {
            val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}
