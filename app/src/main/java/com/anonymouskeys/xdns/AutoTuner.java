package com.anonymouskeys.xdns;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.util.ArrayList;
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
                    + "\nYouTube HTTPS: " + youtubeLatencyMs + " ms";
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

    static List<DpiStrategies.Preset> orderedStrategies(String selected, int ttl) {
        List<DpiStrategies.Preset> all = DpiStrategies.candidates(ttl);
        java.util.LinkedHashMap<String, DpiStrategies.Preset> ordered = new java.util.LinkedHashMap<>();
        for (String id : new String[]{selected, "maximum", "auto_compat"}) {
            for (DpiStrategies.Preset preset : all) {
                if (preset.id.equals(id)) ordered.put(preset.id, preset);
            }
        }
        for (DpiStrategies.Preset preset : all) ordered.put(preset.id, preset);
        return new ArrayList<>(ordered.values());
    }

    private static final long DNS_BUDGET_MS = 90_000;
    private static final long DPI_BUDGET_MS = 150_000;

    private static long now() { return System.nanoTime() / 1_000_000; }

    private static final class CheckedDns {
        final ResolverStore.Entry entry;
        final DohClient.Result result;
        CheckedDns(ResolverStore.Entry entry, DohClient.Result result) {
            this.entry = entry;
            this.result = result;
        }
    }

    private static Result runFresh(Context context, SharedPreferences prefs,
            int fakeTtl, Listener listener, TestNetwork network) throws Exception {
        DnsLog.beginSession("Fresh DNS + DPI test (bounded)");
        FastDoh.clearCache();
        RouteMemory.reset(prefs);
        ResolverStore.resetResults(prefs);
        prefs.edit().remove(XDnsVpnService.KEY_AUTO_PROFILE).apply();
        network.check();

        // Use the saved catalog: catalog downloads must not delay a network retest.
        List<ResolverStore.Entry> all = ResolverStore.all(prefs);
        List<ResolverStore.Entry> working = scanDns(all, prefs, listener, network);
        network.check();
        if (working.isEmpty()) return fail("No working DNS within 90 seconds; untested entries remain unknown");
        working.sort(Comparator.comparingLong(e -> e.latencyMs));

        List<DpiStrategies.Preset> strategies = orderedStrategies(
                prefs.getString(XDnsVpnService.KEY_DPI_STRATEGY, "maximum"), fakeTtl);
        String selectedUrl = prefs.getString(XDnsVpnService.KEY_DOH_URL, XDnsVpnService.DEFAULT_DOH);
        // Preserve preference only if it passed a fresh DNS test on this network.
        working.sort(Comparator.comparingInt(e -> selectedUrl.equals(e.url) ? 0 : 1));
        java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        long deadline = now() + DPI_BUDGET_MS;
        int resolverCount = Math.min(5, working.size());
        int attempt = 0;
        try {
            // Try each strategy across the best fresh DNS candidates before moving on.
            search:
            for (DpiStrategies.Preset strategy : strategies) {
                for (int r = 0; r < resolverCount; r++) {
                    network.check();
                    if (now() >= deadline) break search;
                    ResolverStore.Entry resolver = working.get(r);
                    String label = "DPI " + (++attempt) + "/" + (resolverCount * strategies.size())
                            + " • " + resolver.name + " + " + strategy.name;
                    DnsLog.addRaw(label);
                    DragonByeDpi dpi = new DragonByeDpi();
                    YoutubeProbe.Result probe = null;
                    try {
                        dpi.start(context, fakeTtl, strategy.id, true);
                        probe = probe(worker, network, deadline, listener, label, resolver.url);
                        if (probe.ok) {
                            // A second independent pass confirms all four HTTPS targets.
                            probe = probe(worker, network, deadline, listener,
                                    label + " • confirming 4/4", resolver.url);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw e;
                    } catch (Exception e) {
                        progress(listener, label + " • " + safeMessage(e));
                        probe = null;
                    } finally {
                        boolean interrupted = Thread.currentThread().isInterrupted();
                        dpi.stop();
                        if (interrupted) Thread.currentThread().interrupt();
                    }
                    network.check();
                    if (probe == null) continue;
                    if (!probe.ok) {
                        progress(listener, label + " • FAIL • " + probe.error);
                        continue;
                    }
                    prefs.edit()
                            .putString(XDnsVpnService.KEY_DOH_URL, resolver.url)
                            .putString(XDnsVpnService.KEY_DPI_STRATEGY, strategy.id)
                            .putBoolean(XDnsVpnService.KEY_FORCE_TCP, true)
                            .putString(XDnsVpnService.KEY_MODE, XDnsVpnService.MODE_DRAGON_DPI)
                            .putString(XDnsVpnService.KEY_AUTO_PROFILE,
                                    resolver.name + " + " + strategy.name + " • HTTPS 4/4 twice")
                            .apply();
                    network.check();
                    return new Result(true, resolver.name, resolver.url, resolver.latencyMs,
                            strategy.id, strategy.name, probe.latencyMs, null);
                }
            }
            return fail("No confirmed DNS + DPI pair within the test budget. This does not prove every pair fails.");
        } finally {
            worker.shutdownNow();
        }
    }

    private static List<ResolverStore.Entry> scanDns(List<ResolverStore.Entry> all,
            SharedPreferences prefs, Listener listener, TestNetwork network) throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        java.util.concurrent.CompletionService<CheckedDns> completed =
                new java.util.concurrent.ExecutorCompletionService<>(pool);
        java.util.Set<ProbeControl> controls = java.util.concurrent.ConcurrentHashMap.newKeySet();
        List<ResolverStore.Entry> working = new ArrayList<>();
        long deadline = now() + DNS_BUDGET_MS;
        int checked = 0;
        long nextProgress = 0;
        try {
            for (ResolverStore.Entry entry : all) {
                completed.submit(() -> {
                    try (ProbeControl control = new ProbeControl(15000)) {
                        controls.add(control);
                        try {
                            control.enter();
                            DohClient.Result result = DohClient.query(entry.url,
                                    DohClient.makeTestQuery("example.com"));
                            ProbeControl.check();
                            return new CheckedDns(entry, result);
                        } finally {
                            controls.remove(control);
                        }
                    } catch (java.io.IOException e) {
                        return new CheckedDns(entry, new DohClient.Result(null, 15000, -1, "", e.getMessage()));
                    }
                });
            }
            while (checked < all.size() && now() < deadline) {
                network.check();
                if (now() >= nextProgress) {
                    progress(listener, "DNS " + checked + "/" + all.size()
                            + " • working " + working.size() + " • remaining "
                            + Math.max(0, (deadline - now()) / 1000) + " s");
                    nextProgress = now() + 1000;
                }
                java.util.concurrent.Future<CheckedDns> future = completed.poll(200,
                        java.util.concurrent.TimeUnit.MILLISECONDS);
                if (future == null) continue;
                CheckedDns test = future.get();
                network.check();
                checked++;
                ResolverStore.saveResult(prefs, test.entry.url, test.entry.name, test.result);
                if (test.result.ok()) {
                    test.entry.latencyMs = test.result.latencyMs;
                    working.add(test.entry);
                }
            }
            progress(listener, "DNS finished: " + checked + "/" + all.size()
                    + " • working " + working.size() + " • untested " + (all.size() - checked));
            return working;
        } finally {
            pool.shutdownNow();
            for (ProbeControl control : controls) control.cancel();
        }
    }

    private static YoutubeProbe.Result probe(java.util.concurrent.ExecutorService worker,
            TestNetwork network, long deadline, Listener listener, String label,
            String url) throws Exception {
        long remaining = deadline - now();
        if (remaining <= 0) throw new java.util.concurrent.TimeoutException("DPI time limit");
        long probeDeadline = now() + Math.min(40_000, remaining);
        try (ProbeControl control = new ProbeControl(Math.min(40_000, remaining))) {
            java.util.concurrent.Future<YoutubeProbe.Result> future = worker.submit(() -> {
                try {
                    control.enter();
                    YoutubeProbe.Result result = YoutubeProbe.throughByeDpi(url);
                    ProbeControl.check();
                    return result;
                } finally {
                    control.close();
                }
            });
            long nextProgress = 0;
            try {
                while (now() < probeDeadline) {
                    network.check();
                    if (now() >= nextProgress) {
                        progress(listener, label + " • remaining "
                                + Math.max(0, (deadline - now()) / 1000) + " s");
                        nextProgress = now() + 1000;
                    }
                    try {
                        return future.get(200, java.util.concurrent.TimeUnit.MILLISECONDS);
                    } catch (java.util.concurrent.TimeoutException waiting) {
                        // Poll to keep cancellation and network changes responsive.
                    }
                }
                throw new java.util.concurrent.TimeoutException("DPI time limit");
            } finally {
                control.cancel();
                future.cancel(true);
            }
        }
    }

    private static void progress(
            Listener listener,
            String text
    ) {
        if (listener != null) {
            listener.onProgress(text);
        }

        // UI countdowns must not evict the actual per-host failure diagnostics.
        if (!text.contains(" • remaining ")) DnsLog.addRaw(text);
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
