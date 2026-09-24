package com.anonymouskeys.xdns;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Scoped cancellation for test HTTP calls and SOCKS/TLS sockets only. */
final class ProbeControl implements AutoCloseable {
    private static final ThreadLocal<ProbeControl> CURRENT = new ThreadLocal<>();
    private static final ScheduledThreadPoolExecutor TIMER = new ScheduledThreadPoolExecutor(1, r -> {
        Thread thread = new Thread(r, "xdns-probe-deadline");
        thread.setDaemon(true);
        return thread;
    });
    static { TIMER.setRemoveOnCancelPolicy(true); }
    private final List<Closeable> resources = new ArrayList<>();
    private final ScheduledFuture<?> timer;
    private volatile boolean cancelled;

    ProbeControl(long timeoutMs) {
        timer = TIMER.schedule(this::cancel, timeoutMs, TimeUnit.MILLISECONDS);
    }

    void enter() throws IOException {
        CURRENT.set(this);
        check();
    }

    static void check() throws IOException {
        ProbeControl scope = CURRENT.get();
        if (scope != null && (scope.cancelled || Thread.currentThread().isInterrupted())) {
            throw new IOException("Test cancelled or time limit reached");
        }
    }

    static <T extends Closeable> T track(T resource) throws IOException {
        ProbeControl scope = CURRENT.get();
        if (scope != null) {
            synchronized (scope) {
                if (scope.cancelled || Thread.currentThread().isInterrupted()) {
                    resource.close();
                    throw new IOException("Test cancelled or time limit reached");
                }
                scope.resources.add(resource);
            }
        }
        return resource;
    }

    void cancel() {
        List<Closeable> pending;
        synchronized (this) {
            cancelled = true;
            pending = new ArrayList<>(resources);
            resources.clear();
        }
        for (Closeable resource : pending) {
            try { resource.close(); } catch (Exception ignored) { }
        }
    }

    @Override public void close() {
        timer.cancel(false);
        cancel();
        if (CURRENT.get() == this) CURRENT.remove();
    }
}
