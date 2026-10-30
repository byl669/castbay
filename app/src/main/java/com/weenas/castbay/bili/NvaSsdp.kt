package com.weenas.castbay.bili

import com.weenas.castbay.dlna.Ssdp
import com.weenas.castbay.dlna.SsdpServer
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
 * Makes the Bilibili receiver discoverable. The phone finds cast targets with an SSDP search for
 * [Nva.SERVICE_TYPE]; a device answering it is listed as one of Bilibili's own boxes, which is
 * what unlocks 1080p, playback speed and danmaku on the sender's side.
 *
 * It runs its own sockets rather than sharing the DLNA renderer's: the address in each answer is
 * this server (port 9958), not the DLNA one, and a failure here must leave DLNA untouched.
 */
class NvaSsdp(private val uuid: String, private val port: () -> Int) {
    @Volatile private var running = false
    private var listener: MulticastSocket? = null
    private var sender: MulticastSocket? = null

    /** Set while answering through a renderer's socket rather than one of our own. */
    private var hostServer: SsdpServer? = null
    private var guest: SsdpServer.Guest? = null

    /**
     * Whether searches can be received; if not, the periodic announcements still advertise us.
     *
     * When [host] is given this receiver joins that renderer's socket instead of binding a second
     * one on 1900: two sockets there split the unicast searches, and whichever loses a packet
     * makes this device show up as offline in the phone's list. The host also announces us.
     */
    var answersSearches = false
        private set

    fun start(host: SsdpServer? = null) {
        running = true
        if (host != null) {
            val shared = SsdpServer.Guest({ port() }, Nva.DESCRIPTION_PATH) { Nva.targets(uuid) }
            host.addGuest(shared)
            hostServer = host
            guest = shared
            answersSearches = true
            Log.i(TAG, "Bilibili discovery shares the DLNA socket on ${Ssdp.PORT}")
            return
        }
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
            Thread({ listen(socket) }, "Bili-ssdp").apply { isDaemon = true }.start()
            true
        } catch (error: SocketException) {
            Log.w(TAG, "SSDP port ${Ssdp.PORT} is held without address reuse; announcing only", error)
            false
        }
        Log.i(TAG, "Bilibili discovery started (answers searches: $answersSearches)")
        Thread({ announceLoop() }, "Bili-ssdp-notify").apply { isDaemon = true }.start()
    }

    fun stop() {
        if (!running) return
        running = false
        guest?.let { shared -> hostServer?.removeGuest(shared) }
        guest = null
        hostServer = null
        runCatching { announce { target, location -> Ssdp.byeBye(target) } }
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
            // Only an exact match: answering "ssdp:all" would also list this device twice, once
            // per description address, for every DLNA control point on the network.
            val targets = Nva.targets(uuid).filter { it.first.equals(search.searchTarget, ignoreCase = true) }
            if (targets.isEmpty()) continue
            val to = InetSocketAddress(packet.address, packet.port)
            Log.i(TAG, "Bilibili search '${search.searchTarget}' from ${packet.address.hostAddress}")
            reply(to, targets)
        }
    }

    private fun reply(to: InetSocketAddress, targets: List<Pair<String, String>>) {
        val local = localAddressFor(to.address) ?: return
        val location = location(local)
        runCatching {
            DatagramSocket().use { socket ->
                targets.forEach { target ->
                    val bytes = Ssdp.searchResponse(target, location).toByteArray(Charsets.UTF_8)
                    socket.send(DatagramPacket(bytes, bytes.size, to))
                }
            }
        }.onFailure { Log.w(TAG, "Could not answer Bilibili search from ${to.address.hostAddress}", it) }
    }

    private fun announceLoop() {
        var sent = 0
        while (running) {
            runCatching { announce { target, location -> Ssdp.alive(target, location) } }
                .onFailure { Log.w(TAG, "Bilibili announcement failed", it) }
            sent++
            Thread.sleep(if (sent < 3) 1_000L else ANNOUNCE_INTERVAL_MS)
        }
    }

    private fun announce(message: (Pair<String, String>, String) -> String) {
        val socket = sender ?: return
        val group = InetSocketAddress(InetAddress.getByName(Ssdp.ADDRESS), Ssdp.PORT)
        interfaces().forEach { (network, address) ->
            socket.networkInterface = network
            Nva.targets(uuid).forEach { target ->
                val bytes = message(target, location(address)).toByteArray(Charsets.UTF_8)
                socket.send(DatagramPacket(bytes, bytes.size, group))
            }
        }
    }

    private fun location(address: InetAddress) = "http://${address.hostAddress}:${port()}${Nva.DESCRIPTION_PATH}"

    /** Our address on the network that reaches [remote]: the one the phone must connect back to. */
    private fun localAddressFor(remote: InetAddress): InetAddress? = runCatching {
        DatagramSocket().use { socket ->
            socket.connect(remote, Ssdp.PORT)
            socket.localAddress.takeUnless { it.isAnyLocalAddress }
        }
    }.getOrNull()

    private fun interfaces(): List<Pair<NetworkInterface, InetAddress>> =
        runCatching { NetworkInterface.getNetworkInterfaces().toList() }.getOrDefault(emptyList())
            .filter { runCatching { it.isUp && !it.isLoopback && it.supportsMulticast() }.getOrDefault(false) }
            .mapNotNull { network ->
                network.inetAddresses.toList().firstOrNull { it is Inet4Address }?.let { network to it }
            }

    private companion object {
        const val TAG = "CastBayBili"
        /**
         * Phones rebuild their cast list from search replies, but a list already open only keeps
         * what it last heard, so the announcement is repeated often enough to undo a missed reply.
         */
        const val ANNOUNCE_INTERVAL_MS = 10_000L
    }
}
