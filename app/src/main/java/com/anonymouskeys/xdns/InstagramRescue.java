package com.anonymouskeys.xdns;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

public final class InstagramRescue {

    private static final int MAX_RESOLVERS = 6;
    private static final long POSITIVE_TTL_MS = 120_000L;
    private static final long NEGATIVE_TTL_MS = 30_000L;

    private static final ConcurrentHashMap<String, CacheEntry> CACHE =
            new ConcurrentHashMap<>();

    private InstagramRescue() {}

    private static final class CacheEntry {
        final List<String> addresses;
        final long expiresAt;

        CacheEntry(
                List<String> addresses,
                long expiresAt
        ) {
            this.addresses =
                    new ArrayList<>(addresses);
            this.expiresAt = expiresAt;
        }
    }

    public static boolean isMetaHost(
            String host
    ) {
        if (host == null) return false;

        String h =
                host.toLowerCase(
                        Locale.ROOT
                );

        return h.equals("instagram.com")
                || h.endsWith(".instagram.com")
                || h.equals("facebook.com")
                || h.endsWith(".facebook.com")
                || h.endsWith(".fbcdn.net")
                || h.endsWith(".cdninstagram.com");
    }

    public static List<String> prependIpv6(
            SharedPreferences prefs,
            String host,
            List<String> ipv4
    ) {
        if (!isMetaHost(host)) {
            return ipv4;
        }

        String key =
                host.toLowerCase(
                        Locale.ROOT
                );

        long now =
                System.currentTimeMillis();

        CacheEntry cached =
                CACHE.get(key);

        List<String> ipv6;

        if (cached != null
                && cached.expiresAt > now) {

            ipv6 =
                    new ArrayList<>(
                            cached.addresses
                    );

        } else {
            ipv6 =
                    resolveIpv6(
                            prefs,
                            host
                    );

            CACHE.put(
                    key,
                    new CacheEntry(
                            ipv6,
                            now + (
                                    ipv6.isEmpty()
                                            ? NEGATIVE_TTL_MS
                                            : POSITIVE_TTL_MS
                            )
                    )
            );
        }

        if (ipv6.isEmpty()) {
            return ipv4;
        }

        LinkedHashSet<String> out =
                new LinkedHashSet<>();

        out.addAll(ipv6);

        if (ipv4 != null) {
            out.addAll(ipv4);
        }

        DnsLog.addRaw(
                "META IPv6 RESCUE • "
                        + host
                        + " • "
                        + ipv6.size()
                        + " AAAA"
        );

        return new ArrayList<>(out);
    }

    public static void clearCache() {
        CACHE.clear();
    }

    private static List<String> resolveIpv6(
            SharedPreferences prefs,
            String host
    ) {
        LinkedHashSet<String> out =
                new LinkedHashSet<>();

        List<String> urls =
                FastDoh.candidateUrls(
                        prefs
                );

        int count =
                Math.min(
                        MAX_RESOLVERS,
                        urls.size()
                );

        for (int i = 0;
             i < count;
             i++) {

            String url =
                    urls.get(i);

            try {
                DohClient.Result result =
                        DohClient.query(
                                url,
                                DohClient.makeAaaaQuery(
                                        host
                                )
                        );

                if (!result.ok()) {
                    continue;
                }

                out.addAll(
                        DnsPacket.allIpv6Addresses(
                                result.body
                        )
                );

                if (out.size() >= 6) {
                    break;
                }

            } catch (Exception ignored) {
            }
        }

        return new ArrayList<>(out);
    }
}
