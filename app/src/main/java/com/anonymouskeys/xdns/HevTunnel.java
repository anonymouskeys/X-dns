package com.anonymouskeys.xdns;

import android.content.Context;
import android.os.ParcelFileDescriptor;

import com.v2ray.ang.service.TProxyService;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public final class HevTunnel {

    public static final int MTU = 1420;
    public static final String TUN_IPV4 = "198.18.0.1";
    public static final int TUN_PREFIX = 15;
    public static final String MAP_DNS = "198.18.0.2";

    private File configFile;

    public void start(
            Context context,
            ParcelFileDescriptor tun
    ) throws Exception {

        String yaml =
                "tunnel:\n" +
                "  mtu: " + MTU + "\n" +
                "  ipv4: " + TUN_IPV4 + "\n" +
                "  icmp: 'off'\n" +
                "socks5:\n" +
                "  address: 127.0.0.1\n" +
                "  port: " + SocksDohBridge.PORT + "\n" +
                "  udp: 'udp'\n" +
                "mapdns:\n" +
                "  address: " + MAP_DNS + "\n" +
                "  port: 53\n" +
                "  network: 100.64.0.0\n" +
                "  netmask: 255.192.0.0\n" +
                "  cache-size: 10000\n" +
                "misc:\n" +
                "  connect-timeout: 10000\n" +
                "  tcp-read-write-timeout: 300000\n" +
                "  udp-read-write-timeout: 60000\n" +
                "  log-level: warn\n";

        configFile = new File(
                context.getFilesDir(),
                "xdns-hev.yaml"
        );

        try (FileOutputStream out =
                     new FileOutputStream(
                             configFile,
                             false
                     )) {

            out.write(
                    yaml.getBytes(
                            StandardCharsets.UTF_8
                    )
            );

            out.flush();
        }

        DnsLog.addRaw(
                "DPI • HEV start via DoH bridge • "
                        + TUN_IPV4 + "/" + TUN_PREFIX
                        + " • MTU " + MTU
                        + " • mapdns " + MAP_DNS
        );

        boolean started =
                TProxyService.TProxyStartService(
                        configFile.getAbsolutePath(),
                        tun.getFd()
                );

        if (!started) {
            throw new IllegalStateException(
                    "HEV JNI refused to start"
            );
        }

        boolean alive = false;

        for (int i = 0; i < 20; i++) {
            Thread.sleep(50);

            if (TProxyService.TProxyIsRunning()) {
                alive = true;
                break;
            }
        }

        if (!alive) {
            try {
                TProxyService.TProxyStopService();
            } catch (Throwable ignored) {
            }

            throw new IllegalStateException(
                    "HEV worker exited during startup"
            );
        }

        DnsLog.addRaw(
                "DPI • HEV native worker running ✓"
        );
    }

    public void stop() {
        try {
            if (TProxyService.TProxyIsRunning()) {
                TProxyService.TProxyStopService();
            }
        } catch (Throwable ignored) {
        }

        if (configFile != null) {
            try {
                configFile.delete();
            } catch (Exception ignored) {
            }

            configFile = null;
        }
    }

    public static boolean isRunning() {
        try {
            return TProxyService.TProxyIsRunning();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static long[] stats() {
        try {
            if (!TProxyService.TProxyIsRunning()) {
                return new long[]{0, 0, 0, 0};
            }

            long[] value =
                    TProxyService.TProxyGetStats();

            return value == null
                    ? new long[]{0, 0, 0, 0}
                    : value;

        } catch (Throwable ignored) {
            return new long[]{0, 0, 0, 0};
        }
    }
}
