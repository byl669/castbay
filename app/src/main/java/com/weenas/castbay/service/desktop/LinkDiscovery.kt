package com.weenas.castbay.service.desktop

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.weenas.castbay.service.ReceiverSettings
import com.weenas.castbay.util.Log

/** Publishes the `_castbay._tcp` service desktop senders browse for (desktop/docs/protocol.md, section 2). */
class LinkDiscovery(context: Context) {
    private val nsdManager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var listener: NsdManager.RegistrationListener? = null

    @Synchronized
    fun start(name: String, port: Int, receiverId: String, settings: ReceiverSettings) {
        stop()
        val displayName = name.trim().ifBlank { "CastBay" }.take(60)
        val info = NsdServiceInfo().apply {
            serviceName = displayName
            serviceType = TYPE
            this.port = port
            setAttribute("v", LinkProtocol.VERSION.toString())
            setAttribute("id", receiverId)
            setAttribute("name", displayName)
            setAttribute("pair", pairMode(settings))
            if (settings.requiredPassword().isNotEmpty()) setAttribute("pw", "1")
        }
        val registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "Registered $TYPE as ${serviceInfo.serviceName} on port $port")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Could not register $TYPE (code $errorCode)")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        try {
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration)
            listener = registration
        } catch (error: Exception) {
            Log.w(TAG, "Could not register $TYPE", error)
        }
    }

    @Synchronized
    fun stop() {
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

        /** What senders should expect: `open`, `ask` (allowed on the TV) or `pin` (a code to type). */
        fun pairMode(settings: ReceiverSettings) = when (settings.access) {
            ReceiverSettings.ACCESS_CONFIRM -> "ask"
            ReceiverSettings.ACCESS_PIN, ReceiverSettings.ACCESS_PASSWORD -> "pin"
            else -> "open"
        }
    }
}
