package com.weenas.castbay.service

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import com.weenas.castbay.util.Diagnostics
import com.weenas.castbay.util.Log
import java.io.IOException
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.Locale

/**
 * TXT key/value pairs for `_airplay._tcp` and `_raop._tcp`.
 * They must be identical to what the protocol core returns from /info and uses
 * in its handshake, so they normally come from [NativeBridge.discoveryRecords].
 */
data class DiscoveryRecords(
    val airplay: Map<String, String>,
    val raop: Map<String, String>
) {
    companion object {
        /** Mirror of RPiPlay's lib/dnssdint.h, used only when the native library is missing. */
        val FALLBACK = DiscoveryRecords(
            airplay = linkedMapOf(
                "features" to "0x5A7FFEE6",
                "flags" to "0x4",
                "model" to "AppleTV2,1",
                "pk" to "b07727d6f6cd6e08b58ede525ec3cdeaa252ad9f683feb212ef8a205246554e7",
                "pi" to "2e388006-13ba-4041-9a67-25dd4a43d536",
                "srcvers" to "220.68",
                "vv" to "2"
            ),
            raop = linkedMapOf(
                "ch" to "2",
                "cn" to "0,1,2,3",
                "da" to "true",
                "et" to "0,3,5",
                "vv" to "2",
                "ft" to "0x5A7FFEE6",
                "am" to "AppleTV2,1",
                "md" to "0,1,2",
                "rhd" to "5.6.0.0",
                "pw" to "false",
                "sr" to "44100",
                "ss" to "16",
                "sv" to "false",
                "tp" to "UDP",
                "txtvers" to "1",
                "sf" to "0x4",
                "vs" to "220.68",
                "vn" to "65537",
                "pk" to "b07727d6f6cd6e08b58ede525ec3cdeaa252ad9f683feb212ef8a205246554e7"
            )
        )
    }
}

/** Where one DNS-SD service's registration with Android's NsdManager stands. */
sealed interface Registration {
    /** Not asked for since the app started, or withdrawn when the receiver stopped. */
    data object Off : Registration
    data object Pending : Registration
    data class Registered(val port: Int) : Registration
    /** [errorCode] is NsdManager's; null when registering threw instead of calling back. */
    data class Failed(val errorCode: Int?) : Registration
}

/** A registration that failed, kept after the advertiser stops so Diagnostics can show it. */
data class DiscoveryFailure(val serviceType: String, val errorCode: Int?, val atMs: Long)

/** What the network check reads: each service's registration and the latest failure. */
data class DiscoveryStatus(
    val airplay: Registration = Registration.Off,
    val raop: Registration = Registration.Off,
    val lastFailure: DiscoveryFailure? = null
)

/** Publishes AirPlay and RAOP DNS-SD records for the active protocol listener. */
class AirPlayDiscoveryAdvertiser(context: Context) {
    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val preferences = appContext.getSharedPreferences("airplay_identity", Context.MODE_PRIVATE)

    private var socket: ServerSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val registrations = mutableListOf<NsdManager.RegistrationListener>()
    private val registeredTypes = mutableSetOf<String>()
    private var running = false
    private var retries = 0
    /** NsdManager did not register the services, so [responder] announces them (see [MdnsResponder]). */
    private var ownActive = false
    private val responder = MdnsResponder(appContext)
    private var announced: Triple<String, Int, DiscoveryRecords>? = null
    private var registerAll: (() -> Unit)? = null
    /** NsdManager sometimes never answers when the same names were withdrawn a moment ago: try again. */
    private val watchdog: Runnable = Runnable {
        if (!running || registeredTypes.size == 2) return@Runnable
        if (retries >= MAX_RETRIES) {
            Diagnostics.record("discovery", "Registering did not finish after $retries retries")
            if (!startOwnResponder("NsdManager did not answer")) noteFailure(AIRPLAY, null)
            return@Runnable
        }
        retries++
        Diagnostics.record("discovery", "Registering is slow, trying again ($retries)")
        unregisterAll()
        registerAll?.invoke()
        mainHandler.postDelayed(watchdog, REGISTER_TIMEOUT_MS)
    }
    private var onReady: (() -> Unit)? = null
    private var onError: ((String) -> Unit)? = null

    @Volatile private var _status = DiscoveryStatus()
    /** Read from any thread; updated on the main thread as NsdManager calls back. */
    val status: DiscoveryStatus get() = _status

    /** Uses [protocolPort] when native AirPlay is active, otherwise opens a discovery-only probe. */
    fun start(
        name: String,
        protocolPort: Int? = null,
        records: DiscoveryRecords = DiscoveryRecords.FALLBACK,
        onReady: () -> Unit,
        onError: (String) -> Unit
    ): Boolean {
        if (running) return true
        this.onReady = onReady
        this.onError = onError
        val displayName = name.trim().ifBlank { "CastBay" }.take(60)
        _status = _status.copy(airplay = Registration.Pending, raop = Registration.Pending)
        var registering: String? = null
        return try {
            val listenerSocket = protocolPort?.let { null } ?: ServerSocket(0)
            socket = listenerSocket
            running = true
            listenerSocket?.let {
                Thread({ acceptAndCloseConnections(it) }, "AirPlay-discovery-probe").apply {
                    isDaemon = true
                    start()
                }
            }
            val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifiManager.createMulticastLock("CastBay-mDNS").apply {
                setReferenceCounted(false)
                acquire()
            }

            val deviceId = deviceId()
            val advertisedPort = protocolPort ?: requireNotNull(listenerSocket).localPort
            announced = Triple(displayName, advertisedPort, records)
            ownActive = false
            // Same layout as RPiPlay's dnssd_register_airplay / dnssd_register_raop.
            registerAll = {
                register(AIRPLAY, displayName, advertisedPort,
                    linkedMapOf("deviceid" to deviceId) + records.airplay)
                registering = RAOP
                register(RAOP, "${deviceId.replace(":", "")}@$displayName", advertisedPort,
                    records.raop)
            }
            registering = AIRPLAY
            retries = 0
            mainHandler.removeCallbacks(watchdog)
            if (com.weenas.castbay.BuildConfig.DEBUG && preferences.getBoolean("force_own_mdns", false)) {
                // For testing the fallback without a box whose NsdManager is broken.
                mainHandler.post { startOwnResponder("forced for testing") }
            } else {
                registerAll?.invoke()
                mainHandler.postDelayed(watchdog, REGISTER_TIMEOUT_MS)
            }
            true
        } catch (error: Exception) {
            Log.e(TAG, "Could not advertise AirPlay discovery", error)
            stop()
            // Failing before either service was registered (the socket, the multicast lock)
            // leaves AirPlay unadvertised, so it is put down to that.
            noteFailure(registering ?: AIRPLAY, null)
            false
        }
    }

    /** Announces the services without NsdManager. Main thread. True when it is announcing. */
    private fun startOwnResponder(reason: String): Boolean {
        if (ownActive) return true
        val (name, port, records) = announced ?: return false
        mainHandler.removeCallbacks(watchdog)
        unregisterAll()
        val id = deviceId()
        val services = listOf(
            MdnsService(AIRPLAY, name, port, linkedMapOf("deviceid" to id) + records.airplay),
            MdnsService(RAOP, "${id.replace(":", "")}@$name", port, records.raop)
        )
        val host = "castbay-" + id.replace(":", "").takeLast(6).lowercase()
        if (!responder.start(host, services)) return false
        ownActive = true
        Diagnostics.record("discovery", "Announcing by itself because: $reason")
        Log.i(TAG, "Announcing by itself because: $reason")
        update(AIRPLAY, Registration.Registered(port))
        update(RAOP, Registration.Registered(port))
        registeredTypes.addAll(listOf(AIRPLAY, RAOP))
        onReady?.invoke()
        return true
    }

    private fun unregisterAll() {
        registrations.forEach { listener ->
            try {
                nsdManager.unregisterService(listener)
            } catch (error: IllegalArgumentException) {
                Log.d(TAG, "mDNS registration was not active", error)
            }
        }
        registrations.clear()
        registeredTypes.clear()
    }

    fun stop() {
        running = false
        mainHandler.removeCallbacks(watchdog)
        responder.stop()
        ownActive = false
        registerAll = null
        unregisterAll()
        // A failure stays visible; anything else is no longer advertised.
        _status = _status.copy(airplay = withdrawn(_status.airplay), raop = withdrawn(_status.raop))
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
        socket?.close()
        socket = null
        onReady = null
        onError = null
    }

    private fun register(type: String, name: String, port: Int, attributes: Map<String, String>) {
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = type
            this.port = port
            attributes.forEach { (key, value) -> setAttribute(key, value) }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                mainHandler.post {
                    if (!running || ownActive) return@post
                    Log.i(TAG, "Registered $type as ${serviceInfo.serviceName} on port $port")
                    registeredTypes.add(type)
                    update(type, Registration.Registered(port))
                    if (registeredTypes.size == 2) onReady?.invoke()
                }
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                mainHandler.post {
                    if (!running || ownActive) return@post
                    // An internal error or an old registration still active is often gone a moment
                    // later (seen on a TV box with an old Android, code 0): try again first.
                    if (retries < MAX_RETRIES && (errorCode == NsdManager.FAILURE_INTERNAL_ERROR || errorCode == NsdManager.FAILURE_ALREADY_ACTIVE)) {
                        mainHandler.removeCallbacks(watchdog)
                        mainHandler.postDelayed(watchdog, RETRY_DELAY_MS)
                        Diagnostics.record("discovery", "Registering $type failed (NsdManager code $errorCode), will try again")
                        return@post
                    }
                    // The system will not do it: announce by ourselves, and only if that fails too give up.
                    if (startOwnResponder("NsdManager failed ($type, code $errorCode)")) return@post
                    val callback = onError
                    stop()
                    noteFailure(type, errorCode)
                    callback?.invoke("Local network discovery failed ($type, code $errorCode)")
                }
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Could not unregister $type: $errorCode")
            }
        }
        registrations.add(listener)
        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun update(type: String, registration: Registration) {
        _status = if (type == AIRPLAY) _status.copy(airplay = registration) else _status.copy(raop = registration)
    }

    private fun withdrawn(registration: Registration) =
        if (registration is Registration.Failed) registration else Registration.Off

    private fun noteFailure(type: String, errorCode: Int?) {
        update(type, Registration.Failed(errorCode))
        _status = _status.copy(lastFailure = DiscoveryFailure(type, errorCode, System.currentTimeMillis()))
        Diagnostics.record("discovery", "Registering $type failed" + (errorCode?.let { " (NsdManager code $it)" } ?: ""))
    }

    private fun acceptAndCloseConnections(listenerSocket: ServerSocket) {
        while (!listenerSocket.isClosed) {
            try {
                listenerSocket.accept().close()
            } catch (_: IOException) {
                break
            }
        }
    }

    private fun deviceId(): String {
        preferences.getString("device_id", null)?.let { return it }
        val bytes = ByteArray(6).also(SecureRandom()::nextBytes)
        bytes[0] = ((bytes[0].toInt() and 0xFE) or 0x02).toByte()
        val id = bytes.joinToString(":") { String.format(Locale.US, "%02X", it.toInt() and 0xFF) }
        preferences.edit().putString("device_id", id).apply()
        return id
    }

    fun hardwareAddress(): ByteArray = deviceId().split(":")
        .map { it.toInt(16).toByte() }
        .toByteArray()

    companion object {
        private const val TAG = "AirPlayDiscovery"
        const val AIRPLAY = "_airplay._tcp"
        const val RAOP = "_raop._tcp"
        private const val REGISTER_TIMEOUT_MS = 8_000L
        private const val MAX_RETRIES = 2
        private const val RETRY_DELAY_MS = 1_500L
    }
}
