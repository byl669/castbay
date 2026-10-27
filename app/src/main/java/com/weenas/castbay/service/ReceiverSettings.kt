package com.weenas.castbay.service

import android.content.Context

data class ReceiverSettings(
    /** The name the user chose; senders see [advertisedName]. */
    val deviceName: String = DeviceName.BRAND,
    /** Add the TV's own name after [deviceName], so several TVs on one network tell apart. */
    val appendTvName: Boolean = true,
    /** The TV's device name (or model), read from the system on load; not stored. */
    val tvName: String = "",
    val resolution: String = RESOLUTION_AUTO,
    val frameRate: String = FRAME_RATE_AUTO,
    /** "Auto" offers H.265 when the TV decodes it in hardware; otherwise H.264 only. */
    val videoCodec: String = CODEC_AUTO,
    /**
     * Who may cast: [ACCESS_OPEN] anyone on the network; [ACCESS_CONFIRM] a new device once
     * allowed on the TV ([KnownDevices]); [ACCESS_PIN] a new device enters a PIN shown on the TV,
     * once, and is remembered ([PairedDevices]); [ACCESS_PASSWORD] every device enters
     * [password]. Devices blocked in [KnownDevices] are refused whatever this is.
     */
    val access: String = ACCESS_OPEN,
    /** The password (digits) for [ACCESS_PASSWORD]; kept while another access is chosen. */
    val password: String = "",
    /**
     * What happens when another device casts while one is connected: true = it takes over
     * (the current one is disconnected), false = it is refused.
     */
    val allowTakeover: Boolean = false,
    /** Also act as a DLNA renderer, for video apps' own "cast" buttons (Bilibili, iQiyi, ...). */
    val dlnaEnabled: Boolean = true,
    /** CastBay Desktop: computers casting with the CastBay Link protocol. */
    val desktopEnabled: Boolean = true,
    /** Show a "stats for nerds" overlay (codec, resolution, bitrate, ...) while playing. */
    val showStats: Boolean = false,
    /** How mirroring and AirPlay video fill the screen: [PICTURE_FIT], [PICTURE_FILL] or [PICTURE_STRETCH]. */
    val pictureMode: String = PICTURE_FIT,
    /** Look up and show synced lyrics for AirPlay music (sends the song's title to lrclib.net). */
    val showLyrics: Boolean = false,
    /** The app's language: [LANGUAGE_SYSTEM] (the TV's), [LANGUAGE_ZH] or [LANGUAGE_EN]. */
    val language: String = LANGUAGE_SYSTEM,
    /** Look for a newer CastBay on GitHub once a day (UpdateChecker). */
    val checkUpdates: Boolean = true,
    /** Also offer test versions (GitHub pre-releases) when updating, to help check a fix; off for everybody else. */
    val testVersions: Boolean = false,
    /** After CastBay stops unexpectedly, send a problem report at the next start (off: ask). */
    val sendErrorReports: Boolean = false,
    /**
     * The music screen, which stays on for hours: [SCREEN_SAVER_BURN_IN] dims and shifts it a
     * little when idle (against burn-in), [SCREEN_SAVER_MUSIC_OFF] blacks it out while the music
     * plays on, [SCREEN_SAVER_OFF] leaves it as it is.
     */
    val screenSaver: String = SCREEN_SAVER_BURN_IN
) {
    /**
     * The display size advertised to senders, which they size mirroring to. "Auto" is the
     * TV's own display, capped at 1080p unless [allowUhd] (H.265 with a 4K-capable decoder):
     * H.264 mirroring gains nothing beyond 1080p.
     */
    fun displaySize(displayWidth: Int, displayHeight: Int, allowUhd: Boolean = false): Pair<Int, Int> =
        when (resolution) {
            "720p" -> 1280 to 720
            "1080p" -> 1920 to 1080
            else -> {
                val landscapeWidth = maxOf(displayWidth, displayHeight)
                val landscapeHeight = minOf(displayWidth, displayHeight)
                when {
                    landscapeWidth <= 0 || landscapeHeight <= 0 -> 1920 to 1080
                    landscapeHeight >= 2160 && allowUhd -> 3840 to 2160
                    landscapeHeight > 1080 -> 1920 to 1080
                    else -> landscapeWidth to landscapeHeight
                }
            }
        }

    /**
     * Whether switching from [previous] needs a running receiver restarted: connection settings
     * do (senders must reconnect); playback settings apply live and are also in the quick menu.
     */
    /** The name senders list this TV under. */
    val advertisedName: String
        get() = DeviceName.compose(deviceName, if (appendTvName) tvName else "")

    fun needsRestartComparedTo(previous: ReceiverSettings): Boolean =
        withoutLiveSettings() != previous.withoutLiveSettings()

    private fun withoutLiveSettings() = copy(
        showStats = false, pictureMode = PICTURE_FIT, showLyrics = false, language = LANGUAGE_SYSTEM, checkUpdates = true, testVersions = false,
        sendErrorReports = false, screenSaver = SCREEN_SAVER_BURN_IN
    )

    /** Frames per second senders may mirror at. "Auto" is 60: TVs decode in hardware. */
    fun maxFps(): Int = if (frameRate == "30 FPS") 30 else 60

    /** The client-access password to enforce, or "" when there is none. */
    fun requiredPassword(): String = password.takeIf { access == ACCESS_PASSWORD && isValidPassword(it) }.orEmpty()

    /** Whether new devices pair with a PIN shown on the TV. */
    fun usesPin(): Boolean = access == ACCESS_PIN

    companion object {
        const val LANGUAGE_SYSTEM = "system"
        const val LANGUAGE_ZH = "zh"
        const val LANGUAGE_EN = "en"
        val LANGUAGES = listOf(LANGUAGE_SYSTEM, LANGUAGE_ZH, LANGUAGE_EN)
        const val RESOLUTION_AUTO = "Auto"
        const val FRAME_RATE_AUTO = "Auto"
        val RESOLUTIONS = listOf(RESOLUTION_AUTO, "720p", "1080p")
        /**
         * Auto is 60, the most senders mirror at, so there is no separate 60 FPS; a stored
         * "60 FPS" from older versions loads as Auto, the same.
         */
        val FRAME_RATES = listOf(FRAME_RATE_AUTO, "30 FPS")
        const val PICTURE_FIT = "Fit"
        const val PICTURE_FILL = "Fill"
        const val PICTURE_STRETCH = "Stretch"
        val PICTURE_MODES = listOf(PICTURE_FIT, PICTURE_FILL, PICTURE_STRETCH)
        const val SCREEN_SAVER_OFF = "off"
        const val SCREEN_SAVER_BURN_IN = "burnin"
        const val SCREEN_SAVER_MUSIC_OFF = "music_off"
        val SCREEN_SAVERS = listOf(SCREEN_SAVER_BURN_IN, SCREEN_SAVER_MUSIC_OFF, SCREEN_SAVER_OFF)
        const val CODEC_AUTO = "Auto"
        const val CODEC_H264_ONLY = "H.264 only"
        val VIDEO_CODECS = listOf(CODEC_AUTO, CODEC_H264_ONLY)

        const val ACCESS_OPEN = "open"
        const val ACCESS_CONFIRM = "confirm"
        const val ACCESS_PIN = "pin"
        const val ACCESS_PASSWORD = "password"
        val ACCESS_MODES = listOf(ACCESS_OPEN, ACCESS_CONFIRM, ACCESS_PIN, ACCESS_PASSWORD)

        /** UxPlay requires client-access passwords of at least 4 characters. */
        const val MIN_PASSWORD_LENGTH = 4

        fun isValidPassword(password: String) = password.length >= MIN_PASSWORD_LENGTH && password.all { it.isDigit() }
    }
}

class ReceiverSettingsStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("receiver_settings", Context.MODE_PRIVATE)

    private val tvName = DeviceName.tvName(context.applicationContext)

    fun load() = ReceiverSettings(
        deviceName = preferences.getString("device_name", DeviceName.BRAND).orEmpty().ifBlank { DeviceName.BRAND },
        appendTvName = preferences.getBoolean("append_tv_name", true),
        tvName = tvName,
        // Values from older versions (e.g. "4K") fall back to Auto.
        resolution = preferences.getString("resolution", null)
            ?.takeIf { it in ReceiverSettings.RESOLUTIONS } ?: ReceiverSettings.RESOLUTION_AUTO,
        frameRate = preferences.getString("frame_rate", null)
            ?.takeIf { it in ReceiverSettings.FRAME_RATES } ?: ReceiverSettings.FRAME_RATE_AUTO,
        videoCodec = preferences.getString("video_codec", null)
            ?.takeIf { it in ReceiverSettings.VIDEO_CODECS } ?: ReceiverSettings.CODEC_AUTO,
        // Stored as "pin" by older versions, when the password was called a PIN.
        password = preferences.getString("pin", "").orEmpty(),
        access = preferences.getString("access", null)?.takeIf { it in ReceiverSettings.ACCESS_MODES }
            ?: legacyAccess(),
        allowTakeover = preferences.getBoolean("allow_takeover", false),
        dlnaEnabled = preferences.getBoolean("dlna_enabled", true),
        desktopEnabled = preferences.getBoolean("desktop_enabled", true),
        showStats = preferences.getBoolean("show_stats", false),
        pictureMode = preferences.getString("picture_mode", null)
            ?.takeIf { it in ReceiverSettings.PICTURE_MODES } ?: ReceiverSettings.PICTURE_FIT,
        showLyrics = preferences.getBoolean("show_lyrics", false),
        language = preferences.getString("language", null)
            ?.takeIf { it in ReceiverSettings.LANGUAGES } ?: ReceiverSettings.LANGUAGE_SYSTEM,
        checkUpdates = preferences.getBoolean("check_updates", true),
        testVersions = preferences.getBoolean("test_versions", false),
        sendErrorReports = preferences.getBoolean("send_error_reports", false),
        screenSaver = preferences.getString("screen_saver", null)
            ?.takeIf { it in ReceiverSettings.SCREEN_SAVERS } ?: ReceiverSettings.SCREEN_SAVER_BURN_IN
    )

    /** Before the access setting: a password switch, and before that a saved password alone. */
    private fun legacyAccess(): String {
        val saved = ReceiverSettings.isValidPassword(preferences.getString("pin", "").orEmpty())
        val required = preferences.getBoolean("require_password", saved)
        return if (required) ReceiverSettings.ACCESS_PASSWORD else ReceiverSettings.ACCESS_OPEN
    }

    fun save(settings: ReceiverSettings) {
        preferences.edit()
            .putString("device_name", settings.deviceName.trim().ifBlank { DeviceName.BRAND })
            .putBoolean("append_tv_name", settings.appendTvName)
            .putString("resolution", settings.resolution)
            .putString("frame_rate", settings.frameRate)
            .putString("video_codec", settings.videoCodec)
            .putString("pin", settings.password)
            .putString("access", settings.access)
            .remove("require_password")
            .putBoolean("allow_takeover", settings.allowTakeover)
            .putBoolean("dlna_enabled", settings.dlnaEnabled)
            .putBoolean("desktop_enabled", settings.desktopEnabled)
            // Receiving can no longer be turned off (Back twice on the home screen quits).
            .remove("start_on_boot")
            .putBoolean("show_stats", settings.showStats)
            .putString("picture_mode", settings.pictureMode)
            .putBoolean("show_lyrics", settings.showLyrics)
            .putString("language", settings.language)
            .putBoolean("check_updates", settings.checkUpdates)
            .putBoolean("test_versions", settings.testVersions)
            .putBoolean("send_error_reports", settings.sendErrorReports)
            .putString("screen_saver", settings.screenSaver)
            .remove("audio_latency")
            .apply()
    }
}

/**
 * The app's language, chosen in Settings, applied to a context's resources: activities and
 * the service wrap their base context with it (their strings follow it). The system language
 * needs nothing done.
 */
object AppLanguage {
    fun wrap(base: Context): Context {
        val language = ReceiverSettingsStore(base).load().language
        if (language == ReceiverSettings.LANGUAGE_SYSTEM) return base
        val locale = if (language == ReceiverSettings.LANGUAGE_ZH) java.util.Locale.SIMPLIFIED_CHINESE else java.util.Locale.ENGLISH
        val config = android.content.res.Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}
