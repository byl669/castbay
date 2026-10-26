package com.weenas.castbay.service

import android.content.Context
import android.net.wifi.WifiManager
import com.weenas.castbay.util.Diagnostics
import com.weenas.castbay.util.Log
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.NetworkInterface

/**
 * Announces the receiver's DNS-SD services by itself, for the TV boxes whose NsdManager answers
 * "internal error" or never answers (#214, #239). The advertiser starts it only after Android's
 * own registration failed, so the two never announce the same names together.
 */
class MdnsResponder(context: Context) {
    private val appContext = context.applicationContext
    @Volatile private var socket: MulticastSocket? = null
    private var lock: WifiManager.MulticastLock? = null
    private var thread: Thread? = null
    private var zone: MdnsZone? = null

    val running: Boolean get() = socket != null

    /** Starts answering for [services]; false when no multicast socket could be opened. */
    @Synchronized
    fun start(host: String, services: List<MdnsService>): Boolean {
        if (socket != null) return true
        return try {
            val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            lock = wifi.createMulticastLock("CastBay-mDNS-own").apply { setReferenceCounted(false); acquire() }
            val s = MulticastSocket(null).apply {
                reuseAddress = true
                bind(java.net.InetSocketAddress(PORT))
                timeToLive = 255
                soTimeout = 0
            }
            val joined = interfaces().count { nic ->
                runCatching { s.joinGroup(java.net.InetSocketAddress(GROUP, PORT), nic); true }.getOrDefault(false)
            }
            if (joined == 0) {
                s.close()
                throw java.io.IOException("no network interface to announce on")
            }
            socket = s
            val z = MdnsZone(host, { addresses() }, services)
            zone = z
            thread = Thread({ listen(s, z) }, "CastBay-mDNS").apply { isDaemon = true; start() }
            Thread({ announce(s, z) }, "CastBay-mDNS-announce").apply { isDaemon = true; start() }
            Log.i(TAG, "Announcing ${services.joinToString { it.fullName }} by itself (no NsdManager)")
            Diagnostics.record("discovery", "Announcing by itself: ${services.joinToString { it.type }}")
            true
        } catch (error: Exception) {
            Log.e(TAG, "Could not start the own mDNS responder", error)
            Diagnostics.record("discovery", "Own mDNS responder failed: ${error.message}")
            stop()
            false
        }
    }

    @Synchronized
    fun stop() {
        val s = socket
        val z = zone
        // Tell the network the services are gone, so phones drop them from their list.
        if (s != null && z != null) runCatching { send(s, z.announcement(0)) }
        socket = null
        zone = null
        runCatching { s?.close() }
        thread = null
        lock?.let { if (it.isHeld) it.release() }
        lock = null
    }

    private fun announce(s: MulticastSocket, z: MdnsZone) {
        // Three times, as the standard says, so one lost packet does not hide the receiver.
        for (delayMs in longArrayOf(0, 1000, 2000)) {
            Thread.sleep(delayMs)
            if (socket !== s) return
            runCatching { send(s, z.announcement()) }
        }
    }

    private fun listen(s: MulticastSocket, z: MdnsZone) {
        val buffer = ByteArray(9000)
        var lastReply = 0L
        while (socket === s) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                s.receive(packet)
                val reply = z.answer(packet.data.copyOf(packet.length)) ?: continue
                // Many hosts ask at once; one answer per 100 ms is enough for all of them.
                val now = System.nanoTime() / 1_000_000
                if (now - lastReply < 100) continue
                lastReply = now
                send(s, reply)
            } catch (error: Exception) {
                if (socket !== s) return
                Log.w(TAG, "mDNS receive: ${error.message}")
            }
        }
    }

    /** Multicasts [data] on every interface that is up (usually one). */
    private fun send(s: MulticastSocket, data: ByteArray) {
        val packet = DatagramPacket(data, data.size, GROUP, PORT)
        for (nic in interfaces()) {
            runCatching {
                s.networkInterface = nic
                s.send(packet)
            }
        }
    }

    private fun interfaces(): List<NetworkInterface> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter {
            it.isUp && !it.isLoopback && it.supportsMulticast() && !it.isPointToPoint &&
                it.inetAddresses.toList().any { a -> a is Inet4Address && !a.isLinkLocalAddress }
        }
    }.getOrDefault(emptyList())

    private fun addresses(): List<ByteArray> =
        interfaces().flatMap { nic -> nic.inetAddresses.toList().filterIsInstance<Inet4Address>().filter { !it.isLinkLocalAddress } }
            .map { it.address }

    private companion object {
        const val TAG = "MdnsResponder"
        const val PORT = 5353
        val GROUP: InetAddress = InetAddress.getByName("224.0.0.251")
    }
}
