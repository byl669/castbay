package com.weenas.castbay.service

/**
 * Who may cast, the same for AirPlay and DLNA: Settings' casting verification, the devices
 * allowed or blocked on the TV ([KnownDevices]) and the rule for a second device.
 */
object AccessPolicy {
    enum class Decision {
        ADMIT,
        /** A new device: ask on the TV whether to allow it; it casts again once allowed. */
        ASK,
        BLOCKED,
        /** Another device is casting, and the second-device rule refuses this one. */
        BUSY
    }

    /**
     * Whether [deviceId] may cast. [known] is what the TV keeps about it, [onScreen] the device
     * casting now (null: none), [needsApproval] whether new devices are asked about on the TV.
     * A blocked device never may; while another casts, a device only with
     * [ReceiverSettings.allowTakeover]; a new one, once allowed when approval is needed.
     */
    fun decide(
        settings: ReceiverSettings,
        deviceId: String,
        known: KnownDevice?,
        onScreen: String?,
        needsApproval: Boolean
    ): Decision = when {
        known?.allowed == false -> Decision.BLOCKED
        onScreen != null && onScreen != deviceId && !settings.allowTakeover -> Decision.BUSY
        known == null && needsApproval -> Decision.ASK
        else -> Decision.ADMIT
    }

    /** AirPlay senders enter a PIN or password themselves (UxPlay checks it): only Confirm asks on the TV. */
    fun airPlayNeedsApproval(settings: ReceiverSettings) = settings.access == ReceiverSettings.ACCESS_CONFIRM

    /** DLNA senders can't enter a PIN or password: with any verification, the TV asks, once. */
    fun dlnaNeedsApproval(settings: ReceiverSettings) = settings.access != ReceiverSettings.ACCESS_OPEN

    /** A DLNA sender has no device ID of its own; it is known by its network address. */
    fun dlnaDeviceId(address: String) = "dlna:${address.removePrefix("::ffff:")}"
}
