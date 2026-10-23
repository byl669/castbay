package com.weenas.castbay.service

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdaptiveMimeTypeTest {
    @Test
    fun tellsHlsAndDashFromTheSendersMimeType() {
        assertEquals(MimeTypes.APPLICATION_M3U8, adaptiveMimeType("application/x-mpegURL"))
        assertEquals(MimeTypes.APPLICATION_M3U8, adaptiveMimeType(" application/vnd.apple.mpegurl"))
        assertEquals(MimeTypes.APPLICATION_MPD, adaptiveMimeType("application/dash+xml"))
        // Progressive files are told apart by their content.
        assertNull(adaptiveMimeType("video/mp4"))
        assertNull(adaptiveMimeType(null))
    }
}
