package com.anonymouskeys.xdns;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class DragonByeDpi {

    public static final int PORT = 1080;

    private Process process;
    private Thread logThread;
    private String activeStrategy = "";

    public synchronized boolean isRunning() {
        return process != null && process.isAlive();
    }

    public synchronized String activeStrategy() {
        return activeStrategy;
    }

    public synchronized void start(
            Context context,
            int fakeTtl,
            String strategyId,
            boolean forceTcp
    ) throws Exception {
        if (isRunning()) return;

        File binary = new File(
                context.getApplicationInfo().nativeLibraryDir,
                "libciadpi.so"
        );

        if (!binary.isFile()) {
            throw new IllegalStateException("libciadpi.so missing");
        }

        DpiStrategies.Preset preset =
                DpiStrategies.find(strategyId, fakeTtl);

        List<String> command = new ArrayList<>(Arrays.asList(
                binary.getAbsolutePath(),
                "--ip", "127.0.0.1",
                "--port", String.valueOf(PORT),
                "--max-conn", "1024",
                "--timeout", "3",
                "--cache-ttl", "86400",
                "--auto-mode", "1",
                "--proto", "http,tls",
                "--pf", "80-443"
        ));

        if (forceTcp) {
            // ByeDPI SOCKS UDP is intentionally disabled here.
            // This makes QUIC/UDP fail fast so YouTube falls back to TLS/TCP,
            // where the selected DPI strategy is actually applied.
            command.add("--no-udp");
            DnsLog.addRaw(
                    "DPI • FORCE TCP enabled • SOCKS UDP/QUIC disabled"
            );
        }

        command.addAll(preset.args);

        DnsLog.addRaw("DPI • starting " + preset.name);

        Process created = new ProcessBuilder(command)
                .directory(context.getFilesDir())
                .redirectErrorStream(true)
                .start();

        process = created;
        activeStrategy = preset.id;

        logThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(created.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    DnsLog.addRaw("DPI • " + line);
                }
            } catch (Exception ignored) {
            }
        }, "xdns-ciadpi-log");

        logThread.setDaemon(true);
        logThread.start();

        // Do not probe the SOCKS port with a bare TCP connect here.
        // AUTO's first real connection performs a complete SOCKS5 handshake.
        Thread.sleep(250);

        if (!created.isAlive()) {
            stop();
            throw new IllegalStateException(
                    "ciadpi exited immediately for " + preset.name
            );
        }

        DnsLog.addRaw(
                "DPI • " + preset.name + " process alive; SOCKS probe next"
        );
    }

    public synchronized void stop() {
        Process current = process;
        process = null;
        activeStrategy = "";

        if (current != null) {
            try {
                current.destroy();
                if (!current.waitFor(700, TimeUnit.MILLISECONDS)) {
                    current.destroyForcibly();
                    current.waitFor(700, TimeUnit.MILLISECONDS);
                }
            } catch (Exception ignored) {
                try {
                    current.destroyForcibly();
                } catch (Exception ignoredAgain) {
                }
            }
        }

        if (logThread != null) {
            logThread.interrupt();
            logThread = null;
        }
    }


}
