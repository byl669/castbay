package com.weenas.castbay.plex

import android.content.Context
import androidx.core.content.edit
import com.weenas.castbay.util.Log
import java.util.UUID

/**
 * CastBay as a Plex player, next to AirPlay and DLNA: the official Plex app (phone, web) finds it
 * under "play to device" and sends it what to play.
 *
 * <p>This is the probe stage: every command the controller sends is written to the log and nothing
 * is decoded yet, so the wire format can be confirmed on a television before the playback path is
 * written against the wiki. A failure here never takes AirPlay or DLNA down with it.
 */
class PlexReceiver(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("plex_identity", Context.MODE_PRIVATE)
    private var companion: PlexCompanion? = null
    private var gdm: PlexGdm? = null

    /**
     * Plex's own clients use a 40-hex machine identifier; matching that shape keeps controllers
     * from rejecting us over the length. Stable across restarts, so the app keeps recognising the
     * television as the same player.
     */
    private val uuid: String by lazy {
        preferences.getString(KEY_UUID, null) ?: (
            UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().take(8)
            ).lowercase().also { value -> preferences.edit { putString(KEY_UUID, value) } }
    }

    @Synchronized
    fun start(name: String) {
        stop()
        try {
            val server = PlexCompanion(PlexCompanion.DEFAULT_PORT, uuid, name, log)
            val discovery = PlexGdm(server.port(), headerSafe(name), uuid, PlexCompanion.VERSION, log)
            server.start()
            discovery.start()
            companion = server
            gdm = discovery
            Log.i(TAG, "Plex player \"${headerSafe(name)}\" on port ${server.port()} (uuid $uuid)")
        } catch (error: Exception) {
            // Port 32412 or 32500 taken, or no multicast: AirPlay and DLNA carry on without Plex.
            Log.w(TAG, "Could not start the Plex player", error)
            stop()
        }
    }

    @Synchronized
    fun stop() {
        gdm?.close()
        companion?.close()
        gdm = null
        companion = null
    }

    /**
     * GDM headers carry no charset, so a non-ASCII name can break discovery on some controllers.
     * The XML of `/resources` keeps the full name; only the announcement is reduced.
     */
    private fun headerSafe(name: String): String {
        val cleaned = name.filter { it.code in 32..126 }.trim().take(32)
        return cleaned.ifBlank { "CastBay" }
    }

    private val log = object : PlexLog {
        override fun write(line: String) { Log.i(TAG, line) }
    }

    private companion object {
        const val TAG = "Plex"
        const val KEY_UUID = "uuid"
    }
}
