package com.anonymouskeys.xdns;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
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

    public synchronized void start(Context context, int fakeTtl, String strategyId)
            throws Exception {
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

        if (!waitForPort(created, 3500)) {
            stop();
            throw new IllegalStateException(
                    "ciadpi did not open 127.0.0.1:" + PORT
            );
        }

        DnsLog.addRaw(
                "DPI • " + preset.name + " ready on 127.0.0.1:" + PORT
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

    private static boolean waitForPort(Process process, long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;

        while (System.currentTimeMillis() < end) {
            if (!process.isAlive()) return false;

            try (Socket socket = new Socket()) {
                socket.connect(
                        new InetSocketAddress("127.0.0.1", PORT),
                        180
                );
                return true;
            } catch (Exception ignored) {
                try {
                    Thread.sleep(80);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }

        return false;
    }
}
