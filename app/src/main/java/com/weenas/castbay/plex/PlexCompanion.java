package com.weenas.castbay.plex;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Plex Companion receiver, probe build.
 *
 * <p>Plex controllers (the official app on a phone, Plex Web) talk to a player over plain HTTP once
 * GDM has advertised it: they push {@code /player/playback/playMedia} with a server address and an
 * access token, then drive it with {@code play}/{@code pause}/{@code seekTo}, and expect the player
 * to report progress by POSTing {@code /:/timeline} back to a port the controller subscribes with.
 *
 * <p>This build answers the whole command surface but deliberately does <em>not</em> decode video
 * yet. Every command is recorded through [com.weenas.castbay.util.Log] so the real wire format can
 * be confirmed on the television before the playback path is written against guesses.
 *
 * <p>Written against Android 6.0 (API 23): no {@code java.util.function}, no {@code Map.getOrDefault}
 * / {@code merge}, no records — this app's lint fails the build on anything newer than minSdk.
 */
public final class PlexCompanion implements AutoCloseable {
    public static final int DEFAULT_PORT = 32500;
    /** Reported to controllers as our version, and shown in Plex's device list. */
    public static final String VERSION = "probe-1";

    private final ServerSocket socket;
    private final String uuid, name;
    private final PlexLog log;
    private final ExecutorService workers = Executors.newFixedThreadPool(3, daemon("Plex-http"));
    private final ExecutorService outbound = Executors.newSingleThreadExecutor(daemon("Plex-timeline"));
    private final Map<String, Subscriber> subscribers = new ConcurrentHashMap<String, Subscriber>();
    private volatile boolean closed;
    private volatile int lastCommandId;

    public PlexCompanion(int preferredPort, String uuid, String name, PlexLog log) throws IOException {
        this.uuid = uuid; this.name = name; this.log = log;
        ServerSocket bound = null; IOException failure = null;
        for (int candidate = preferredPort; candidate < preferredPort + 10 && bound == null; candidate++) {
            ServerSocket attempt = new ServerSocket();
            try {
                attempt.setReuseAddress(true);
                attempt.bind(new InetSocketAddress(candidate), 16);
                bound = attempt;
            } catch (IOException busy) {
                failure = busy;
                try { attempt.close(); } catch (IOException ignored) { }
            }
        }
        if (bound == null) throw failure == null ? new IOException("no free port") : failure;
        socket = bound;
    }

    public int port() { return socket.getLocalPort(); }

    public void start() {
        Thread acceptor = new Thread(new Runnable() { public void run() { acceptLoop(); } }, "Plex-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                final Socket client = socket.accept();
                try { workers.execute(new Runnable() { public void run() { handle(client); } }); }
                catch (RejectedExecutionException busy) { client.close(); }
            } catch (IOException e) {
                if (!closed) log.write("[Plex] 接口服务接收异常：" + e.getClass().getSimpleName());
            }
        }
    }

    private void handle(Socket client) {
        try {
            client.setSoTimeout(6000);
            Request r = read(client);
            if (r == null) { send(client, 400, "Bad Request", "text/plain", ""); return; }
            String target = header(r, "x-plex-target-client-identifier");
            if (target != null && !target.equals(uuid)) {
                log.write("[Plex] 忽略发给其他播放器的命令：" + r.path);
                send(client, 404, "Not Found", "text/plain", "");
                return;
            }
            route(client, r);
        } catch (Exception ignored) {
            // The controller may close the socket mid-command; nothing to recover.
        } finally {
            try { client.close(); } catch (IOException ignored) { }
        }
    }

    private void route(Socket client, Request r) throws IOException {
        String path = r.path;
        Map<String, String> query = query(r.query);
        String commandId = query.get("commandID");
        if (commandId != null) {
            try { lastCommandId = Integer.parseInt(commandId); } catch (NumberFormatException ignored) { }
        }
        if (path.equals("/resources")) {
            log.write("[Plex] 控制器读取设备信息（/resources）");
            send(client, 200, "OK", "text/xml; charset=utf-8", resources());
            return;
        }
        if (path.equals("/player/timeline/subscribe")) {
            subscribe(r, query);
            send(client, 200, "OK", "text/plain", "");
            publish("stopped", 0, 0);
            return;
        }
        if (path.equals("/player/timeline/unsubscribe")) {
            subscribers.remove(subscriberKey(r.peer));
            log.write("[Plex] 控制器取消订阅");
            send(client, 200, "OK", "text/plain", "");
            return;
        }
        if (path.equals("/player/timeline/poll")) {
            send(client, 200, "OK", "text/xml; charset=utf-8", timeline("stopped", 0, 0));
            return;
        }
        if (path.startsWith("/player/playback/")) {
            String command = path.substring("/player/playback/".length());
            record(command, r, query);
            if (command.equals("playMedia")) publish("playing", 0, 0);
            if (command.equals("stop")) publish("stopped", 0, 0);
            send(client, 200, "OK", "text/plain", "");
            return;
        }
        if (path.startsWith("/player/navigation/") || path.startsWith("/player/mirror/") || path.startsWith("/player/application/")) {
            log.write("[Plex] 收到 " + path.substring(1) + tail(r, query));
            send(client, 200, "OK", "text/plain", "");
            return;
        }
        if (path.equals("/") || path.isEmpty()) {
            send(client, 200, "OK", "text/html; charset=utf-8",
                    "<!doctype html><meta charset='utf-8'><title>CastBay · Plex</title>"
                            + "<h1>Plex 接收端（探测版）</h1><p>设备名 " + escape(name) + "，端口 " + port() + "</p>"
                            + "<p>在手机 Plex App 的“播放到设备”中选择本设备即可，收到的每条命令都会记录在日志里。</p>");
            return;
        }
        // Unknown paths are worth seeing during probing: they are the commands this build missed.
        log.write("[Plex] 未识别请求 " + r.method + " " + path + tail(r, query));
        send(client, 200, "OK", "text/plain", "");
    }

    private void subscribe(Request r, Map<String, String> query) {
        int port;
        try { port = Integer.parseInt(value(query, "port", "3005")); }
        catch (NumberFormatException e) { port = 3005; }
        if (port < 1 || port > 65535) port = 3005;
        String key = subscriberKey(r.peer);
        subscribers.put(key, new Subscriber(key, r.peer, port, header(r, "x-plex-client-identifier")));
        String protocol = value(query, "protocol", "http");
        log.write("[Plex] 控制器已订阅状态，回报到 " + r.peer.getHostAddress() + ":" + port + "（" + protocol + "）");
    }

    /** Records a playback command. The token is masked: it grants access to the whole Plex server. */
    private void record(String command, Request r, Map<String, String> query) {
        StringBuilder line = new StringBuilder("[Plex] 收到 ").append(command);
        if (command.equals("playMedia")) {
            line.append("  key=").append(shorten(query.get("key"), 120));
            line.append("  服务器=").append(shorten(query.get("address"), 40)).append(':').append(value(query, "port", "?"));
            line.append("  machine=").append(shorten(query.get("machineIdentifier"), 12));
            line.append("  token=").append(mask(query.get("token")));
            line.append("  container=").append(shorten(query.get("containerKey"), 120));
            if (query.containsKey("offset")) line.append("  offset=").append(query.get("offset"));
        } else {
            if (query.containsKey("type")) line.append("  type=").append(query.get("type"));
            if (query.containsKey("offset")) line.append("  offset=").append(query.get("offset"));
            if (query.containsKey("volume")) line.append("  volume=").append(query.get("volume"));
            if (query.containsKey("key")) line.append("  key=").append(shorten(query.get("key"), 80));
        }
        if (query.containsKey("commandID")) line.append("  cmd=").append(query.get("commandID"));
        String controller = header(r, "x-plex-client-identifier");
        line.append("  来自=").append(r.peer.getHostAddress());
        if (controller != null) line.append(" （控制器 ").append(shorten(controller, 24)).append("）");
        log.write(line.toString());
    }

    private void publish(String state, long time, long duration) {
        if (subscribers.isEmpty()) {
            log.write("[Plex] 无控制器订阅，跳过状态回报：" + state);
            return;
        }
        final String xml = timeline(state, time, duration);
        for (final Subscriber s : subscribers.values()) {
            try { outbound.execute(new Runnable() { public void run() { post(s, xml); } }); }
            catch (RejectedExecutionException ignored) { }
        }
    }

    private void post(Subscriber s, String xml) {
        byte[] body = xml.getBytes(StandardCharsets.UTF_8);
        Socket connection = new Socket();
        try {
            connection.connect(new InetSocketAddress(s.address, s.port), 2000);
            connection.setSoTimeout(2000);
            String head = "POST /:/timeline HTTP/1.1\r\n"
                    + "HOST: " + s.address.getHostAddress() + ":" + s.port + "\r\n"
                    + "CONTENT-TYPE: text/xml; charset=utf-8\r\n"
                    + "X-Plex-Client-Identifier: " + uuid + "\r\n"
                    + "CONTENT-LENGTH: " + body.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            OutputStream out = connection.getOutputStream();
            out.write(head.getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            out.flush();
            connection.getInputStream().read();
        } catch (IOException e) {
            log.write("[Plex] 状态回报失败：" + e.getClass().getSimpleName());
        } finally {
            try { connection.close(); } catch (IOException ignored) { }
        }
    }

    private String timeline(String state, long time, long duration) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<MediaContainer location=\"fullScreenVideo\" commandID=\"" + lastCommandId + "\" size=\"1\">\n"
                + "<Timeline type=\"video\" state=\"" + state + "\" time=\"" + time + "\" duration=\"" + duration + "\""
                + " controllable=\"playPause,stop,seekTo,stepBack,stepForward\" volume=\"100\"/>\n"
                + "</MediaContainer>";
    }

    private String resources() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<MediaContainer size=\"1\">\n"
                + "<Player title=\"" + escape(name) + "\" machineIdentifier=\"" + escape(uuid) + "\""
                + " product=\"CastBay\" version=\"" + escape(VERSION) + "\""
                + " platform=\"Android\" deviceClass=\"stb\" protocolVersion=\"1\""
                + " protocolCapabilities=\"timeline,playback,playqueues\"/>\n"
                + "</MediaContainer>";
    }

    private static String tail(Request r, Map<String, String> query) {
        if (query.isEmpty()) return "";
        StringBuilder text = new StringBuilder("  ");
        for (Map.Entry<String, String> entry : query.entrySet()) {
            text.append(entry.getKey()).append('=').append(shorten(entry.getValue(), 60)).append(' ');
        }
        return text.toString();
    }

    private static String value(Map<String, String> query, String key, String fallback) {
        String found = query.get(key);
        return found == null || found.isEmpty() ? fallback : found;
    }

    private static String mask(String value) {
        if (value == null || value.isEmpty()) return "(未提供)";
        return value.length() <= 6 ? "******" : value.substring(0, 6) + "…(共 " + value.length() + " 位)";
    }

    private static String shorten(String value, int limit) {
        if (value == null) return "-";
        return value.length() <= limit ? value : value.substring(0, limit) + "…";
    }

    private static String subscriberKey(InetAddress peer) { return peer.getHostAddress(); }

    private static String header(Request r, String name) { return r.headers.get(name); }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        if (raw == null || raw.isEmpty()) return values;
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) continue;
            int equals = pair.indexOf('=');
            String key = equals < 0 ? pair : pair.substring(0, equals);
            String value = equals < 0 ? "" : pair.substring(equals + 1);
            values.put(urlDecode(key), urlDecode(value));
        }
        return values;
    }

    private static String urlDecode(String value) {
        try { return java.net.URLDecoder.decode(value, "UTF-8"); }
        catch (Exception e) { return value; }
    }

    private static Request read(Socket client) throws IOException {
        InputStream input = new BufferedInputStream(client.getInputStream());
        String start = line(input, 8192);
        if (start == null) return null;
        String[] pieces = start.split(" ");
        if (pieces.length != 3 || !pieces[1].startsWith("/") || !pieces[2].startsWith("HTTP/1.")) return null;
        String[] target = pieces[1].split("\\?", 2);
        Map<String, String> headers = new LinkedHashMap<String, String>();
        int total = start.length();
        for (String h; (h = line(input, 8192)) != null && !h.isEmpty(); ) {
            total += h.length();
            if (total > 16384) return null;
            int colon = h.indexOf(':');
            if (colon <= 0) return null;
            String key = h.substring(0, colon).trim().toLowerCase(Locale.US);
            if (headers.containsKey(key)) return null;
            headers.put(key, h.substring(colon + 1).trim());
        }
        if (headers.containsKey("transfer-encoding")) return null;
        int length;
        try { length = Integer.parseInt(value(headers, "content-length", "0")); }
        catch (NumberFormatException e) { return null; }
        if (length < 0 || length > 131072) return null;
        byte[] bytes = new byte[length];
        int offset = 0;
        while (offset < length) {
            int n = input.read(bytes, offset, length - offset);
            if (n < 0) throw new EOFException();
            offset += n;
        }
        return new Request(pieces[0], target[0], target.length > 1 ? target[1] : "", headers,
                new String(bytes, StandardCharsets.UTF_8), client.getInetAddress());
    }

    private static String line(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        for (int c; (c = input.read()) != -1; ) {
            if (c == '\n') return new String(buffer.toByteArray(), StandardCharsets.US_ASCII).replace("\r", "");
            if (buffer.size() >= limit) throw new IOException();
            buffer.write(c);
        }
        return null;
    }

    private static void send(Socket socket, int code, String reason, String mime, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + code + " " + reason + "\r\n"
                + "CONTENT-TYPE: " + mime + "\r\n"
                + "CONTENT-LENGTH: " + bytes.length + "\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream out = socket.getOutputStream();
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.flush();
    }

    private static ThreadFactory daemon(final String name) {
        return new ThreadFactory() {
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, name);
                thread.setDaemon(true);
                return thread;
            }
        };
    }

    @Override public void close() {
        closed = true;
        try { socket.close(); } catch (IOException ignored) { }
        workers.shutdownNow();
        outbound.shutdownNow();
        try { outbound.awaitTermination(200, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) { }
        subscribers.clear();
    }

    private static final class Request {
        final String method, path, query, body;
        final Map<String, String> headers;
        final InetAddress peer;

        Request(String method, String path, String query, Map<String, String> headers, String body, InetAddress peer) {
            this.method = method; this.path = path; this.query = query;
            this.headers = headers; this.body = body; this.peer = peer;
        }
    }

    private static final class Subscriber {
        final String key, identifier;
        final InetAddress address;
        final int port;

        Subscriber(String key, InetAddress address, int port, String identifier) {
            this.key = key; this.address = address; this.port = port; this.identifier = identifier;
        }
    }
}
