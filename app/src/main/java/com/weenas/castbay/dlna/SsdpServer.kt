package com.weenas.castbay.dlna

import com.weenas.castbay.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketException

/**
 * Makes the renderer discoverable: answers M-SEARCH requests on the SSDP multicast group and
 * announces it with NOTIFY ssdp:alive (repeated) and ssdp:byebye on stop.
 *
 * TVs often run their own DLNA service on port 1900. Sockets that all allow address reuse
 * share it; if one doesn't, [start] still announces the renderer, but searches go unanswered
 * and control points only find it from the periodic announcements.
 */
class SsdpServer(private val uuid: String, private val httpPort: () -> Int) {
    @Volatile private var running = false
    private var listener: MulticastSocket? = null
    private var sender: MulticastSocket? = null

    /**
     * A second receiver answering through this socket.
     *
     * Two sockets bound to 1900 with address reuse are legal, but a search is delivered to only
     * one of them. The loser hears nothing but multicast, so every search it misses makes the
     * other device look offline; sharing one socket removes the coin toss.
     */
    class Guest(
        val port: () -> Int,
        val path: String,
        val targets: () -> List<Pair<String, String>>
    )

    private val guests = java.util.concurrent.CopyOnWriteArrayList<Guest>()

    /** Registers another receiver; add it before [start] so the first announcement carries it. */
    fun addGuest(guest: Guest) {
        if (guests.none { it === guest }) guests.add(guest)
    }

    /** Takes a guest back out, for a receiver that stops while this server keeps running. */
    fun removeGuest(guest: Guest) {
        guests.removeAll { it === guest }
    }

    /** Whether M-SEARCH requests can be received (port 1900 could be bound). */
    var answersSearches = false
        private set

    fun start() {
        running = true
        sender = MulticastSocket().apply { timeToLive = 4 }
        answersSearches = try {
            val socket = MulticastSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(Ssdp.PORT))
            }
            val group = InetSocketAddress(InetAddress.getByName(Ssdp.ADDRESS), Ssdp.PORT)
            interfaces().forEach { (network, _) ->
                runCatching { socket.joinGroup(group, network) }
                    .onFailure { Log.w(TAG, "Could not join SSDP group on ${network.name}", it) }
            }
            listener = socket
            Thread({ listen(socket) }, "DLNA-ssdp").apply { isDaemon = true }.start()
            true
        } catch (error: SocketException) {
            Log.w(TAG, "SSDP port ${Ssdp.PORT} is taken by another app; relying on announcements only", error)
            false
        }
        Log.i(TAG, "SSDP started (answers searches: $answersSearches)")
        Thread({ announceLoop() }, "DLNA-ssdp-notify").apply { isDaemon = true }.start()
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { announce { target, _ -> Ssdp.byeBye(target) } }
        runCatching { listener?.close() }
        runCatching { sender?.close() }
        listener = null
        sender = null
    }

    private fun listen(socket: MulticastSocket) {
        val buffer = ByteArray(4096)
        while (running) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (error: Exception) {
                break
            }
            val search = Ssdp.parseSearch(String(packet.data, 0, packet.length, Charsets.UTF_8)) ?: continue
            val to = InetSocketAddress(packet.address, packet.port)
            devices().forEach { device ->
                val targets = matching(search.searchTarget, device.targets())
                if (targets.isEmpty()) return@forEach
                Log.d(TAG, "M-SEARCH ${search.searchTarget} from ${packet.address.hostAddress}")
                reply(device, to, targets)
            }
        }
    }

    /** This renderer, plus every guest that answers on the same socket. */
    private fun devices(): List<Guest> =
        listOf(Guest({ httpPort() }, UpnpDescriptions.DESCRIPTION_PATH) { Ssdp.targets(uuid) }) + guests

    private fun matching(searchTarget: String, all: List<Pair<String, String>>): List<Pair<String, String>> =
        if (searchTarget.equals("ssdp:all", ignoreCase = true)) {
            all
        } else {
            all.filter { it.first.equals(searchTarget, ignoreCase = true) }
        }

    private fun reply(device: Guest, to: InetSocketAddress, targets: List<Pair<String, String>>) {
        val local = localAddressFor(to.address) ?: return
        val location = location(device, local)
        runCatching {
            DatagramSocket().use { socket ->
                targets.forEach { target ->
                    val bytes = Ssdp.searchResponse(target, location).toByteArray(Charsets.UTF_8)
                    socket.send(DatagramPacket(bytes, bytes.size, to))
                }
            }
        }.onFailure { Log.w(TAG, "Could not answer M-SEARCH from ${to.address.hostAddress}", it) }
    }

    private fun announceLoop() {
        var sent = 0
        while (running) {
            runCatching { announce { target, location -> Ssdp.alive(target, location) } }
                .onFailure { Log.w(TAG, "SSDP announcement failed", it) }
            sent++
            // A quick burst so control points already searching see us, then a steady pace.
            Thread.sleep(if (sent < 3) 1000L else ANNOUNCE_INTERVAL_MS)
        }
    }

    private fun announce(message: (Pair<String, String>, String) -> String) {
        val socket = sender ?: return
        val group = InetSocketAddress(InetAddress.getByName(Ssdp.ADDRESS), Ssdp.PORT)
        interfaces().forEach { (network, address) ->
            socket.networkInterface = network
            devices().forEach { device ->
                val location = location(device, address)
                device.targets().forEach { target ->
                    val bytes = message(target, location).toByteArray(Charsets.UTF_8)
                    socket.send(DatagramPacket(bytes, bytes.size, group))
                }
            }
        }
    }

    private fun location(device: Guest, address: InetAddress) =
        "http://${address.hostAddress}:${device.port()}${device.path}"

    /** Our address on the network that reaches [remote]. */
    private fun localAddressFor(remote: InetAddress): InetAddress? = runCatching {
        DatagramSocket().use { socket ->
            socket.connect(remote, Ssdp.PORT)
            socket.localAddress.takeUnless { it.isAnyLocalAddress }
        }
    }.getOrNull()

    /** Up, multicast-capable interfaces with an IPv4 address (SSDP here is IPv4 only). */
    private fun interfaces(): List<Pair<NetworkInterface, InetAddress>> =
        runCatching { NetworkInterface.getNetworkInterfaces().toList() }.getOrDefault(emptyList())
            .filter { runCatching { it.isUp && !it.isLoopback && it.supportsMulticast() }.getOrDefault(false) }
            .mapNotNull { network ->
                network.inetAddresses.toList().firstOrNull { it is Inet4Address }?.let { network to it }
            }

    private companion object {
        const val TAG = "CastBayDlna"
        const val ANNOUNCE_INTERVAL_MS = 60_000L
    }
}
