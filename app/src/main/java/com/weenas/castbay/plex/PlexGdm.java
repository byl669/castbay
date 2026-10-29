package com.weenas.castbay.plex;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Advertises the receiver as a Plex player through GDM ("Good Day Mate"), Plex's own discovery
 * protocol. It is not DLNA and not SSDP: the Plex app scans by broadcasting
 * {@code M-SEARCH * HTTP/1.0} to UDP 32412 and expects an HTTP-like header block back, while
 * players also announce themselves with a periodic {@code HELLO} to the 239.0.0.250 group.
 *
 * <p>Like [com.weenas.castbay.dlna.SsdpServer] this binds the wildcard address and joins the group
 * on every IPv4 interface, because a television can be on Wi-Fi and Ethernet at once and only one
 * of them reaches the phone. Nothing is authenticated: any host on the same subnet can address the
 * player port, exactly as with the DLNA renderer.
 */
public final class PlexGdm implements AutoCloseable {
    public static final int PORT = 32412;
    private static final String GROUP = "239.0.0.250";
    private static final long ANNOUNCE_INTERVAL_MS = 5000;

    private final MulticastSocket socket;
    private final InetAddress group;
    private final String headers;
    private final PlexLog log;
    /** Discovery frames are bursty; log each peer once and then only every tenth repeat. */
    private final Map<String, Integer> seen = new ConcurrentHashMap<String, Integer>();
    private volatile boolean closed;
    private Thread listener, announcer;

    public PlexGdm(int httpPort, String name, String uuid, String version, PlexLog log) throws IOException {
        this.log = log;
        this.group = InetAddress.getByName(GROUP);
        this.headers = "Content-Type: plex/media-player\r\n"
                + "Name: " + name + "\r\n"
                + "Port: " + httpPort + "\r\n"
                + "Product: CastBay\r\n"
                + "Protocol: plex\r\n"
                + "Protocol-Version: 1\r\n"
                + "Protocol-Capabilities: timeline,playback,playqueues\r\n"
                + "Resource-Identifier: " + uuid + "\r\n"
                + "Version: " + version + "\r\n"
                + "\r\n";
        MulticastSocket created = new MulticastSocket(null);
        try {
            created.setReuseAddress(true);
            created.bind(new InetSocketAddress(PORT));
            created.setTimeToLive(4);
            created.setBroadcast(true);
            created.setLoopbackMode(true);
            joinEveryInterface(created, group);
        } catch (IOException e) {
            try { created.close(); } catch (RuntimeException ignored) { }
            throw e;
        }
        socket = created;
    }

    private static void joinEveryInterface(MulticastSocket socket, InetAddress group) {
        try {
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback()) continue;
                for (InterfaceAddress address : network.getInterfaceAddresses()) {
                    if (!(address.getAddress() instanceof Inet4Address)) continue;
                    try { socket.joinGroup(new InetSocketAddress(group, PORT), network); }
                    catch (IOException ignored) { }
                }
            }
        } catch (SocketException ignored) {
            // No interface list: announcements still go out over the default route.
        }
    }

    public void start() {
        announcer = new Thread(new Runnable() { public void run() { announceLoop(); } }, "Plex-gdm-hello");
        announcer.setDaemon(true);
        announcer.start();
        listener = new Thread(new Runnable() { public void run() { listenLoop(); } }, "Plex-gdm-listen");
        listener.setDaemon(true);
        listener.start();
        log.write("[Plex] 发现服务已启动：监听 UDP " + PORT + "，等待 Plex App 搜索");
    }

    private void listenLoop() {
        byte[] buffer = new byte[2048];
        while (!closed) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                if (!firstLine(packet).startsWith("M-SEARCH")) continue;
                note(packet.getAddress());
                byte[] reply = ("HTTP/1.1 200 OK\r\n" + headers).getBytes(StandardCharsets.UTF_8);
                socket.send(new DatagramPacket(reply, reply.length, packet.getAddress(), packet.getPort()));
            } catch (IOException e) {
                if (!closed) log.write("[Plex] 发现服务接收异常：" + e.getClass().getSimpleName());
            } catch (RuntimeException ignored) {
                // A malformed datagram must not end discovery.
            }
        }
    }

    /** Group and broadcast: Plex controllers differ in which of the two they listen on. */
    private void announceLoop() {
        byte[] hello = ("HELLO * HTTP/1.0\r\n" + headers).getBytes(StandardCharsets.UTF_8);
        while (!closed) {
            send(hello, group);
            try { send(hello, InetAddress.getByName("255.255.255.255")); }
            catch (IOException ignored) { }
            try { Thread.sleep(ANNOUNCE_INTERVAL_MS); } catch (InterruptedException stop) { return; }
        }
    }

    private void send(byte[] hello, InetAddress target) {
        try {
            socket.send(new DatagramPacket(hello, hello.length, target, PORT));
        } catch (IOException e) {
            if (!closed) log.write("[Plex] 广播公告失败：" + e.getClass().getSimpleName());
        }
    }

    private void note(InetAddress peer) {
        String key = peer.getHostAddress();
        Integer previous = seen.get(key);
        int count = previous == null ? 1 : previous.intValue() + 1;
        seen.put(key, Integer.valueOf(count));
        if (count == 1 || count % 10 == 0) {
            log.write("[Plex] Plex App 正在搜索设备，已应答：" + key + (count == 1 ? "" : "，第 " + count + " 次"));
        }
    }

    private static String firstLine(DatagramPacket packet) {
        String text = new String(packet.getData(), packet.getOffset(), Math.min(packet.getLength(), 256), StandardCharsets.UTF_8);
        int end = text.indexOf('\r');
        if (end < 0) end = text.indexOf('\n');
        return end < 0 ? text.trim() : text.substring(0, end).trim();
    }

    @Override public void close() {
        closed = true;
        if (announcer != null) announcer.interrupt();
        try { socket.close(); } catch (RuntimeException ignored) { }
        seen.clear();
    }
}
