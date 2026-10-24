package com.weenas.castbay.service

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubtitleFilesTest {
    private val text = "1\n00:00:01,000 --> 00:00:03,000\n你好，映湾\n"

    @Test
    fun readsGbkAsWellAsUnicode() {
        assertEquals(text, SubtitleFiles.decode(text.toByteArray(charset("GBK"))))
        assertEquals(text, SubtitleFiles.decode(text.toByteArray(Charsets.UTF_8)))
        assertEquals(text, SubtitleFiles.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray(Charsets.UTF_8)))
        assertEquals(text, SubtitleFiles.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)))
    }

    @Test
    fun mapsFormatsToMedia3() {
        assertEquals(MimeTypes.APPLICATION_SUBRIP, SubtitleFiles.mimeType("srt"))
        assertEquals(MimeTypes.TEXT_SSA, SubtitleFiles.mimeType("ass"))
        assertNull(SubtitleFiles.mimeType("smi"))
    }

    @Test
    fun guessesTheLanguageFromTheScript() {
        assertEquals("zh", SubtitleFiles.language("第一句中文字幕：映湾测试。第二句中文字幕。"))
        assertEquals("ja", SubtitleFiles.language("これは日本語の字幕です。ありがとうございます。"))
        assertEquals("ko", SubtitleFiles.language("이것은 한국어 자막입니다 감사합니다 안녕하세요"))
        assertNull(SubtitleFiles.language("1\n00:00:01,000 --> 00:00:02,000\nHello there, general Kenobi\n"))
    }
}
