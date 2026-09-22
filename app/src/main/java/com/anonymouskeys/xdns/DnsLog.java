package com.anonymouskeys.xdns;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

public final class DnsLog {

    private static final int MAX_LOGS = 120;
    private static final ArrayDeque<String> LOGS = new ArrayDeque<>();

    private static final AtomicLong QUERIES = new AtomicLong();
    private static final AtomicLong OK = new AtomicLong();
    private static final AtomicLong FAIL = new AtomicLong();
    private static final AtomicLong TOTAL_LATENCY = new AtomicLong();
    private static final AtomicLong DNS_IN = new AtomicLong();
    private static final AtomicLong DNS_OUT = new AtomicLong();

    private static volatile long startedAt = 0;

    private DnsLog() {}

    public static synchronized void beginSession(String doh) {
        QUERIES.set(0);
        OK.set(0);
        FAIL.set(0);
        TOTAL_LATENCY.set(0);
        DNS_IN.set(0);
        DNS_OUT.set(0);
        LOGS.clear();
        startedAt = System.currentTimeMillis();
        addRaw("START • " + doh);
    }

    public static void queryReceived(int bytes) {
        QUERIES.incrementAndGet();
        DNS_IN.addAndGet(Math.max(0, bytes));
    }

    public static void success(String name, String type, String address,
                               long latencyMs, int responseBytes) {
        OK.incrementAndGet();
        TOTAL_LATENCY.addAndGet(Math.max(0, latencyMs));
        DNS_OUT.addAndGet(Math.max(0, responseBytes));
        addRaw(type + " • " + name + " → " + address + " • " + latencyMs + " ms");
    }

    public static void failure(String name, String type, String error, long latencyMs) {
        FAIL.incrementAndGet();
        TOTAL_LATENCY.addAndGet(Math.max(0, latencyMs));
        addRaw(type + " • " + name + " • ERROR: " + error + " • " + latencyMs + " ms");
    }

    public static synchronized void addRaw(String text) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        LOGS.addFirst(time + "  " + text);
        while (LOGS.size() > MAX_LOGS) {
            LOGS.removeLast();
        }
    }

    public static synchronized String getText() {
        if (LOGS.isEmpty()) return "No DNS queries yet.";
        StringBuilder out = new StringBuilder();
        for (String line : LOGS) {
            out.append(line).append('\n');
        }
        return out.toString();
    }

    public static synchronized void clear() {
        LOGS.clear();
    }

    public static String statsText() {
        long q = QUERIES.get();
        long ok = OK.get();
        long fail = FAIL.get();
        long completed = ok + fail;
        long avg = completed == 0 ? 0 : TOTAL_LATENCY.get() / completed;

        long uptimeSec = startedAt <= 0
                ? 0
                : Math.max(0, (System.currentTimeMillis() - startedAt) / 1000);

        return "Uptime: " + formatDuration(uptimeSec)
                + "\nDNS queries: " + q + "   OK: " + ok + "   Errors: " + fail
                + "\nAverage DoH: " + avg + " ms"
                + "\nDNS traffic: ↓ " + formatBytes(DNS_IN.get())
                + "   ↑ " + formatBytes(DNS_OUT.get());
    }

    private static String formatDuration(long seconds) {
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s);
    }

    public static String formatBytes(long value) {
        if (value < 1024) return value + " B";
        double kib = value / 1024.0;
        if (kib < 1024) return String.format(Locale.US, "%.1f KiB", kib);
        return String.format(Locale.US, "%.2f MiB", kib / 1024.0);
    }
}
