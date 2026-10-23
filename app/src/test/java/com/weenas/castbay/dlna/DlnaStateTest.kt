package com.weenas.castbay.dlna

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DlnaStateTest {
    @Test
    fun offersFlacOnlyWhereAndroidDecodesIt() {
        val old = DlnaState.sinkProtocolInfo(26)
        val new = DlnaState.sinkProtocolInfo(27)
        assertFalse("audio/flac" in old)
        assertTrue("http-get:*:audio/flac:*" in new)
        for (sink in listOf(old, new)) {
            assertTrue("http-get:*:application/dash+xml:*" in sink)
            assertTrue("http-get:*:application/vnd.apple.mpegurl:*" in sink)
            assertFalse("image/" in sink)
        }
    }
}
