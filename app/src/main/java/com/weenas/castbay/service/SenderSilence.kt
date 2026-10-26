package com.weenas.castbay.service

/**
 * When a sender that has gone quiet is taken for gone. A phone walking out of a room or between
 * mesh Wi-Fi nodes lost its heartbeats first and its audio some seconds later (14 s apart on a
 * Google TV), then came back by itself: music counts from the last sign of life of either, and
 * waits [MUSIC_QUIET_MS]. Mirroring has no such wait: a frozen picture is not worth keeping.
 */
object SenderSilence {
    /** Heartbeats come every two seconds; missing two of them ends a mirror. */
    const val MIRROR_HEARTBEAT_MS = 5000L

    /** Music: nothing at all (heartbeat or audio) for this long. */
    const val MUSIC_QUIET_MS = 30_000L

    /**
     * @param sinceHeartbeatMs time since the last heartbeat
     * @param sinceAudioMs time since the last audio arrived, null when none did
     */
    fun gone(audioOnly: Boolean, sinceHeartbeatMs: Long, sinceAudioMs: Long?): Boolean =
        if (audioOnly) minOf(sinceHeartbeatMs, sinceAudioMs ?: Long.MAX_VALUE) > MUSIC_QUIET_MS
        else sinceHeartbeatMs > MIRROR_HEARTBEAT_MS
}
