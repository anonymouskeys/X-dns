package com.anonymouskeys.xdns;

import android.content.SharedPreferences;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class SocksDohBridge {

    public static final int PORT = 1081;

    private final ExecutorService clients =
            Executors.newCachedThreadPool();

    private final java.util.Set<Socket> sockets = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private String resolverOverride;
    private volatile boolean running;
    private ServerSocket server;
    private Thread acceptThread;
    private SharedPreferences prefs;

    public synchronized void start(
            SharedPreferences prefs
    ) throws Exception {

        start(prefs, null);
    }

    public synchronized void start(SharedPreferences prefs, String resolverOverride) throws Exception {
        if (running) return;
        this.resolverOverride = resolverOverride;
        this.prefs = prefs;

        server = new ServerSocket();

        server.setReuseAddress(true);

        server.bind(
                new InetSocketAddress(
                        "127.0.0.1",
                        PORT
                ),
                128
        );

        running = true;

        acceptThread =
                new Thread(
                        this::acceptLoop,
                        "xdns-doh-socks-bridge"
                );

        acceptThread.setDaemon(true);
        acceptThread.start();

        DnsLog.addRaw(
                "DOH BRIDGE • listening 127.0.0.1:"
                        + PORT
                        + " → ciadpi:"
                        + DragonByeDpi.PORT
        );
    }

    public synchronized void stop() {
        running = false;

        if (server != null) {
            try {
                server.close();
            } catch (Exception ignored) {
            }

            server = null;
        }

        if (acceptThread != null) {
            acceptThread.interrupt();
            acceptThread = null;
        }

        synchronized (sockets) {
            for (Socket socket : sockets) closeQuietly(socket);
            sockets.clear();
        }
        clients.shutdownNow();
        FastDoh.clearCache();
        InstagramRescue.clearCache();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = track(server.accept());

                client.setTcpNoDelay(true);

                clients.submit(
                        () -> handle(client)
                );

            } catch (Exception e) {
                if (running) {
                    DnsLog.addRaw(
                            "DOH BRIDGE • accept error • "
                                    + safeMessage(e)
                    );
                }
            }
        }
    }

    private void handle(Socket client) {
        Socket upstream = null;

        try {
            client.setSoTimeout(10_000);

            Request request =
                    readClientRequest(client);

            if (request.command != 0x01) {
                sendReply(client, 0x07);
                return;
            }

            List<String> candidates;

            if (request.addressType == 0x03) {
                candidates =
                        resolve(request.host);
            } else {
                candidates =
                        Collections.singletonList(
                                request.host
                        );
            }

            ensureRunning();
            String rememberedBefore =
                    RouteMemory.preferred(
                            prefs,
                            request.host,
                            request.port
                    );

            if (rememberedBefore == null
                    && request.addressType == 0x03
                    && request.port == 443
                    && InstagramRescue.isMetaHost(
                    request.host
            )) {
                candidates =
                        InstagramRescue.prependIpv6(
                                prefs,
                                request.host,
                                candidates
                        );
            }

            candidates =
                    RouteMemory.prioritize(
                            prefs,
                            request.host,
                            request.port,
                            candidates
                    );

            Exception last = null;
            String connectedIp = null;
            int rejected = 0;
            boolean domainRescue = false;

            if (request.addressType == 0x03
                    && request.port == 443
                    && RouteMemory.preferDomain(
                    request.host,
                    request.port
            )) {
                try {
                    upstream =
                            connectCiadpiDomain(
                                    request.host,
                                    request.port
                            );

                    connectedIp = "domain";
                    domainRescue = true;

                } catch (Exception e) {
                    last = e;

                    RouteMemory.domainFailure(
                            request.host,
                            request.port
                    );
                }
            }

            int maxTries =
                    Math.min(
                            8,
                            candidates.size()
                    );

            if (upstream == null) {
                for (int i = 0; i < maxTries; i++) {
                    String ip = candidates.get(i);

                    try {
                        upstream =
                                connectCiadpi(
                                        ip,
                                        request.port
                                );

                        connectedIp = ip;
                        break;

                    } catch (Exception e) {
                        ensureRunning();
                        last = e;
                        rejected++;

                        RouteMemory.failure(
                                prefs,
                                request.host,
                                request.port,
                                ip
                        );
                    }
                }
            }

            if (upstream == null
                    && request.addressType == 0x03) {

                ensureRunning();
                List<String> alternates =
                        FastDoh.resolveAlternateCandidates(
                                prefs,
                                request.host,
                                candidates
                        );

                alternates =
                        RouteMemory.prioritize(
                                prefs,
                                request.host,
                                request.port,
                                alternates
                        );

                if (!alternates.isEmpty()) {
                    DnsLog.addRaw(
                            "ALT DOH RESCUE • "
                                    + request.host
                                    + " • "
                                    + alternates.size()
                                    + " new IPs"
                    );
                }

                int altTries =
                        Math.min(
                                12,
                                alternates.size()
                        );

                for (int i = 0;
                     i < altTries
                             && upstream == null;
                     i++) {

                    String ip =
                            alternates.get(i);

                    try {
                        upstream =
                                connectCiadpi(
                                        ip,
                                        request.port
                                );

                        connectedIp = ip;
                        break;

                    } catch (Exception e) {
                        ensureRunning();
                        last = e;
                        rejected++;

                        RouteMemory.failure(
                                prefs,
                                request.host,
                                request.port,
                                ip
                        );
                    }
                }
            }

            if (upstream == null
                    && request.addressType == 0x03
                    && request.port == 443) {

                try {
                    upstream =
                            connectCiadpiDomain(
                                    request.host,
                                    request.port
                            );

                    connectedIp = "domain";
                    domainRescue = true;

                } catch (Exception e) {
                    last = e;

                    RouteMemory.domainFailure(
                            request.host,
                            request.port
                    );
                }
            }

            if (upstream == null
                    && request.port != 443) {

                for (int i = 0; i < maxTries; i++) {
                    String ip = candidates.get(i);

                    try {
                        upstream =
                                connectDirect(
                                        ip,
                                        request.port
                                );

                        connectedIp = ip;

                        DnsLog.addRaw(
                                "DIRECT FALLBACK • "
                                        + request.host
                                        + ":"
                                        + request.port
                                        + " → "
                                        + ip
                        );

                        break;

                    } catch (Exception e) {
                        last = e;
                    }
                }
            }

            if (upstream == null) {
                RouteMemory.routeFailed(
                        prefs,
                        request.host
                );

                FastDoh.invalidate(
                        request.host
                );

                if (RouteMemory.shouldLogFailure(
                        request.host,
                        request.port
                )) {
                    DnsLog.addRaw(
                            "ROUTE ✗ • "
                                    + request.host
                                    + ":"
                                    + request.port
                                    + " • "
                                    + rejected
                                    + " IPs rejected"
                                    + (last == null
                                    ? ""
                                    : " • " + safeMessage(last))
                    );
                }

                throw new IOException(
                        "route unavailable"
                );
            }

            ensureRunning();

            boolean verifyTlsRoute =
                    request.addressType == 0x03
                            && request.port == 443
                            && isTrackedHttpsHost(
                            request.host
                    );

            if (!verifyTlsRoute) {
                confirmRouteSuccess(
                        request,
                        connectedIp,
                        domainRescue,
                        rememberedBefore,
                        rejected
                );
            }

            sendReply(client, 0x00);

            client.setSoTimeout(0);

            if (!verifyTlsRoute) {
                upstream.setSoTimeout(0);
            }

            final Socket upstreamFinal = upstream;

            Future<?> uplink =
                    clients.submit(() ->
                            relay(
                                    client,
                                    upstreamFinal
                            )
                    );

            if (verifyTlsRoute) {
                relayHttpsAndConfirm(
                        upstreamFinal,
                        client,
                        request,
                        connectedIp,
                        domainRescue,
                        rememberedBefore,
                        rejected
                );
            } else {
                relay(
                        upstreamFinal,
                        client
                );
            }

            uplink.cancel(true);

        } catch (Exception e) {
            try {
                sendReply(client, 0x01);
            } catch (Exception ignored) {
            }

            String message = safeMessage(e);

            if (!"route unavailable".equals(message)
                    && !message.contains("Socket closed")
                    && !message.contains("Broken pipe")
                    && !message.contains("Connection reset")) {

                DnsLog.addRaw(
                        "DOH BRIDGE • "
                                + message
                );
            }

        } finally {
            sockets.remove(client);
            if (upstream != null) sockets.remove(upstream);
            closeQuietly(client);
            closeQuietly(upstream);
        }
    }

    private void confirmRouteSuccess(
            Request request,
            String connectedIp,
            boolean domainRescue,
            String rememberedBefore,
            int rejected
    ) {
        boolean changed = false;

        if (domainRescue) {
            RouteMemory.domainSuccess(
                    prefs,
                    request.host,
                    request.port
            );
        } else {
            changed =
                    RouteMemory.success(
                            prefs,
                            request.host,
                            request.port,
                            connectedIp
                    );
        }

        boolean memoryHit =
                !domainRescue
                        && rememberedBefore != null
                        && rememberedBefore.equals(
                                connectedIp
                        );

        if (domainRescue) {
            DnsLog.addRaw(
                    "DOMAIN RESCUE ✓ • "
                            + request.host
                            + ":"
                            + request.port
                            + " • TLS confirmed"
            );

        } else if (changed) {
            DnsLog.addRaw(
                    "ROUTE LEARN ✓ • "
                            + request.host
                            + ":"
                            + request.port
                            + " → "
                            + connectedIp
                            + (rejected > 0
                            ? " • "
                            + rejected
                            + " rejected first"
                            : "")
                            + (request.port == 443
                            ? " • TLS confirmed"
                            : "")
            );

        } else if (rejected > 0) {
            DnsLog.addRaw(
                    "ROUTE RECOVER ✓ • "
                            + request.host
                            + ":"
                            + request.port
                            + " → "
                            + connectedIp
                            + " • "
                            + rejected
                            + " rejected"
                            + (request.port == 443
                            ? " • TLS confirmed"
                            : "")
            );

        } else if (memoryHit
                && isTrackedHttpsHost(
                request.host
        )) {
            DnsLog.addRaw(
                    "ROUTE MEMORY ✓ • "
                            + request.host
                            + " → "
                            + connectedIp
                            + " • TLS confirmed"
            );
        }
    }

    private void relayHttpsAndConfirm(
            Socket upstream,
            Socket client,
            Request request,
            String connectedIp,
            boolean domainRescue,
            String rememberedBefore,
            int rejected
    ) {
        boolean confirmed = false;

        try {
            upstream.setSoTimeout(6_000);

            InputStream in =
                    upstream.getInputStream();

            OutputStream out =
                    client.getOutputStream();

            byte[] tlsHeader =
                    readExact(
                            in,
                            5
                    );

            if (!looksLikeTlsServerResponse(
                    tlsHeader
            )) {
                throw new IOException(
                        "TLS server response rejected"
                );
            }

            confirmRouteSuccess(
                    request,
                    connectedIp,
                    domainRescue,
                    rememberedBefore,
                    rejected
            );

            confirmed = true;

            out.write(tlsHeader);
            out.flush();

            upstream.setSoTimeout(0);

            relay(
                    upstream,
                    client
            );

        } catch (Exception e) {
            if (!confirmed) {
                if (domainRescue) {
                    RouteMemory.domainFailure(
                            request.host,
                            request.port
                    );
                } else if (connectedIp != null
                        && !"domain".equals(
                        connectedIp
                )) {
                    RouteMemory.failure(
                            prefs,
                            request.host,
                            request.port,
                            connectedIp
                    );
                }

                RouteMemory.routeFailed(
                        prefs,
                        request.host
                );

                FastDoh.invalidate(
                        request.host
                );

                if (RouteMemory.shouldLogFailure(
                        request.host,
                        request.port
                )) {
                    DnsLog.addRaw(
                            "ROUTE TLS ✗ • "
                                    + request.host
                                    + ":"
                                    + request.port
                                    + " • "
                                    + connectedIp
                                    + " • "
                                    + safeMessage(e)
                    );
                }
            }

        } finally {
            try {
                upstream.setSoTimeout(0);
            } catch (Exception ignored) {
            }
        }
    }

    private static boolean looksLikeTlsServerResponse(
            byte[] header
    ) {
        if (header == null
                || header.length < 5) {
            return false;
        }

        int type =
                header[0] & 0xff;

        int major =
                header[1] & 0xff;

        boolean recordType =
                type == 20
                        || type == 22
                        || type == 23;

        return recordType
                && major == 3;
    }

    private static boolean isTrackedHttpsHost(
            String host
    ) {
        if (host == null) return false;

        String h =
                host.toLowerCase(
                        java.util.Locale.ROOT
                );

        return h.contains("youtube")
                || h.endsWith("googlevideo.com")
                || h.endsWith("ytimg.com")
                || h.contains("instagram")
                || h.endsWith("facebook.com")
                || h.endsWith("fbcdn.net")
                || h.contains("tiktok")
                || h.contains("byteoversea")
                || h.contains("ibytedtos")
                || h.contains("musical.ly");
    }

    private void ensureRunning() throws IOException {
        if (!running || Thread.currentThread().isInterrupted()) throw new IOException("Bridge stopped");
    }

    private Socket track(Socket socket) throws IOException {
        synchronized (sockets) {
            if (!running) { socket.close(); throw new IOException("Bridge stopped"); }
            sockets.add(socket);
        }
        return socket;
    }

    private List<String> resolve(String host) throws Exception {
        if (resolverOverride == null) return FastDoh.resolveCandidates(prefs, host);
        DohClient.Result result = DohClient.query(resolverOverride, DohClient.makeTestQuery(host));
        if (!result.ok()) throw new IOException("Candidate DoH failed");
        return DnsPacket.allIpv4Addresses(result.body);
    }

    private Request readClientRequest(
            Socket socket
    ) throws Exception {

        InputStream in =
                socket.getInputStream();

        OutputStream out =
                socket.getOutputStream();

        int version = readU8(in);
        int methodCount = readU8(in);

        if (version != 0x05) {
            throw new IOException(
                    "SOCKS version "
                            + version
                            + " unsupported"
            );
        }

        boolean noAuth = false;

        for (int i = 0; i < methodCount; i++) {
            if (readU8(in) == 0x00) {
                noAuth = true;
            }
        }

        if (!noAuth) {
            out.write(
                    new byte[]{
                            0x05,
                            (byte) 0xff
                    }
            );
            out.flush();

            throw new IOException(
                    "SOCKS no-auth unavailable"
            );
        }

        out.write(
                new byte[]{
                        0x05,
                        0x00
                }
        );

        out.flush();

        int reqVersion = readU8(in);
        int command = readU8(in);
        readU8(in); // reserved
        int atyp = readU8(in);

        if (reqVersion != 0x05) {
            throw new IOException(
                    "Invalid SOCKS request"
            );
        }

        String host;

        if (atyp == 0x01) {
            host =
                    InetAddress.getByAddress(
                            readExact(in, 4)
                    ).getHostAddress();

        } else if (atyp == 0x04) {
            host =
                    InetAddress.getByAddress(
                            readExact(in, 16)
                    ).getHostAddress();

        } else if (atyp == 0x03) {
            int len = readU8(in);

            host =
                    new String(
                            readExact(in, len),
                            StandardCharsets.US_ASCII
                    );

        } else {
            throw new IOException(
                    "SOCKS address type "
                            + atyp
                            + " unsupported"
            );
        }

        int port =
                (readU8(in) << 8)
                        | readU8(in);

        return new Request(
                command,
                atyp,
                host,
                port
        );
    }

    private Socket connectCiadpi(
            String ip,
            int port
    ) throws Exception {

        Socket socket = track(new Socket());
        try {

        socket.connect(
                new InetSocketAddress(
                        "127.0.0.1",
                        DragonByeDpi.PORT
                ),
                1500
        );

        socket.setTcpNoDelay(true);
        socket.setSoTimeout(10_000);

        InputStream in =
                socket.getInputStream();

        OutputStream out =
                socket.getOutputStream();

        out.write(
                new byte[]{
                        0x05,
                        0x01,
                        0x00
                }
        );

        out.flush();

        byte[] greeting =
                readExact(in, 2);

        if ((greeting[0] & 0xff) != 0x05
                || (greeting[1] & 0xff) != 0x00) {
            closeQuietly(socket);

            throw new IOException(
                    "ciadpi SOCKS greeting failed"
            );
        }

        byte[] address =
                InetAddress.getByName(ip)
                        .getAddress();

        int atyp =
                address.length == 16
                        ? 0x04
                        : 0x01;

        byte[] request =
                new byte[
                        4
                                + address.length
                                + 2
                        ];

        request[0] = 0x05;
        request[1] = 0x01;
        request[2] = 0x00;
        request[3] = (byte) atyp;

        System.arraycopy(
                address,
                0,
                request,
                4,
                address.length
        );

        request[request.length - 2] =
                (byte) (
                        (port >>> 8)
                                & 0xff
                );

        request[request.length - 1] =
                (byte) (
                        port
                                & 0xff
                );

        out.write(request);
        out.flush();

        byte[] reply =
                readExact(in, 4);

        if ((reply[0] & 0xff) != 0x05
                || (reply[1] & 0xff) != 0x00) {
            int code =
                    reply.length > 1
                            ? reply[1] & 0xff
                            : -1;

            closeQuietly(socket);

            throw new IOException(
                    "ciadpi CONNECT failed • code "
                            + code
            );
        }

        int replyType =
                reply[3] & 0xff;

        if (replyType == 0x01) {
            readExact(in, 4);
        } else if (replyType == 0x04) {
            readExact(in, 16);
        } else if (replyType == 0x03) {
            int len = readU8(in);
            readExact(in, len);
        } else {
            closeQuietly(socket);

            throw new IOException(
                    "ciadpi bad reply address"
            );
        }

        readExact(in, 2);

        return socket;
        } catch (Exception e) {
            sockets.remove(socket);
            closeQuietly(socket);
            throw e;
        }
    }

    private Socket connectCiadpiDomain(
            String host,
            int port
    ) throws Exception {

        byte[] name =
                host.getBytes(
                        StandardCharsets.US_ASCII
                );

        if (name.length == 0
                || name.length > 255) {
            throw new IOException(
                    "invalid SOCKS domain"
            );
        }

        Socket socket = track(new Socket());
        try {

        socket.connect(
                new InetSocketAddress(
                        "127.0.0.1",
                        DragonByeDpi.PORT
                ),
                1500
        );

        socket.setTcpNoDelay(true);
        socket.setSoTimeout(10_000);

        InputStream in =
                socket.getInputStream();

        OutputStream out =
                socket.getOutputStream();

        out.write(
                new byte[]{
                        0x05,
                        0x01,
                        0x00
                }
        );
        out.flush();

        byte[] greeting =
                readExact(in, 2);

        if ((greeting[0] & 0xff) != 0x05
                || (greeting[1] & 0xff) != 0x00) {
            closeQuietly(socket);

            throw new IOException(
                    "ciadpi SOCKS greeting failed"
            );
        }

        byte[] request =
                new byte[
                        4
                                + 1
                                + name.length
                                + 2
                        ];

        request[0] = 0x05;
        request[1] = 0x01;
        request[2] = 0x00;
        request[3] = 0x03;
        request[4] = (byte) name.length;

        System.arraycopy(
                name,
                0,
                request,
                5,
                name.length
        );

        int portOffset =
                5 + name.length;

        request[portOffset] =
                (byte) (
                        (port >>> 8)
                                & 0xff
                );

        request[portOffset + 1] =
                (byte) (
                        port
                                & 0xff
                );

        out.write(request);
        out.flush();

        byte[] reply =
                readExact(in, 4);

        if ((reply[0] & 0xff) != 0x05
                || (reply[1] & 0xff) != 0x00) {

            int code =
                    reply[1] & 0xff;

            closeQuietly(socket);

            throw new IOException(
                    "ciadpi DOMAIN CONNECT failed • code "
                            + code
            );
        }

        int replyType =
                reply[3] & 0xff;

        if (replyType == 0x01) {
            readExact(in, 4);

        } else if (replyType == 0x04) {
            readExact(in, 16);

        } else if (replyType == 0x03) {
            int len = readU8(in);
            readExact(in, len);

        } else {
            closeQuietly(socket);

            throw new IOException(
                    "ciadpi bad domain reply"
            );
        }

        readExact(in, 2);

        return socket;
        } catch (Exception e) {
            sockets.remove(socket);
            closeQuietly(socket);
            throw e;
        }
    }

    private Socket connectDirect(
            String ip,
            int port
    ) throws Exception {

        Socket socket = track(new Socket());
        try {

        socket.connect(
                new InetSocketAddress(
                        ip,
                        port
                ),
                1800
        );

        socket.setTcpNoDelay(true);
        return socket;
        } catch (Exception e) {
            sockets.remove(socket);
            closeQuietly(socket);
            throw e;
        }
    }

    private static void sendReply(
            Socket socket,
            int code
    ) throws Exception {

        OutputStream out =
                socket.getOutputStream();

        out.write(
                new byte[]{
                        0x05,
                        (byte) code,
                        0x00,
                        0x01,
                        0x00,
                        0x00,
                        0x00,
                        0x00,
                        0x00,
                        0x00
                }
        );

        out.flush();
    }

    private static void relay(
            Socket from,
            Socket to
    ) {
        byte[] buffer =
                new byte[32 * 1024];

        try {
            InputStream in =
                    from.getInputStream();

            OutputStream out =
                    to.getOutputStream();

            while (!Thread.currentThread()
                    .isInterrupted()) {

                int n =
                        in.read(buffer);

                if (n < 0) break;

                if (n == 0) continue;

                out.write(
                        buffer,
                        0,
                        n
                );

                out.flush();
            }

        } catch (Exception ignored) {
        }

        try {
            to.shutdownOutput();
        } catch (Exception ignored) {
        }
    }

    private static int readU8(
            InputStream in
    ) throws Exception {

        int value = in.read();

        if (value < 0) {
            throw new EOFException(
                    "Unexpected SOCKS EOF"
            );
        }

        return value;
    }

    private static byte[] readExact(
            InputStream in,
            int size
    ) throws Exception {

        byte[] data =
                new byte[size];

        int offset = 0;

        while (offset < size) {
            int n =
                    in.read(
                            data,
                            offset,
                            size - offset
                    );

            if (n < 0) {
                throw new EOFException(
                        "Unexpected SOCKS EOF"
                );
            }

            offset += n;
        }

        return data;
    }

    private static void closeQuietly(
            Socket socket
    ) {
        if (socket == null) return;

        try {
            socket.close();
        } catch (Exception ignored) {
        }
    }

    private static String safeMessage(
            Exception e
    ) {
        String message =
                e.getMessage();

        return message == null
                || message.trim().isEmpty()
                ? e.getClass()
                .getSimpleName()
                : message;
    }

    private static final class Request {
        final int command;
        final int addressType;
        final String host;
        final int port;

        Request(
                int command,
                int addressType,
                String host,
                int port
        ) {
            this.command = command;
            this.addressType = addressType;
            this.host = host;
            this.port = port;
        }
    }
}
