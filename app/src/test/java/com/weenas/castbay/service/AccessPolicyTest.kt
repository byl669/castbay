package com.weenas.castbay.service

import com.weenas.castbay.service.AccessPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessPolicyTest {
    private val open = ReceiverSettings(access = ReceiverSettings.ACCESS_OPEN)
    private val confirm = ReceiverSettings(access = ReceiverSettings.ACCESS_CONFIRM)
    private val allowed = KnownDevice("a", "Phone", "iPhone", allowed = true)
    private val blocked = KnownDevice("a", "Phone", "iPhone", allowed = false)

    @Test
    fun blockedDevicesNeverCast() {
        assertEquals(Decision.BLOCKED, AccessPolicy.decide(open, "a", blocked, null, needsApproval = false))
        assertEquals(Decision.BLOCKED, AccessPolicy.decide(open.copy(allowTakeover = true), "a", blocked, "b", needsApproval = false))
    }

    @Test
    fun newDevicesAreAskedAboutOnlyWhenApprovalIsNeeded() {
        assertEquals(Decision.ASK, AccessPolicy.decide(confirm, "a", null, null, needsApproval = true))
        assertEquals(Decision.ADMIT, AccessPolicy.decide(confirm, "a", allowed, null, needsApproval = true))
        assertEquals(Decision.ADMIT, AccessPolicy.decide(open, "a", null, null, needsApproval = false))
    }

    @Test
    fun aSecondDeviceFollowsTheTakeoverSetting() {
        assertEquals(Decision.BUSY, AccessPolicy.decide(open, "a", allowed, "b", needsApproval = false))
        assertEquals(Decision.ADMIT, AccessPolicy.decide(open.copy(allowTakeover = true), "a", allowed, "b", needsApproval = false))
        // The device already casting carries on.
        assertEquals(Decision.ADMIT, AccessPolicy.decide(open, "a", allowed, "a", needsApproval = false))
        // Refused while busy before anyone is asked about it.
        assertEquals(Decision.BUSY, AccessPolicy.decide(confirm, "a", null, "b", needsApproval = true))
    }

    @Test
    fun dlnaSendersAreAskedAboutWhateverTheVerification() {
        assertFalse(AccessPolicy.dlnaNeedsApproval(open))
        for (access in listOf(ReceiverSettings.ACCESS_CONFIRM, ReceiverSettings.ACCESS_PIN, ReceiverSettings.ACCESS_PASSWORD)) {
            assertTrue(AccessPolicy.dlnaNeedsApproval(ReceiverSettings(access = access)))
        }
        // AirPlay senders enter their PIN or password themselves.
        assertTrue(AccessPolicy.airPlayNeedsApproval(confirm))
        assertFalse(AccessPolicy.airPlayNeedsApproval(ReceiverSettings(access = ReceiverSettings.ACCESS_PIN)))
        assertEquals("dlna:192.168.1.5", AccessPolicy.dlnaDeviceId("::ffff:192.168.1.5"))
    }
}
