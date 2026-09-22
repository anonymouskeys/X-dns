package com.anonymouskeys.xdns;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

public final class YoutubeProbe {

    private static final String HOST = "www.youtube.com";
    private static final int HTTPS_PORT = 443;

    private YoutubeProbe() {}

    public static final class Result {
        public final boolean ok;
        public final long latencyMs;
        public final int httpCode;
        public final String error;

        Result(
                boolean ok,
                long latencyMs,
                int httpCode,
                String error
        ) {
            this.ok = ok;
            this.latencyMs = latencyMs;
            this.httpCode = httpCode;
            this.error = error;
        }
    }

    public static Result throughByeDpi(String dohUrl) {
        long started = System.currentTimeMillis();

        try {
            String ip = resolveViaDoh(dohUrl, HOST);

            DnsLog.addRaw(
                    "AUTO • DoH " + HOST + " → " + ip
            );

            Socket socks = connectLocalSocksWithRetry(
                    "127.0.0.1",
                    DragonByeDpi.PORT,
                    2500
            );

            socks.setSoTimeout(8000);

            try {
                socks5Handshake(socks, ip, HTTPS_PORT);

                DnsLog.addRaw(
                        "AUTO • SOCKS5 CONNECT " + ip + ":443 ✓"
                );

                SSLSocketFactory factory =
                        (SSLSocketFactory) SSLSocketFactory.getDefault();

                SSLSocket tls =
                        (SSLSocket) factory.createSocket(
                                socks,
                                HOST,
                                HTTPS_PORT,
                                true
                        );

                tls.setSoTimeout(8000);

                SSLParameters parameters =
                        tls.getSSLParameters();

                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                parameters.setServerNames(
                        Collections.singletonList(
                                new SNIHostName(HOST)
                        )
                );

                tls.setSSLParameters(parameters);
                tls.startHandshake();

                DnsLog.addRaw(
                        "AUTO • TLS/SNI " + HOST + " ✓"
                );

                OutputStream out = tls.getOutputStream();

                String request =
                        "GET /generate_204 HTTP/1.1\r\n"
                                + "Host: " + HOST + "\r\n"
                                + "User-Agent: X-dns/0.4.1\r\n"
                                + "Accept: */*\r\n"
                                + "Connection: close\r\n"
                                + "\r\n";

                out.write(
                        request.getBytes(
                                StandardCharsets.US_ASCII
                        )
                );
                out.flush();

                BufferedReader reader =
                        new BufferedReader(
                                new InputStreamReader(
                                        tls.getInputStream(),
                                        StandardCharsets.US_ASCII
                                )
                        );

                String statusLine = reader.readLine();

                int code = parseHttpCode(statusLine);

                long latency =
                        System.currentTimeMillis() - started;

                boolean ok =
                        code >= 200 && code < 500;

                try {
                    tls.close();
                } catch (Exception ignored) {
                }

                return new Result(
                        ok,
                        latency,
                        code,
                        ok
                                ? null
                                : "HTTP "
                                + (code > 0
                                ? code
                                : "invalid response")
                );

            } finally {
                try {
                    socks.close();
                } catch (Exception ignored) {
                }
            }

        } catch (Exception e) {
            return new Result(
                    false,
                    System.currentTimeMillis() - started,
                    -1,
                    phaseError(e)
            );
        }
    }

    private static String resolveViaDoh(
            String dohUrl,
            String hostname
    ) throws Exception {

        DohClient.Result result =
                DohClient.query(
                        dohUrl,
                        DohClient.makeTestQuery(hostname)
                );

        if (!result.ok()) {
            throw new IllegalStateException(
                    "DOH: "
                            + (result.error == null
                            ? "failed"
                            : result.error)
            );
        }

        String ip =
                DnsPacket.firstAddress(
                        result.body
                );

        if (ip == null
                || ip.isEmpty()
                || "-".equals(ip)) {
            throw new IllegalStateException(
                    "DOH: no A answer for " + hostname
            );
        }

        return ip;
    }

    private static Socket connectLocalSocksWithRetry(
            String host,
            int port,
            long timeoutMs
    ) throws Exception {

        long end =
                System.currentTimeMillis() + timeoutMs;

        Exception last = null;

        while (System.currentTimeMillis() < end) {
            Socket socket = new Socket();

            try {
                socket.connect(
                        new InetSocketAddress(
                                host,
                                port
                        ),
                        300
                );

                return socket;

            } catch (Exception e) {
                last = e;

                try {
                    socket.close();
                } catch (Exception ignored) {
                }

                Thread.sleep(100);
            }
        }

        throw new IllegalStateException(
                "LOCAL SOCKS: "
                        + (last == null
                        ? "127.0.0.1:1080 unavailable"
                        : safeMessage(last))
        );
    }

    private static void socks5Handshake(
            Socket socket,
            String ip,
            int port
    ) throws Exception {

        InputStream in =
                socket.getInputStream();

        OutputStream out =
                socket.getOutputStream();

        out.write(new byte[]{
                0x05, 0x01, 0x00
        });
        out.flush();

        byte[] greeting =
                readFully(in, 2);

        if ((greeting[0] & 0xff) != 0x05) {
            throw new IllegalStateException(
                    "SOCKS5: invalid version"
            );
        }

        if ((greeting[1] & 0xff) != 0x00) {
            throw new IllegalStateException(
                    "SOCKS5: no-auth rejected"
            );
        }

        byte[] address =
                InetAddress.getByName(ip)
                        .getAddress();

        int atyp;

        if (address.length == 4) {
            atyp = 0x01;
        } else if (address.length == 16) {
            atyp = 0x04;
        } else {
            throw new IllegalStateException(
                    "SOCKS5: unsupported IP " + ip
            );
        }

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
                (byte) ((port >>> 8) & 0xff);

        request[request.length - 1] =
                (byte) (port & 0xff);

        out.write(request);
        out.flush();

        byte[] head =
                readFully(in, 4);

        if ((head[0] & 0xff) != 0x05) {
            throw new IllegalStateException(
                    "SOCKS5: invalid CONNECT response"
            );
        }

        int reply =
                head[1] & 0xff;

        if (reply != 0x00) {
            throw new IllegalStateException(
                    "SOCKS5: CONNECT reply "
                            + replyName(reply)
            );
        }

        int replyAtyp =
                head[3] & 0xff;

        if (replyAtyp == 0x01) {
            readFully(in, 4);
        } else if (replyAtyp == 0x04) {
            readFully(in, 16);
        } else if (replyAtyp == 0x03) {
            int len = readFully(in, 1)[0] & 0xff;
            readFully(in, len);
        } else {
            throw new IllegalStateException(
                    "SOCKS5: bad reply address type"
            );
        }

        readFully(in, 2);
    }

    private static byte[] readFully(
            InputStream in,
            int count
    ) throws Exception {

        byte[] out =
                new byte[count];

        int offset = 0;

        while (offset < count) {
            int n =
                    in.read(
                            out,
                            offset,
                            count - offset
                    );

            if (n < 0) {
                throw new IllegalStateException(
                        "SOCKS5: unexpected EOF"
                );
            }

            offset += n;
        }

        return out;
    }

    private static int parseHttpCode(
            String statusLine
    ) {
        if (statusLine == null) return -1;

        String[] parts =
                statusLine.trim()
                        .split("\\s+");

        if (parts.length < 2) return -1;

        try {
            return Integer.parseInt(
                    parts[1]
            );
        } catch (Exception e) {
            return -1;
        }
    }

    private static String replyName(
            int reply
    ) {
        switch (reply) {
            case 1:
                return "general failure";
            case 2:
                return "not allowed";
            case 3:
                return "network unreachable";
            case 4:
                return "host unreachable";
            case 5:
                return "connection refused";
            case 6:
                return "TTL expired";
            case 7:
                return "command unsupported";
            case 8:
                return "address type unsupported";
            default:
                return "code " + reply;
        }
    }

    private static String phaseError(
            Exception e
    ) {
        String message =
                safeMessage(e);

        if (message.startsWith("DOH:")) {
            return message;
        }

        if (message.startsWith("LOCAL SOCKS:")) {
            return message;
        }

        if (message.startsWith("SOCKS5:")) {
            return message;
        }

        return "TLS/HTTP: " + message;
    }

    private static String safeMessage(
            Exception e
    ) {
        String message =
                e.getMessage();

        return message == null
                || message.trim().isEmpty()
                ? e.getClass().getSimpleName()
                : message;
    }
}
