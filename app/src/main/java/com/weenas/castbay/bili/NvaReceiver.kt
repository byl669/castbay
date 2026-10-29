package com.weenas.castbay.bili

import android.content.Context
import com.weenas.castbay.util.Log
import java.util.UUID

/**
 * CastBay as a Bilibili cast target, beside AirPlay and DLNA.
 *
 * This is the probe build: the receiver advertises itself, answers every command and writes what
 * arrives, but nothing is fetched or played yet.
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
    fun start(name: String) {
        stop()
        val advertised = name.trim().ifBlank { "CastBay" }
        val nva = NvaServer(uuid) { advertised }
        try {
            // The same port as last time when it is free: an NVA device is addressed by it.
            val port = nva.start(preferences.getInt(KEY_PORT, Nva.PORT))
            preferences.edit().putInt(KEY_PORT, port).apply()
            val discovery = NvaSsdp(uuid) { nva.port }.also { it.start() }
            server = nva
            ssdp = discovery
            Log.i(TAG, "Bilibili receiver \"$advertised\" on port $port (uuid $uuid)")
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

    private companion object {
        const val TAG = "CastBayBili"
        const val KEY_UUID = "uuid"
        const val KEY_PORT = "port"
    }
}
