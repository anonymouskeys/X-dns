package com.anonymouskeys.xdns;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.concurrent.CancellationException;
import java.util.concurrent.*;

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
        String services = "";
        int servicesOk;

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
            Listener listener,
            BooleanSupplier current,
            java.util.function.Predicate<Runnable> publish
    ) {
        // The foreground service exclusively owns engine start/stop and cancellation.
        final long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(4);
        Listener checkedListener = text -> {
            if (!current.getAsBoolean()) throw new CancellationException();
            if (listener != null) listener.onProgress(text);
        };
        return runChecked(context, prefs, fakeTtl, checkedListener, current, publish, deadline);
    }

    private static Result runChecked(Context context, SharedPreferences prefs, int fakeTtl,
                                     Listener listener, BooleanSupplier current,
                                     java.util.function.Predicate<Runnable> publish, long deadline) {
        DnsLog.beginSession("AUTO tuner");

        progress(
                listener,
                "AUTO • fresh test: forgetting old network measurements"
        );

        FastDoh.clearCache();
        InstagramRescue.clearCache();
        RouteMemory.resetForFreshAuto(prefs);
        ResolverStore.resetMeasurements(prefs);

        progress(listener, "AUTO • loading resolver database");

        // Use saved/built-in endpoints first; catalog refresh must not block recovery.
        ensureSomeResolversWork(prefs, listener);

        List<ResolverStore.Entry> working =
                ResolverStore.working(prefs);

        if (working.isEmpty()) {
            return fail("no working DoH resolver");
        }

        // Re-benchmark the current best five with 3 real DNS queries.
        int benchmarkCount = Math.min(5, working.size());

        for (int i = 0; i < benchmarkCount; i++) {
            ResolverStore.Entry entry = working.get(i);

            progress(
                    listener,
                    "AUTO • benchmark DoH "
                            + (i + 1) + "/" + benchmarkCount
                            + " • " + entry.name
            );

            benchmarkResolver(prefs, entry, listener);
            if (System.nanoTime() > deadline) return fail("AUTO budget exhausted during DoH checks");
        }

        working = ResolverStore.working(prefs);

        if (working.isEmpty()) {
            return fail("DoH candidates failed 3-probe benchmark");
        }

        int resolverCount = Math.min(5, working.size());

        List<DpiStrategies.Preset> strategies =
                DpiStrategies.candidates(fakeTtl);

        List<PairCandidate> winners = new ArrayList<>();

        search:
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
                if (System.nanoTime() > deadline) break search;
                DpiStrategies.Preset strategy =
                        strategies.get(s);

                progress(
                        listener,
                        "AUTO • " + resolver.name
                                + " + " + strategy.name
                                + " • " + (s + 1)
                                + "/" + strategies.size()
                );

                DragonByeDpi dpi = new DragonByeDpi();
                SocksDohBridge bridge = new SocksDohBridge();

                try {
                    dpi.start(
                            context,
                            fakeTtl,
                            strategy.id,
                            prefs.getBoolean(XDnsVpnService.KEY_FORCE_TCP, true)
                    );

                    RouteMemory.resetForFreshAuto(prefs);
                    bridge.start(prefs, resolver.url);
                    YoutubeProbe.Result probe = YoutubeProbe.throughBridge(
                            new String[]{"www.youtube.com", "youtubei.googleapis.com",
                                    "i.ytimg.com", "redirector.googlevideo.com"});

                    if (probe.ok) {
                        PairCandidate candidate = new PairCandidate(resolver, strategy, probe);
                        progress(listener, "AUTO • checking Instagram and TikTok via bridge");
                        YoutubeProbe.Result instagram = YoutubeProbe.throughBridge(
                                new String[]{"www.instagram.com", "i.instagram.com"});
                        progress(listener, "AUTO • checking TikTok via bridge");
                        YoutubeProbe.Result tiktok = YoutubeProbe.throughBridge(
                                new String[]{"www.tiktok.com"});
                        candidate.servicesOk = (instagram.ok ? 1 : 0) + (tiktok.ok ? 1 : 0);
                        candidate.services = "Instagram: " + (instagram.ok ? "HTTPS reachable" : instagram.error)
                                + " • TikTok: " + (tiktok.ok ? "HTTPS reachable" : tiktok.error);
                        winners.add(candidate);
                        progress(listener, "AUTO • " + candidate.services);

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

                        if (candidate.servicesOk == 2) break search;
                        // Keep the first working strategy for this resolver.
                        // Candidate order intentionally goes from simple to strong.
                        break;
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

                } catch (CancellationException e) {
                    throw e;
                } catch (Exception e) {
                    progress(
                            listener,
                            "AUTO • ✗ "
                                    + strategy.name
                                    + " • "
                                    + safeMessage(e)
                    );
                } finally {
                    bridge.stop();
                    dpi.stop();
                }
            }
        }

        if (winners.isEmpty()) {
            return fail("no DoH + DPI pair reached YouTube");
        }

        winners.sort(
                Comparator.comparingInt((PairCandidate p) -> -p.servicesOk).thenComparingLong(PairCandidate::score)
        );

        PairCandidate best = winners.get(0);

        progress(listener, "AUTO • saving verified profile");
        if (!current.getAsBoolean()) throw new CancellationException();
        if (!publish.test(() -> prefs.edit()
                .putString(
                        XDnsVpnService.KEY_DOH_URL,
                        best.resolver.url
                )
                .putString(
                        XDnsVpnService.KEY_DPI_STRATEGY,
                        best.strategy.id
                )
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
                                + " ms • " + best.services
                )
                .apply())) throw new CancellationException();

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

    private static void ensureSomeResolversWork(
            SharedPreferences prefs,
            Listener listener
    ) {
        List<ResolverStore.Entry> all = ResolverStore.all(prefs);
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CompletionService<ResolverStore.Entry> completed = new ExecutorCompletionService<>(pool);
        List<Future<ResolverStore.Entry>> futures = new ArrayList<>();
        try {
            // Workers never write preferences: cancelled old-network probes cannot pollute them.
            java.util.Map<String, DohClient.Result> results = new ConcurrentHashMap<>();
            int count = Math.min(24, all.size());
            for (int i = 0; i < count; i++) {
                ResolverStore.Entry entry = all.get(i);
                futures.add(completed.submit(() -> {
                    results.put(entry.url, DohClient.query(entry.url, DohClient.makeTestQuery("example.com")));
                    return entry;
                }));
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(75);
            int success = 0;
            for (int i = 0; i < count; i++) {
                progress(listener, "AUTO • testing current-network DoH endpoints • " + success + " working");
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                Future<ResolverStore.Entry> done = completed.poll(remaining, TimeUnit.NANOSECONDS);
                if (done == null) break;
                ResolverStore.Entry entry = done.get();
                progress(listener, "AUTO • DoH result • " + entry.name);
                DohClient.Result result = results.get(entry.url);
                ResolverStore.saveResult(prefs, entry.url, entry.name, result);
                if (result.ok() && ++success >= 5) break;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException();
        } catch (ExecutionException e) {
            throw new IllegalStateException("Resolver test failed", e);
        } finally {
            for (Future<?> f : futures) f.cancel(true);
            pool.shutdownNow();
        }
    }

    private static void benchmarkResolver(
            SharedPreferences prefs,
            ResolverStore.Entry entry,
            Listener listener
    ) {
        List<Long> latencies = new ArrayList<>();
        int success = 0;
        String method = "";
        String error = "";

        for (int i = 0; i < 3; i++) {
            progress(listener, "AUTO • stability check " + entry.name + " • " + (i + 1) + "/3");
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

        progress(listener, "AUTO • stability result " + entry.name);
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
        if (Thread.currentThread().isInterrupted()) throw new CancellationException();
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
