package com.weenas.castbay.service.desktop

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import com.weenas.castbay.service.MdnsResponder
import com.weenas.castbay.service.MdnsService
import com.weenas.castbay.service.ReceiverSettings
import com.weenas.castbay.util.Diagnostics
import com.weenas.castbay.util.Log

/** Publishes the `_castbay._tcp` service desktop senders browse for (desktop/docs/protocol.md, section 2). */
class LinkDiscovery(context: Context) {
    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var listener: NsdManager.RegistrationListener? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /** When NsdManager does not register the service, the receiver announces it itself ([MdnsResponder]). */
    private val responder = MdnsResponder(appContext)
    private var own: Pair<String, MdnsService>? = null
    @Volatile private var registered = false
    private val watchdog = Runnable { announceByItself("NsdManager did not answer in time") }

    @Synchronized
    fun start(name: String, port: Int, receiverId: String, settings: ReceiverSettings) {
        stop()
        val displayName = name.trim().ifBlank { "CastBay" }.take(60)
        val attributes = linkedMapOf(
            "v" to LinkProtocol.VERSION.toString(),
            "id" to receiverId,
            "name" to displayName,
            "pair" to pairMode(settings)
        )
        if (settings.requiredPassword().isNotEmpty()) attributes["pw"] = "1"
        own = "castbay-" + receiverId.takeLast(6).lowercase() to MdnsService(TYPE, displayName, port, attributes)
        registered = false
        if (com.weenas.castbay.BuildConfig.DEBUG &&
            appContext.getSharedPreferences("airplay_identity", Context.MODE_PRIVATE).getBoolean("force_own_mdns", false)
        ) {
            announceByItself("forced for testing")
            return
        }
        val info = NsdServiceInfo().apply {
            serviceName = displayName
            serviceType = TYPE
            this.port = port
            attributes.forEach { (key, value) -> setAttribute(key, value) }
        }
        val registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                registered = true
                mainHandler.removeCallbacks(watchdog)
                Log.i(TAG, "Registered $TYPE as ${serviceInfo.serviceName} on port $port")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Could not register $TYPE (code $errorCode)")
                announceByItself("NsdManager failed (code $errorCode)")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        try {
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration)
            listener = registration
            mainHandler.postDelayed(watchdog, REGISTER_TIMEOUT_MS)
        } catch (error: Exception) {
            Log.w(TAG, "Could not register $TYPE", error)
            announceByItself("NsdManager threw ${error.javaClass.simpleName}")
        }
    }

    @Synchronized
    private fun announceByItself(reason: String) {
        val (host, service) = own ?: return
        if (registered || responder.running) return
        mainHandler.removeCallbacks(watchdog)
        Diagnostics.record("discovery", "CastBay Link announces by itself because: $reason")
        Log.i(TAG, "Announcing $TYPE by itself because: $reason")
        responder.start(host, listOf(service))
    }

    @Synchronized
    fun stop() {
        mainHandler.removeCallbacks(watchdog)
        responder.stop()
        own = null
        val registration = listener ?: return
        listener = null
        try {
            nsdManager.unregisterService(registration)
        } catch (error: IllegalArgumentException) {
            Log.d(TAG, "$TYPE was not registered", error)
        }
    }

    companion object {
        const val TYPE = "_castbay._tcp"
        private const val TAG = "CastBayLink"
        private const val REGISTER_TIMEOUT_MS = 8_000L

        /** What senders should expect: `open`, `ask` (allowed on the TV) or `pin` (a code to type). */
        fun pairMode(settings: ReceiverSettings) = when (settings.access) {
            ReceiverSettings.ACCESS_CONFIRM -> "ask"
            ReceiverSettings.ACCESS_PIN, ReceiverSettings.ACCESS_PASSWORD -> "pin"
            else -> "open"
        }
    }
}
