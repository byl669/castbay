package com.weenas.castbay.bili

/**
 * What the NVA server needs from the TV's player. The protocol side owns the conversation with
 * the phone (which command, what to report back); this side owns the picture.
 *
 * Every method may be called from a protocol thread, so implementations post to the main
 * thread where the player lives.
 */
interface NvaPlayer {
    /**
     * Plays [manifest] (a local MPD this app serves) from [startSec], or the file itself when
     * the API had no DASH. [durationSec] is what the API says the video lasts, for the progress
     * reports the phone draws before the player knows the length.
     */
    fun play(manifest: String, startSec: Int, durationSec: Int, title: String, qn: Int, speed: Float)

    fun setPaused(paused: Boolean)

    fun seekTo(sec: Int)

    fun stop()

    fun setVolume(percent: Int)

    fun setSpeed(rate: Float)

    /** Where playback is, for the once-a-second report; null when nothing is playing. */
    fun progress(): Progress?

    /** [positionSec] and [durationSec] are whole seconds; [loading] covers buffering and starting. */
    data class Progress(val positionSec: Int, val durationSec: Int, val playing: Boolean, val loading: Boolean)
}
