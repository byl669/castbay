package com.weenas.castbay.service

import android.net.nsd.NsdManager

/**
 * What to do when Android's NsdManager fails to register a service or never answers: try again a
 * couple of times (an internal error or an old registration still active is often gone a moment
 * later), then announce the services ourselves ([MdnsResponder]). Any other failure code is not
 * worth waiting on.
 */
object DiscoveryRecovery {
    enum class Step { RETRY, ANNOUNCE_BY_ITSELF }

    const val MAX_RETRIES = 2

    /** @param errorCode NsdManager's, or null when it did not answer in time; @param retries retries made so far. */
    fun after(errorCode: Int?, retries: Int): Step {
        val retryable = errorCode == null ||
            errorCode == NsdManager.FAILURE_INTERNAL_ERROR ||
            errorCode == NsdManager.FAILURE_ALREADY_ACTIVE
        return if (retries < MAX_RETRIES && retryable) Step.RETRY else Step.ANNOUNCE_BY_ITSELF
    }
}
