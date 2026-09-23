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

    private static final int FALLBACK_RESOLVERS = 8;
    private static final long FALLBACK_TIMEOUT_MS = 2800;
    private static final long CACHE_TTL_MS = 180_000;

    private static final ExecutorService POOL =
            Executors.newFixedThreadPool(8);

    private static final ConcurrentHashMap<String, CacheEntry> ADDRESS_CACHE =
            new ConcurrentHashMap<>();

    private FastDoh() {}
    private static final RecoveryGeneration cacheGeneration = new RecoveryGeneration();

    public static final class RaceResult {
        public final DohClient.Result result;
        public final String endpoint;

        RaceResult(
                DohClient.Result result,
                String endpoint
        ) {
            this.result = result;
            this.endpoint = endpoint;
        }
    }

    private static final class CacheEntry {
        final List<String> addresses;
        final long expiresAt;

        CacheEntry(
                List<String> addresses,
                long expiresAt
        ) {
            this.addresses =
                    Collections.unmodifiableList(
                            new ArrayList<>(addresses)
                    );
            this.expiresAt = expiresAt;
        }
    }

    private static final class ResolveResult {
        final String endpoint;
        final DohClient.Result result;

        ResolveResult(
                String endpoint,
                DohClient.Result result
        ) {
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
                            null,
                            0,
                            -1,
                            "RACE",
                            "No DoH resolvers available"
                    ),
                    ""
            );
        }

        int count = Math.min(3, urls.size());

        CompletionService<RaceResult> completion =
                new ExecutorCompletionService<>(POOL);

        List<Future<RaceResult>> futures =
                new ArrayList<>();

        for (int i = 0; i < count; i++) {
            String endpoint = urls.get(i);

            futures.add(
                    completion.submit(
                            () -> new RaceResult(
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
                System.currentTimeMillis() + 2500;

        RaceResult bestError = null;
        int received = 0;

        try {
            while (received < count) {
                long left =
                        deadline - System.currentTimeMillis();

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
                        2500,
                        -1,
                        "RACE",
                        "No DoH reply within 2500 ms"
                ),
                urls.get(0)
        );
    }

    public static List<String> resolveCandidates(
            SharedPreferences prefs,
            String host
    ) throws Exception {

        final long ticket = cacheGeneration.current();
        String key =
                host.toLowerCase(Locale.ROOT);

        long now = System.currentTimeMillis();

        CacheEntry cached =
                ADDRESS_CACHE.get(key);

        if (cached != null
                && cached.expiresAt > now
                && !cached.addresses.isEmpty()) {
            return new ArrayList<>(cached.addresses);
        }

        List<String> urls =
                candidateUrls(prefs);

        if (urls.isEmpty()) {
            throw new IllegalStateException(
                    "No DoH resolvers available"
            );
        }

        String primary = urls.get(0);

        DohClient.Result primaryResult =
                DohClient.query(
                        primary,
                        DohClient.makeTestQuery(host)
                );

        if (primaryResult.ok()) {
            List<String> primaryAddresses =
                    DnsPacket.allIpv4Addresses(
                            primaryResult.body
                    );

            if (!primaryAddresses.isEmpty()) {
                remember(
                        key,
                        primaryAddresses, ticket
                );

                DnsLog.addRaw(
                        "DOH FAST • "
                                + host
                                + " • "
                                + hostOf(primary)
                                + " • "
                                + primaryAddresses.size()
                                + " A • "
                                + primaryResult.latencyMs
                                + " ms"
                );

                return primaryAddresses;
            }

            DnsLog.addRaw(
                    "DOH FAST • "
                            + host
                            + " • "
                            + hostOf(primary)
                            + " • sinkhole/no public A"
            );
        }

        List<String> fallback =
                resolveFallback(
                        urls,
                        host
                );

        if (fallback.isEmpty()) {
            throw new IllegalStateException(
                    "No usable public A answer for "
                            + host
            );
        }

        remember(
                key,
                fallback, ticket
        );

        return fallback;
    }

    public static List<String> resolveAlternateCandidates(
            SharedPreferences prefs,
            String host,
            List<String> alreadyTried
    ) {
        List<String> urls =
                candidateUrls(prefs);

        if (urls.size() <= 1) {
            return Collections.emptyList();
        }

        List<String> fallback =
                resolveFallback(
                        urls,
                        host
                );

        Set<String> remaining =
                new LinkedHashSet<>(fallback);

        if (alreadyTried != null) {
            remaining.removeAll(
                    alreadyTried
            );
        }

        return new ArrayList<>(remaining);
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

            if (unique.size() >= 10) {
                break;
            }
        }

        return new ArrayList<>(unique);
    }

    public static void invalidate(
            String host
    ) {
        if (host == null) return;

        ADDRESS_CACHE.remove(
                host.toLowerCase(Locale.ROOT)
        );
    }

    public static void clearCache() {
        synchronized (cacheGeneration) {
            cacheGeneration.invalidate();
            ADDRESS_CACHE.clear();
        }
    }

    private static List<String> resolveFallback(
            List<String> urls,
            String host
    ) {
        int start =
                urls.size() > 1 ? 1 : 0;

        int count =
                Math.min(
                        FALLBACK_RESOLVERS,
                        urls.size() - start
                );

        if (count <= 0) {
            return Collections.emptyList();
        }

        CompletionService<ResolveResult> completion =
                new ExecutorCompletionService<>(POOL);

        List<Future<ResolveResult>> futures =
                new ArrayList<>();

        for (int i = 0; i < count; i++) {
            String endpoint =
                    urls.get(start + i);

            futures.add(
                    completion.submit(
                            () -> new ResolveResult(
                                    endpoint,
                                    DohClient.query(
                                            endpoint,
                                            DohClient.makeTestQuery(host)
                                    )
                            )
                    )
            );
        }

        Set<String> addresses =
                new LinkedHashSet<>();

        long deadline =
                System.currentTimeMillis()
                        + FALLBACK_TIMEOUT_MS;

        int received = 0;

        try {
            while (received < count) {
                long left =
                        deadline - System.currentTimeMillis();

                if (left <= 0) break;

                Future<ResolveResult> future =
                        completion.poll(
                                left,
                                TimeUnit.MILLISECONDS
                        );

                if (future == null) break;

                ResolveResult value =
                        future.get();

                received++;

                if (!value.result.ok()) {
                    continue;
                }

                List<String> fromResponse =
                        DnsPacket.allIpv4Addresses(
                                value.result.body
                        );

                if (!fromResponse.isEmpty()) {
                    addresses.addAll(
                            fromResponse
                    );

                    DnsLog.addRaw(
                            "DOH FALLBACK • "
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

                if (addresses.size() >= 16) {
                    break;
                }
            }

        } catch (Exception ignored) {
        } finally {
            cancelResolve(futures);
        }

        return new ArrayList<>(addresses);
    }

    private static void remember(
            String key,
            List<String> addresses, long ticket
    ) {
        cacheGeneration.publish(ticket, () -> ADDRESS_CACHE.put(
                key,
                new CacheEntry(
                        addresses,
                        System.currentTimeMillis()
                                + CACHE_TTL_MS
                )
        ));
    }

    private static void cancelRace(
            List<Future<RaceResult>> futures
    ) {
        for (Future<RaceResult> future : futures) {
            if (future != null
                    && !future.isDone()) {
                future.cancel(true);
            }
        }
    }

    private static void cancelResolve(
            List<Future<ResolveResult>> futures
    ) {
        for (Future<ResolveResult> future : futures) {
            if (future != null
                    && !future.isDone()) {
                future.cancel(true);
            }
        }
    }

    private static String hostOf(
            String url
    ) {
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
