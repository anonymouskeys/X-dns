package com.anonymouskeys.xdns;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class AutoTuner {

    private AutoTuner() {}

    public interface Listener {
        void onProgress(String text);
    }

    public static final class Result {
        public final boolean ok;
        public final String dohName;
        public final String dohUrl;
        public final long dohLatencyMs;
        public final String strategyId;
        public final String strategyName;
        public final long youtubeLatencyMs;
        public final String error;

        Result(
                boolean ok,
                String dohName,
                String dohUrl,
                long dohLatencyMs,
                String strategyId,
                String strategyName,
                long youtubeLatencyMs,
                String error
        ) {
            this.ok = ok;
            this.dohName = dohName;
            this.dohUrl = dohUrl;
            this.dohLatencyMs = dohLatencyMs;
            this.strategyId = strategyId;
            this.strategyName = strategyName;
            this.youtubeLatencyMs = youtubeLatencyMs;
            this.error = error;
        }

        public String summary() {
            if (!ok) {
                return "AUTO failed: "
                        + (error == null ? "no working pair" : error);
            }

            return "DoH: " + dohName
                    + " • " + dohLatencyMs + " ms"
                    + "\nDPI: " + strategyName
                    + "\nYouTube: " + youtubeLatencyMs + " ms";
        }
    }

    private static final class PairCandidate {
        final ResolverStore.Entry resolver;
        final DpiStrategies.Preset strategy;
        final YoutubeProbe.Result probe;

        PairCandidate(
                ResolverStore.Entry resolver,
                DpiStrategies.Preset strategy,
                YoutubeProbe.Result probe
        ) {
            this.resolver = resolver;
            this.strategy = strategy;
            this.probe = probe;
        }

        long score() {
            return resolver.latencyMs + probe.latencyMs;
        }
    }

    public static Result run(
            Context context,
            SharedPreferences prefs,
            int fakeTtl,
            Listener listener
    ) {
        if (!BUSY.compareAndSet(false, true)) return fail("Full retest already running");
        try {
            return runExclusive(context, prefs, fakeTtl, listener);
        } finally {
            BUSY.set(false);
        }
    }

    private static final java.util.concurrent.atomic.AtomicBoolean BUSY =
            new java.util.concurrent.atomic.AtomicBoolean();

    private static Result runExclusive(Context context, SharedPreferences prefs,
            int fakeTtl, Listener listener) {
        if (XDnsVpnService.isRunning()) {
            Intent stop =
                    new Intent(
                            context,
                            XDnsVpnService.class
                    );

            stop.setAction(
                    XDnsVpnService.ACTION_STOP
            );

            context.startService(stop);

            long deadline =
                    System.currentTimeMillis()
                            + 10000;

            while (XDnsVpnService.isRunning()
                    && System.currentTimeMillis()
                    < deadline) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return fail("AUTO interrupted while stopping VPN");
                }
            }

            if (XDnsVpnService.isRunning()) {
                return fail("Could not stop current X-dns VPN");
            }
        }

        try (TestNetwork network = new TestNetwork(context)) {
            return runFresh(context, prefs, fakeTtl, listener, network);
        } catch (Exception e) {
            ResolverStore.resetResults(prefs);
            prefs.edit().remove(XDnsVpnService.KEY_AUTO_PROFILE).apply();
            return fail(safeMessage(e));
        }
    }

    private static Result runFresh(Context context, SharedPreferences prefs,
            int fakeTtl, Listener listener, TestNetwork network) throws Exception {
        DnsLog.beginSession("Full DNS + DPI retest");
        FastDoh.clearCache();
        RouteMemory.reset(prefs);
        ResolverStore.resetResults(prefs);
        prefs.edit().remove(XDnsVpnService.KEY_AUTO_PROFILE).apply();
        network.check();
        ensureCatalogSaved(prefs, listener);
        network.check();

        List<ResolverStore.Entry> all = ResolverStore.all(prefs);
        for (int i = 0; i < all.size(); i++) {
            network.check();
            ResolverStore.Entry entry = all.get(i);
            progress(listener, "FULL • DNS " + (i + 1) + "/" + all.size()
                    + " • " + entry.name);
            benchmarkResolver(prefs, entry);
            network.check();
        }
        List<ResolverStore.Entry> working = ResolverStore.working(prefs);
        if (working.isEmpty()) return fail("no working DoH resolver on this network");
        int resolverCount = working.size();

        List<DpiStrategies.Preset> strategies =
                DpiStrategies.candidates(fakeTtl);

        List<PairCandidate> winners = new ArrayList<>();

        for (int r = 0; r < resolverCount; r++) {
            ResolverStore.Entry resolver = working.get(r);

            progress(
                    listener,
                    "AUTO • DoH candidate "
                            + (r + 1) + "/" + resolverCount
                            + " • " + resolver.name
                            + " • " + resolver.latencyMs + " ms"
            );

            for (int s = 0; s < strategies.size(); s++) {
                DpiStrategies.Preset strategy =
                        strategies.get(s);

                progress(
                        listener,
                        "AUTO • " + resolver.name
                                + " + " + strategy.name
                                + " • " + (s + 1)
                                + "/" + strategies.size()
                );

                network.check();
                DragonByeDpi dpi = new DragonByeDpi();

                try {
                    dpi.start(
                            context,
                            fakeTtl,
                            strategy.id,
                            true
                    );

                    YoutubeProbe.Result probe =
                            YoutubeProbe.throughByeDpi(
                                    resolver.url
                            );

                    if (probe.ok) {
                        winners.add(
                                new PairCandidate(
                                        resolver,
                                        strategy,
                                        probe
                                )
                        );

                        progress(
                                listener,
                                "AUTO • ✓ "
                                        + resolver.name
                                        + " + "
                                        + strategy.name
                                        + " • YouTube stack "
                                        + probe.hostsOk
                                        + "/4 • "
                                        + probe.latencyMs
                                        + " ms"
                        );

                        // Full retest measures every preset, including later candidates.
                    } else {
                        progress(
                                listener,
                                "AUTO • ✗ "
                                        + strategy.name
                                        + " • "
                                        + (probe.error == null
                                        ? "failed"
                                        : probe.error)
                        );
                    }

                } catch (Exception e) {
                    progress(
                            listener,
                            "AUTO • ✗ "
                                    + strategy.name
                                    + " • "
                                    + safeMessage(e)
                    );
                } finally {
                    boolean interrupted = Thread.currentThread().isInterrupted();
                    dpi.stop();
                    if (interrupted) Thread.currentThread().interrupt();
                }
                network.check();
            }
        }

        if (winners.isEmpty()) {
            return fail("no DoH + DPI pair reached YouTube");
        }

        winners.sort(
                Comparator.comparingLong(PairCandidate::score)
        );

        network.check();
        PairCandidate best = winners.get(0);

        prefs.edit()
                .putString(
                        XDnsVpnService.KEY_DOH_URL,
                        best.resolver.url
                )
                .putString(
                        XDnsVpnService.KEY_DPI_STRATEGY,
                        best.strategy.id
                )
                .putBoolean(XDnsVpnService.KEY_FORCE_TCP, true)
                .putString(
                        XDnsVpnService.KEY_MODE,
                        XDnsVpnService.MODE_DRAGON_DPI
                )
                .putString(
                        XDnsVpnService.KEY_AUTO_PROFILE,
                        best.resolver.name
                                + " + "
                                + best.strategy.name
                                + " • "
                                + best.probe.latencyMs
                                + " ms"
                )
                .apply();

        network.check();
        return new Result(
                true,
                best.resolver.name,
                best.resolver.url,
                best.resolver.latencyMs,
                best.strategy.id,
                best.strategy.name,
                best.probe.latencyMs,
                null
        );
    }

    private static void ensureCatalogSaved(
            SharedPreferences prefs,
            Listener listener
    ) {
        if (!ResolverStore.discovered(prefs).isEmpty()) {
            return;
        }

        progress(listener, "AUTO • downloading public DoH catalog");

        try {
            List<String> urls =
                    DohCatalog.fetchPublicDohUrls();

            for (String url : urls) {
                ResolverStore.rememberDiscovered(
                        prefs,
                        url
                );
            }

            progress(
                    listener,
                    "AUTO • saved "
                            + urls.size()
                            + " public DoH endpoints"
            );

        } catch (Exception e) {
            progress(
                    listener,
                    "AUTO • catalog unavailable: "
                            + safeMessage(e)
            );
        }
    }

    private static void benchmarkResolver(
            SharedPreferences prefs,
            ResolverStore.Entry entry
    ) {
        List<Long> latencies = new ArrayList<>();
        int success = 0;
        String method = "";
        String error = "";

        for (int i = 0; i < 3; i++) {
            DohClient.Result result =
                    DohClient.query(
                            entry.url,
                            DohClient.makeTestQuery("example.com")
                    );

            if (result.ok()) {
                success++;
                latencies.add(result.latencyMs);
                method = result.method == null
                        ? method
                        : result.method;
            } else {
                error = result.error == null
                        ? "failed"
                        : result.error;
            }
        }

        long median = 0;

        if (!latencies.isEmpty()) {
            Collections.sort(latencies);
            median =
                    latencies.get(latencies.size() / 2);
        }

        ResolverStore.saveBenchmark(
                prefs,
                entry.url,
                entry.name,
                3,
                success,
                median,
                method,
                error
        );
    }

    private static void progress(
            Listener listener,
            String text
    ) {
        if (listener != null) {
            listener.onProgress(text);
        }

        DnsLog.addRaw(text);
    }

    private static Result fail(String error) {
        return new Result(
                false,
                "",
                "",
                0,
                "",
                "",
                0,
                error
        );
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();

        return message == null || message.trim().isEmpty()
                ? e.getClass().getSimpleName()
                : message;
    }
}
