package com.anonymouskeys.xdns;

import android.content.SharedPreferences;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class FastDoh {

    private static final int RACE_COUNT = 3;
    private static final long RACE_TIMEOUT_MS = 3500;
    private static final long CACHE_TTL_MS = 120_000;

    private static final ExecutorService POOL =
            Executors.newFixedThreadPool(6);

    private static final ConcurrentHashMap<String, CacheEntry> A_CACHE =
            new ConcurrentHashMap<>();

    private FastDoh() {}

    public static final class RaceResult {
        public final DohClient.Result result;
        public final String endpoint;

        RaceResult(DohClient.Result result, String endpoint) {
            this.result = result;
            this.endpoint = endpoint;
        }
    }

    private static final class CacheEntry {
        final String ip;
        final long expiresAt;

        CacheEntry(String ip, long expiresAt) {
            this.ip = ip;
            this.expiresAt = expiresAt;
        }
    }

    public static RaceResult query(
            SharedPreferences prefs,
            byte[] dnsMessage
    ) {
        List<String> urls = candidateUrls(prefs);

        if (urls.isEmpty()) {
            DohClient.Result result =
                    new DohClient.Result(
                            null,
                            0,
                            -1,
                            "RACE",
                            "No DoH resolvers available"
                    );

            return new RaceResult(result, "");
        }

        int count = Math.min(RACE_COUNT, urls.size());

        CompletionService<RaceResult> completion =
                new ExecutorCompletionService<>(POOL);

        List<Future<RaceResult>> futures =
                new ArrayList<>();

        for (int i = 0; i < count; i++) {
            String endpoint = urls.get(i);

            futures.add(
                    completion.submit(() ->
                            new RaceResult(
                                    DohClient.query(
                                            endpoint,
                                            dnsMessage
                                    ),
                                    endpoint
                            )
                    )
            );
        }

        long deadline =
                System.currentTimeMillis()
                        + RACE_TIMEOUT_MS;

        RaceResult bestError = null;
        int received = 0;

        try {
            while (received < count) {
                long left =
                        deadline
                                - System.currentTimeMillis();

                if (left <= 0) break;

                Future<RaceResult> future =
                        completion.poll(
                                left,
                                TimeUnit.MILLISECONDS
                        );

                if (future == null) break;

                received++;

                RaceResult value = future.get();

                if (value.result.ok()) {
                    cancelAll(futures);
                    return value;
                }

                if (bestError == null
                        || value.result.latencyMs
                        < bestError.result.latencyMs) {
                    bestError = value;
                }
            }

        } catch (Exception ignored) {
        } finally {
            cancelAll(futures);
        }

        if (bestError != null) {
            return bestError;
        }

        DohClient.Result timeout =
                new DohClient.Result(
                        null,
                        RACE_TIMEOUT_MS,
                        -1,
                        "RACE",
                        "No DoH reply within "
                                + RACE_TIMEOUT_MS
                                + " ms"
                );

        return new RaceResult(
                timeout,
                urls.get(0)
        );
    }

    public static String resolveA(
            SharedPreferences prefs,
            String host
    ) throws Exception {

        String key =
                host.toLowerCase(Locale.ROOT);

        CacheEntry cached =
                A_CACHE.get(key);

        long now =
                System.currentTimeMillis();

        if (cached != null
                && cached.expiresAt > now) {
            return cached.ip;
        }

        RaceResult raced =
                query(
                        prefs,
                        DohClient.makeTestQuery(host)
                );

        if (!raced.result.ok()) {
            throw new IllegalStateException(
                    "DoH failed for "
                            + host
                            + ": "
                            + (raced.result.error == null
                            ? "unknown error"
                            : raced.result.error)
            );
        }

        String ip =
                DnsPacket.firstAddress(
                        raced.result.body
                );

        if (ip == null
                || ip.isEmpty()
                || "-".equals(ip)) {
            throw new IllegalStateException(
                    "No A answer for " + host
            );
        }

        A_CACHE.put(
                key,
                new CacheEntry(
                        ip,
                        now + CACHE_TTL_MS
                )
        );

        DnsLog.addRaw(
                "DOH BRIDGE • "
                        + host
                        + " → "
                        + ip
                        + " • "
                        + hostOf(raced.endpoint)
                        + " • "
                        + raced.result.latencyMs
                        + " ms"
        );

        return ip;
    }

    public static List<String> candidateUrls(
            SharedPreferences prefs
    ) {
        Set<String> unique =
                new LinkedHashSet<>();

        String selected =
                prefs.getString(
                        XDnsVpnService.KEY_DOH_URL,
                        XDnsVpnService.DEFAULT_DOH
                );

        if (selected != null
                && selected.startsWith("https://")) {
            unique.add(selected);
        }

        for (ResolverStore.Entry entry
                : ResolverStore.working(prefs)) {
            if (entry.url != null
                    && entry.url.startsWith("https://")) {
                unique.add(entry.url);
            }

            if (unique.size() >= 8) break;
        }

        return new ArrayList<>(unique);
    }

    public static void clearCache() {
        A_CACHE.clear();
    }

    private static void cancelAll(
            List<Future<RaceResult>> futures
    ) {
        for (Future<RaceResult> future : futures) {
            if (future != null
                    && !future.isDone()) {
                future.cancel(true);
            }
        }
    }

    private static String hostOf(String url) {
        try {
            String host =
                    URI.create(url).getHost();

            return host == null
                    ? url
                    : host;
        } catch (Exception e) {
            return url;
        }
    }
}
