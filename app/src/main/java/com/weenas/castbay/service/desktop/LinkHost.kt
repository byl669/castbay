package com.weenas.castbay.service.desktop

import com.weenas.castbay.service.ReceiverSettings
import com.weenas.castbay.service.VideoStats

/** How a desktop sender may go on, from the receiver's casting rules. */
enum class LinkAdmission { ADMIT, NEEDS_CODE, NEEDS_APPROVAL, BLOCKED, BUSY }

/** What CastBay Link needs from the receiver around it (the screen, the rules, the TV's dialogs). */
interface LinkHost {
    val receiverName: String

    fun settings(): ReceiverSettings

    /** May [deviceId] cast now? [paired]: it entered a pairing code before. */
    fun admission(deviceId: String, name: String, paired: Boolean): LinkAdmission

    /** Ask on the TV whether to allow a new sender; it connects again once allowed. */
    fun requestApproval(deviceId: String, name: String)

    fun showCode(code: String)
    fun hideCode()

    /** A sender was admitted: remember it as allowed. */
    fun remember(deviceId: String, name: String)

    /** The picture size [width]x[height] is about to arrive; called again when it changes. */
    fun sessionStarted(session: Any, deviceId: String, name: String, width: Int, height: Int, fps: Int)

    /** One access unit; false when the decoder dropped it and wants a key frame. */
    fun video(data: ByteArray, presentationTimeUs: Long): Boolean

    /** The session is over; [session] is what [sessionStarted] was given, so a replaced session's end is ignored. */
    fun sessionEnded(session: Any)

    fun videoStats(): VideoStats?
}
