package com.anonymouskeys.xdns;

import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public final class DohClient {

    private static final MediaType DNS_MEDIA =
            MediaType.get("application/dns-message");

    private static final OkHttpClient CLIENT =
            new OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(12, TimeUnit.SECONDS)
                    .writeTimeout(12, TimeUnit.SECONDS)
                    .callTimeout(15, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(true)
                    .build();

    private DohClient() {}

    public static final class Result {
        public final byte[] body;
        public final long latencyMs;
        public final int httpCode;
        public final String method;
        public final String error;

        Result(byte[] body, long latencyMs, int httpCode, String method, String error) {
            this.body = body;
            this.latencyMs = latencyMs;
            this.httpCode = httpCode;
            this.method = method;
            this.error = error;
        }

        public boolean ok() {
            return body != null && error == null && httpCode == 200;
        }

        public String shortStatus() {
            if (ok()) return "OK " + latencyMs + " ms (" + method + ")";
            return "ERROR " + (error == null ? ("HTTP " + httpCode) : error);
        }
    }

    public static Result query(String endpoint, byte[] dnsMessage) {
        Result post = queryPost(endpoint, dnsMessage);

        // Some real-world resolvers/gateways behave differently from the RFC.
        // Retry wire-format GET when POST is rejected or malformed upstream.
        if (post.ok()) return post;

        if (post.httpCode == 400
                || post.httpCode == 404
                || post.httpCode == 405
                || post.httpCode == 415
                || post.httpCode == 501) {
            Result get = queryGet(endpoint, dnsMessage);
            if (get.ok()) return get;
            return preferUsefulError(post, get);
        }

        // Also try GET after transport-ish failures because it can take a
        // different proxy/cache path on some mobile networks.
        if (post.httpCode < 0) {
            Result get = queryGet(endpoint, dnsMessage);
            if (get.ok()) return get;
            return preferUsefulError(post, get);
        }

        return post;
    }

    private static Result queryPost(String endpoint, byte[] dnsMessage) {
        long started = System.currentTimeMillis();

        try {
            RequestBody body = RequestBody.create(dnsMessage, DNS_MEDIA);

            Request request = new Request.Builder()
                    .url(endpoint)
                    .header("Accept", "application/dns-message")
                    .header("Content-Type", "application/dns-message")
                    .header("User-Agent", "X-dns/0.2.1")
                    .post(body)
                    .build();

            try (Response response = CLIENT.newCall(request).execute()) {
                long latency = System.currentTimeMillis() - started;
                byte[] bytes = bodyBytes(response.body());

                if (response.code() != 200) {
                    return new Result(
                            null,
                            latency,
                            response.code(),
                            "POST",
                            "HTTP " + response.code()
                    );
                }

                if (bytes == null || bytes.length < 12) {
                    return new Result(
                            null,
                            latency,
                            response.code(),
                            "POST",
                            "Invalid DNS response"
                    );
                }

                return new Result(bytes, latency, response.code(), "POST", null);
            }
        } catch (Exception e) {
            return new Result(
                    null,
                    System.currentTimeMillis() - started,
                    -1,
                    "POST",
                    safeMessage(e)
            );
        }
    }

    private static Result queryGet(String endpoint, byte[] dnsMessage) {
        long started = System.currentTimeMillis();

        try {
            HttpUrl base = HttpUrl.parse(endpoint);
            if (base == null) {
                return new Result(null, 0, -1, "GET", "Invalid DoH URL");
            }

            String encoded = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(dnsMessage);

            HttpUrl url = base.newBuilder()
                    .setQueryParameter("dns", encoded)
                    .build();

            Request request = new Request.Builder()
                    .url(url)
                    .header("Accept", "application/dns-message")
                    .header("User-Agent", "X-dns/0.2.1")
                    .get()
                    .build();

            try (Response response = CLIENT.newCall(request).execute()) {
                long latency = System.currentTimeMillis() - started;
                byte[] bytes = bodyBytes(response.body());

                if (response.code() != 200) {
                    return new Result(
                            null,
                            latency,
                            response.code(),
                            "GET",
                            "HTTP " + response.code()
                    );
                }

                if (bytes == null || bytes.length < 12) {
                    return new Result(
                            null,
                            latency,
                            response.code(),
                            "GET",
                            "Invalid DNS response"
                    );
                }

                return new Result(bytes, latency, response.code(), "GET", null);
            }
        } catch (Exception e) {
            return new Result(
                    null,
                    System.currentTimeMillis() - started,
                    -1,
                    "GET",
                    safeMessage(e)
            );
        }
    }

    public static byte[] makeTestQuery(String host) {
        return makeQuery(host, 1);
    }

    public static byte[] makeAaaaQuery(String host) {
        return makeQuery(host, 28);
    }

    private static byte[] makeQuery(
            String host,
            int queryType
    ) {
        ByteArrayOutputStream out =
                new ByteArrayOutputStream();

        int id =
                new SecureRandom()
                        .nextInt(65536);

        write16(out, id);
        write16(out, 0x0100);
        write16(out, 1);
        write16(out, 0);
        write16(out, 0);
        write16(out, 0);

        String[] labels =
                host.split("\\.");

        for (String label : labels) {
            byte[] bytes =
                    label.getBytes(
                            java.nio.charset.StandardCharsets.US_ASCII
                    );

            out.write(bytes.length);
            out.write(
                    bytes,
                    0,
                    bytes.length
            );
        }

        out.write(0);
        write16(out, queryType);
        write16(out, 1);

        return out.toByteArray();
    }

    private static byte[] bodyBytes(ResponseBody body) throws Exception {
        return body == null ? null : body.bytes();
    }

    private static void write16(ByteArrayOutputStream out, int value) {
        out.write((value >>> 8) & 0xff);
        out.write(value & 0xff);
    }

    private static Result preferUsefulError(Result a, Result b) {
        if (b.httpCode > 0 && a.httpCode < 0) return b;
        if (a.httpCode > 0 && b.httpCode < 0) return a;
        if (b.error != null && a.error != null && b.error.length() < a.error.length()) return b;
        return a;
    }

    private static String safeMessage(Exception e) {
        String msg = e.getMessage();
        return msg == null || msg.trim().isEmpty()
                ? e.getClass().getSimpleName()
                : msg;
    }
}
