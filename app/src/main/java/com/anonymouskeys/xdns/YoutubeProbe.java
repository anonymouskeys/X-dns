package com.anonymouskeys.xdns;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public final class YoutubeProbe {

    private YoutubeProbe() {}

    public static final class Result {
        public final boolean ok;
        public final long latencyMs;
        public final int httpCode;
        public final String error;

        Result(boolean ok, long latencyMs, int httpCode, String error) {
            this.ok = ok;
            this.latencyMs = latencyMs;
            this.httpCode = httpCode;
            this.error = error;
        }
    }

    public static Result throughByeDpi(String dohUrl) {
        long started = System.currentTimeMillis();

        try {
            Dns dohDns = hostname -> resolveOne(dohUrl, hostname);

            OkHttpClient client = new OkHttpClient.Builder()
                    .proxy(new Proxy(
                            Proxy.Type.SOCKS,
                            new InetSocketAddress(
                                    "127.0.0.1",
                                    DragonByeDpi.PORT
                            )
                    ))
                    .dns(dohDns)
                    .connectTimeout(7, TimeUnit.SECONDS)
                    .readTimeout(7, TimeUnit.SECONDS)
                    .writeTimeout(7, TimeUnit.SECONDS)
                    .callTimeout(10, TimeUnit.SECONDS)
                    .followRedirects(false)
                    .retryOnConnectionFailure(false)
                    .build();

            Request request = new Request.Builder()
                    .url("https://www.youtube.com/generate_204")
                    .header("User-Agent", "X-dns/0.4")
                    .get()
                    .build();

            try (Response response = client.newCall(request).execute()) {
                long latency =
                        System.currentTimeMillis() - started;

                int code = response.code();

                // A real HTTPS response below 500 means DNS, TCP, TLS and SNI
                // all made it through the selected local ciadpi strategy.
                boolean ok = code >= 200 && code < 500;

                return new Result(
                        ok,
                        latency,
                        code,
                        ok ? null : "HTTP " + code
                );
            }

        } catch (Exception e) {
            return new Result(
                    false,
                    System.currentTimeMillis() - started,
                    -1,
                    safeMessage(e)
            );
        }
    }

    private static List<InetAddress> resolveOne(
            String dohUrl,
            String hostname
    ) throws UnknownHostException {

        DohClient.Result result = DohClient.query(
                dohUrl,
                DohClient.makeTestQuery(hostname)
        );

        if (!result.ok()) {
            throw new UnknownHostException(
                    hostname + " via DoH: "
                            + (result.error == null
                            ? "failed"
                            : result.error)
            );
        }

        String ip = DnsPacket.firstAddress(result.body);

        if (ip == null || ip.equals("-") || ip.isEmpty()) {
            throw new UnknownHostException(
                    hostname + " via DoH: no A answer"
            );
        }

        try {
            return Collections.singletonList(
                    InetAddress.getByName(ip)
            );
        } catch (Exception e) {
            UnknownHostException out =
                    new UnknownHostException(ip);
            out.initCause(e);
            throw out;
        }
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();

        return message == null || message.trim().isEmpty()
                ? e.getClass().getSimpleName()
                : message;
    }
}
