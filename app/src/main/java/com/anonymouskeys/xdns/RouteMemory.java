package com.anonymouskeys.xdns;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class RouteMemory {

    private static final String KEY_GOOD = "learned_routes_v1";
    private static final long GOOD_TTL = 12L * 60L * 60L * 1000L;
    private static final long BAD_TTL = 3L * 60L * 1000L;

    private static final ConcurrentHashMap<String, Good> GOOD =
            new ConcurrentHashMap<>();

    private static final ConcurrentHashMap<String, Long> BAD =
            new ConcurrentHashMap<>();

    private static volatile boolean loaded;

    private RouteMemory() {}

    private static final class Good {
        final String host;
        final int port;
        final String ip;
        final long at;

        Good(String host, int port, String ip, long at) {
            this.host = host;
            this.port = port;
            this.ip = ip;
            this.at = at;
        }
    }

    public static List<String> prioritize(
            SharedPreferences prefs,
            String host,
            int port,
            List<String> candidates
    ) {
        load(prefs);

        LinkedHashSet<String> out = new LinkedHashSet<>();
        String preferred = preferred(prefs, host, port);

        if (preferred != null && !isBad(host, port, preferred)) {
            out.add(preferred);
        }

        if (candidates != null) {
            for (String ip : candidates) {
                if (ip != null
                        && !ip.isEmpty()
                        && !isBad(host, port, ip)) {
                    out.add(ip);
                }
            }
        }

        if (out.isEmpty() && candidates != null) {
            out.addAll(candidates);
        }

        return new ArrayList<>(out);
    }

    public static String preferred(
            SharedPreferences prefs,
            String host,
            int port
    ) {
        load(prefs);

        String key = routeKey(host, port);
        Good good = GOOD.get(key);

        if (good == null) return null;

        if (System.currentTimeMillis() - good.at > GOOD_TTL) {
            GOOD.remove(key);
            persist(prefs);
            return null;
        }

        return good.ip;
    }

    public static boolean success(
            SharedPreferences prefs,
            String host,
            int port,
            String ip
    ) {
        load(prefs);

        String key = routeKey(host, port);
        Good old = GOOD.get(key);
        boolean changed = old == null || !old.ip.equals(ip);

        GOOD.put(
                key,
                new Good(
                        host,
                        port,
                        ip,
                        System.currentTimeMillis()
                )
        );

        BAD.remove(badKey(host, port, ip));

        if (changed) {
            persist(prefs);
        }

        markHealth(prefs, host, true);
        return changed;
    }

    public static void failure(
            SharedPreferences prefs,
            String host,
            int port,
            String ip
    ) {
        load(prefs);

        BAD.put(
                badKey(host, port, ip),
                System.currentTimeMillis() + BAD_TTL
        );

        String key = routeKey(host, port);
        Good good = GOOD.get(key);

        if (good != null && good.ip.equals(ip)) {
            GOOD.remove(key);
            persist(prefs);
        }
    }

    public static void routeFailed(
            SharedPreferences prefs,
            String host
    ) {
        markHealth(prefs, host, false);
    }

    public static String healthText(
            SharedPreferences prefs
    ) {
        load(prefs);

        return "Learned routes: " + GOOD.size()
                + "\nYouTube " + state(prefs, "youtube")
                + "   Instagram " + state(prefs, "instagram")
                + "   TikTok " + state(prefs, "tiktok");
    }

    private static String state(
            SharedPreferences prefs,
            String service
    ) {
        long now = System.currentTimeMillis();
        long ok = prefs.getLong("health_ok_" + service, 0);
        long fail = prefs.getLong("health_fail_" + service, 0);

        if (ok > 0 && now - ok < 5L * 60L * 1000L && ok >= fail) {
            return "✓";
        }

        if (fail > 0 && now - fail < 90_000L && fail > ok) {
            return "✗";
        }

        return "…";
    }

    private static void markHealth(
            SharedPreferences prefs,
            String host,
            boolean ok
    ) {
        String service = service(host);
        if (service == null) return;

        prefs.edit()
                .putLong(
                        (ok ? "health_ok_" : "health_fail_") + service,
                        System.currentTimeMillis()
                )
                .apply();
    }

    private static String service(String host) {
        if (host == null) return null;

        String h = host.toLowerCase(Locale.ROOT);

        if (h.contains("youtube")
                || h.endsWith("googlevideo.com")
                || h.endsWith("ytimg.com")) {
            return "youtube";
        }

        if (h.contains("instagram")
                || h.endsWith("facebook.com")
                || h.endsWith("fbcdn.net")) {
            return "instagram";
        }

        if (h.contains("tiktok")
                || h.contains("byteoversea")
                || h.contains("ibytedtos")
                || h.contains("musical.ly")) {
            return "tiktok";
        }

        return null;
    }

    private static boolean isBad(
            String host,
            int port,
            String ip
    ) {
        String key = badKey(host, port, ip);
        Long until = BAD.get(key);

        if (until == null) return false;

        if (until <= System.currentTimeMillis()) {
            BAD.remove(key);
            return false;
        }

        return true;
    }

    private static synchronized void load(SharedPreferences prefs) {
        if (loaded) return;

        Set<String> saved =
                prefs.getStringSet(KEY_GOOD, new LinkedHashSet<>());

        long now = System.currentTimeMillis();

        for (String line : saved) {
            if (line == null) continue;

            String[] parts = line.split("\\|", -1);
            if (parts.length != 4) continue;

            try {
                String host = parts[0];
                int port = Integer.parseInt(parts[1]);
                String ip = parts[2];
                long at = Long.parseLong(parts[3]);

                if (now - at <= GOOD_TTL) {
                    GOOD.put(
                            routeKey(host, port),
                            new Good(host, port, ip, at)
                    );
                }
            } catch (Exception ignored) {
            }
        }

        loaded = true;
    }

    private static synchronized void persist(SharedPreferences prefs) {
        LinkedHashSet<String> saved = new LinkedHashSet<>();
        long now = System.currentTimeMillis();

        for (Good good : GOOD.values()) {
            if (now - good.at <= GOOD_TTL) {
                saved.add(
                        good.host + "|"
                                + good.port + "|"
                                + good.ip + "|"
                                + good.at
                );
            }
        }

        prefs.edit()
                .putStringSet(KEY_GOOD, saved)
                .apply();
    }

    private static String routeKey(String host, int port) {
        return (host == null ? "" : host.toLowerCase(Locale.ROOT))
                + ":" + port;
    }

    private static String badKey(String host, int port, String ip) {
        return routeKey(host, port) + "|" + ip;
    }
}
