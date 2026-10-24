package com.weenas.castbay.service

import com.weenas.castbay.service.desktop.PairingGuard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingGuardTest {
    private var now = 1_000L
    private val guard = PairingGuard { now }

    @Test
    fun threeWrongCodesInARowLockPairingForAMinute() {
        repeat(2) { guard.failed() }
        assertFalse(guard.locked())
        guard.failed()
        assertTrue(guard.locked())
        now += PairingGuard.LOCK_MS - 1
        assertTrue(guard.locked())
        now += 1
        assertFalse(guard.locked())
    }

    @Test
    fun aRightCodeStartsTheCountAgain() {
        repeat(2) { guard.failed() }
        guard.succeeded()
        repeat(2) { guard.failed() }
        assertFalse(guard.locked())
    }

    @Test
    fun theCountStartsAgainAfterALock() {
        repeat(3) { guard.failed() }
        now += PairingGuard.LOCK_MS
        repeat(2) { guard.failed() }
        assertFalse(guard.locked())
    }
}
