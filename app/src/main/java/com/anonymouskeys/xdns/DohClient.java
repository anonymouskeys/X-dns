package com.anonymouskeys.xdns;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.SecureRandom;

public final class DohClient {

    private DohClient() {}

    public static final class Result {
        public final byte[] body;
        public final long latencyMs;
        public final int httpCode;
        public final String error;

        Result(byte[] body, long latencyMs, int httpCode, String error) {
            this.body = body;
            this.latencyMs = latencyMs;
            this.httpCode = httpCode;
            this.error = error;
        }

        public boolean ok() {
            return body != null && error == null && httpCode == 200;
        }
    }

    public static Result query(String endpoint, byte[] dnsMessage) {
        long started = System.currentTimeMillis();
        HttpURLConnection connection = null;

        try {
            URL url = new URL(endpoint);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(12000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/dns-message");
            connection.setRequestProperty("Content-Type", "application/dns-message");
            connection.setRequestProperty("User-Agent", "X-dns/0.2");
            connection.setRequestProperty("Connection", "close");
            connection.setFixedLengthStreamingMode(dnsMessage.length);

            try (OutputStream os = connection.getOutputStream()) {
                os.write(dnsMessage);
                os.flush();
            }

            int code = connection.getResponseCode();
            InputStream source = code >= 200 && code < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();

            byte[] body = source == null ? new byte[0] : readLimited(source, 65535);
            long latency = System.currentTimeMillis() - started;

            if (code != 200) {
                return new Result(null, latency, code, "HTTP " + code);
            }

            if (body.length < 12) {
                return new Result(null, latency, code, "Invalid DNS response");
            }

            return new Result(body, latency, code, null);

        } catch (Exception e) {
            long latency = System.currentTimeMillis() - started;
            String message = e.getMessage();
            if (message == null || message.trim().isEmpty()) {
                message = e.getClass().getSimpleName();
            }
            return new Result(null, latency, -1, message);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    public static byte[] makeTestQuery(String host) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int id = new SecureRandom().nextInt(65536);

        write16(out, id);
        write16(out, 0x0100); // RD
        write16(out, 1);      // QDCOUNT
        write16(out, 0);
        write16(out, 0);
        write16(out, 0);

        String[] labels = host.split("\\.");
        for (String label : labels) {
            byte[] bytes = label.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            out.write(bytes.length);
            out.write(bytes, 0, bytes.length);
        }
        out.write(0);
        write16(out, 1); // A
        write16(out, 1); // IN
        return out.toByteArray();
    }

    private static void write16(ByteArrayOutputStream out, int value) {
        out.write((value >>> 8) & 0xff);
        out.write(value & 0xff);
    }

    private static byte[] readLimited(InputStream in, int max) throws Exception {
        try (InputStream input = in;
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            byte[] buffer = new byte[4096];
            int total = 0;
            int n;

            while ((n = input.read(buffer)) != -1) {
                total += n;
                if (total > max) {
                    throw new IllegalStateException("DoH response too large");
                }
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }
}
