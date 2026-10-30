package com.weenas.castbay.bili

import android.content.Context
import com.weenas.castbay.dlna.SsdpServer
import com.weenas.castbay.util.Log
import java.util.UUID

/**
 * CastBay as a Bilibili cast target, beside AirPlay, DLNA and Plex.
 *
 * Bilibili's app only hands 720p to a plain DLNA renderer; a device that speaks its own
 * protocol is treated as one of its boxes and the receiver fetches the streams itself, so this
 * one runs next to the DLNA renderer and takes the cast before DLNA does.
 */
class NvaReceiver(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("bili_identity", Context.MODE_PRIVATE)

    private var server: NvaServer? = null
    private var ssdp: NvaSsdp? = null

    /** Stable across restarts, so the phone keeps recognising this TV. */
    val uuid: String by lazy {
        preferences.getString(KEY_UUID, null) ?: UUID.randomUUID().toString().also {
            preferences.edit().putString(KEY_UUID, it).apply()
        }
    }

    @Synchronized
    fun start(name: String, player: NvaPlayer, discoveryHost: SsdpServer? = null) {
        stop()
        val advertised = name.trim().ifBlank { "CastBay" }
        val nva = NvaServer(uuid, player)
        try {
            // The same port as last time when it is free: an NVA device is addressed by it.
            val port = nva.start(preferences.getInt(KEY_PORT, Nva.PORT))
            preferences.edit().putInt(KEY_PORT, port).apply()
            val discovery = NvaSsdp(uuid) { nva.port }.also { it.start(discoveryHost) }
            server = nva
            ssdp = discovery
            Log.i(TAG, "Bilibili receiver \"$advertised\" advertised as \"${Nva.DEVICE_NAME}\" on port $port (uuid $uuid)")
        } catch (error: Exception) {
            Log.e(TAG, "Could not start the Bilibili receiver", error)
            nva.stop()
            stop()
        }
    }

    @Synchronized
    fun stop() {
        ssdp?.stop()
        server?.stop()
        ssdp = null
        server = null
    }

    /** Casting was ended on the TV: the phone's UI should stop showing it as playing. */
    fun stopPlayback() {
        server?.stopPlayback()
    }

    /** The video ended: the phone decides whether to cast the next one. */
    fun finished() {
        server?.finished()
    }

    private companion object {
        const val TAG = "CastBayBili"
        const val KEY_UUID = "uuid"
        const val KEY_PORT = "port"
    }
}
