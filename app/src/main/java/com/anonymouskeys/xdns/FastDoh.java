package com.anonymouskeys.xdns;

import android.content.SharedPreferences;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
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
    private static final int RESOLVE_COUNT = 5;
    private static final long RACE_TIMEOUT_MS = 2500;
    private static final long RESOLVE_TIMEOUT_MS = 2200;
    private static final long CACHE_TTL_MS = 60_000;

    private static final ExecutorService POOL =
            Executors.newFixedThreadPool(8);

    private static final ConcurrentHashMap<String, CacheEntry> ADDRESS_CACHE =
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
        final List<String> addresses;
        final long expiresAt;

        CacheEntry(List<String> addresses, long expiresAt) {
            this.addresses = Collections.unmodifiableList(
                    new ArrayList<>(addresses)
            );
            this.expiresAt = expiresAt;
        }
    }

    private static final class ResolveResult {
        final String endpoint;
        final DohClient.Result result;

        ResolveResult(String endpoint, DohClient.Result result) {
            this.endpoint = endpoint;
            this.result = result;
        }
    }

    public static RaceResult query(
            SharedPreferences prefs,
            byte[] dnsMessage
    ) {
        List<String> urls = candidateUrls(prefs);

        if (urls.isEmpty()) {
            return new RaceResult(
                    new DohClient.Result(
                            null, 0, -1, "RACE",
                            "No DoH resolvers available"
                    ),
                    ""
            );
        }

        int count = Math.min(RACE_COUNT, urls.size());

        CompletionService<RaceResult> completion =
                new ExecutorCompletionService<>(POOL);

        List<Future<RaceResult>> futures = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            String endpoint = urls.get(i);

            futures.add(
                    completion.submit(() ->
                            new RaceResult(
                                    DohClient.query(endpoint, dnsMessage),
                                    endpoint
                            )
                    )
            );
        }

        long deadline = System.currentTimeMillis() + RACE_TIMEOUT_MS;
        RaceResult bestError = null;
        int received = 0;

        try {
            while (received < count) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) break;

                Future<RaceResult> future =
                        completion.poll(left, TimeUnit.MILLISECONDS);

                if (future == null) break;

                received++;
                RaceResult value = future.get();

                if (value.result.ok()) {
                    cancelRace(futures);
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
            cancelRace(futures);
        }

        if (bestError != null) {
            return bestError;
        }

        return new RaceResult(
                new DohClient.Result(
                        null,
                        RACE_TIMEOUT_MS,
                        -1,
                        "RACE",
                        "No DoH reply within "
                                + RACE_TIMEOUT_MS
                                + " ms"
                ),
                urls.get(0)
        );
    }

    public static List<String> resolveCandidates(
            SharedPreferences prefs,
            String host
    ) throws Exception {

        String key = host.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();

        CacheEntry cached = ADDRESS_CACHE.get(key);

        if (cached != null
                && cached.expiresAt > now
                && !cached.addresses.isEmpty()) {
            return new ArrayList<>(cached.addresses);
        }

        List<String> urls = candidateUrls(prefs);

        if (urls.isEmpty()) {
            throw new IllegalStateException("No DoH resolvers available");
        }

        int count = Math.min(RESOLVE_COUNT, urls.size());

        CompletionService<ResolveResult> completion =
                new ExecutorCompletionService<>(POOL);

        List<Future<ResolveResult>> futures = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            String endpoint = urls.get(i);

            futures.add(
                    completion.submit(() ->
                            new ResolveResult(
                                    endpoint,
                                    DohClient.query(
                                            endpoint,
                                            DohClient.makeTestQuery(host)
                                    )
                            )
                    )
            );
        }

        Set<String> addresses = new LinkedHashSet<>();
        long deadline = System.currentTimeMillis() + RESOLVE_TIMEOUT_MS;
        int received = 0;

        try {
            while (received < count) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) break;

                Future<ResolveResult> future =
                        completion.poll(left, TimeUnit.MILLISECONDS);

                if (future == null) break;

                ResolveResult value = future.get();
                received++;

                if (!value.result.ok()) {
                    continue;
                }

                List<String> fromResponse =
                        DnsPacket.allIpv4Addresses(
                                value.result.body
                        );

                if (!fromResponse.isEmpty()) {
                    addresses.addAll(fromResponse);

                    DnsLog.addRaw(
                            "DOH • "
                                    + host
                                    + " • "
                                    + hostOf(value.endpoint)
                                    + " • "
                                    + fromResponse.size()
                                    + " A • "
                                    + value.result.latencyMs
                                    + " ms"
                    );
                }

                if (addresses.size() >= 6
                        && received >= 2) {
                    break;
                }
            }

        } finally {
            cancelResolve(futures);
        }

        if (addresses.isEmpty()) {
            throw new IllegalStateException(
                    "No IPv4 DoH answer for " + host
            );
        }

        List<String> result = new ArrayList<>(addresses);

        ADDRESS_CACHE.put(
                key,
                new CacheEntry(
                        result,
                        now + CACHE_TTL_MS
                )
        );

        return result;
    }

    public static List<String> candidateUrls(
            SharedPreferences prefs
    ) {
        Set<String> unique = new LinkedHashSet<>();

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

            if (unique.size() >= 10) {
                break;
            }
        }

        return new ArrayList<>(unique);
    }

    public static void clearCache() {
        ADDRESS_CACHE.clear();
    }

    private static void cancelRace(
            List<Future<RaceResult>> futures
    ) {
        for (Future<RaceResult> future : futures) {
            if (future != null && !future.isDone()) {
                future.cancel(true);
            }
        }
    }

    private static void cancelResolve(
            List<Future<ResolveResult>> futures
    ) {
        for (Future<ResolveResult> future : futures) {
            if (future != null && !future.isDone()) {
                future.cancel(true);
            }
        }
    }

    private static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? url : host;
        } catch (Exception e) {
            return url;
        }
    }
}
