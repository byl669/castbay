package com.weenas.castbay.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SenderSilenceTest {
    @Test
    fun `a mirror is gone after two missed heartbeats`() {
        assertFalse(SenderSilence.gone(false, 4_900, null))
        assertTrue(SenderSilence.gone(false, 5_100, null))
    }

    @Test
    fun `music waits while the audio still arrives, whatever the heartbeats do`() {
        assertFalse(SenderSilence.gone(true, 60_000, 500))
    }

    @Test
    fun `music waits while heartbeats still come, as in a pause`() {
        assertFalse(SenderSilence.gone(true, 1_500, 60_000))
    }

    @Test
    fun `music counts from the later of the two signs of life`() {
        // The Google TV's drop: heartbeats stopped 20.9 s ago, the audio 6.9 s ago: not yet gone.
        assertFalse(SenderSilence.gone(true, 20_893, 6_885))
        assertFalse(SenderSilence.gone(true, 31_000, 29_000))
        assertTrue(SenderSilence.gone(true, 45_000, 31_000))
    }

    @Test
    fun `music with no audio ever counts from the heartbeat`() {
        assertFalse(SenderSilence.gone(true, 29_000, null))
        assertTrue(SenderSilence.gone(true, 31_000, null))
    }
}
