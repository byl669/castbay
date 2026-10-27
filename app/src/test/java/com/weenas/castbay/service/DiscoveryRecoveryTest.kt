package com.weenas.castbay.service

import android.net.nsd.NsdManager
import com.weenas.castbay.service.DiscoveryRecovery.Step
import org.junit.Assert.assertEquals
import org.junit.Test

class DiscoveryRecoveryTest {
    @Test
    fun `an internal error is retried twice, then the receiver announces by itself`() {
        // The AIUniBOX-M11 of #214 answers code 0 (FAILURE_INTERNAL_ERROR) every time.
        assertEquals(0, NsdManager.FAILURE_INTERNAL_ERROR)
        assertEquals(Step.RETRY, DiscoveryRecovery.after(NsdManager.FAILURE_INTERNAL_ERROR, 0))
        assertEquals(Step.RETRY, DiscoveryRecovery.after(NsdManager.FAILURE_INTERNAL_ERROR, 1))
        assertEquals(Step.ANNOUNCE_BY_ITSELF, DiscoveryRecovery.after(NsdManager.FAILURE_INTERNAL_ERROR, 2))
    }

    @Test
    fun `an old registration still active is retried like an internal error`() {
        assertEquals(Step.RETRY, DiscoveryRecovery.after(NsdManager.FAILURE_ALREADY_ACTIVE, 0))
        assertEquals(Step.ANNOUNCE_BY_ITSELF, DiscoveryRecovery.after(NsdManager.FAILURE_ALREADY_ACTIVE, DiscoveryRecovery.MAX_RETRIES))
    }

    @Test
    fun `no answer at all is retried, then the receiver announces by itself`() {
        // #239: the registration was never confirmed.
        assertEquals(Step.RETRY, DiscoveryRecovery.after(null, 0))
        assertEquals(Step.RETRY, DiscoveryRecovery.after(null, DiscoveryRecovery.MAX_RETRIES - 1))
        assertEquals(Step.ANNOUNCE_BY_ITSELF, DiscoveryRecovery.after(null, DiscoveryRecovery.MAX_RETRIES))
    }

    @Test
    fun `any other failure goes straight to announcing by itself`() {
        assertEquals(Step.ANNOUNCE_BY_ITSELF, DiscoveryRecovery.after(NsdManager.FAILURE_MAX_LIMIT, 0))
        assertEquals(Step.ANNOUNCE_BY_ITSELF, DiscoveryRecovery.after(99, 0))
    }
}
