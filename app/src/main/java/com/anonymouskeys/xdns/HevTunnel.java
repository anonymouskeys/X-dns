package com.anonymouskeys.xdns;

import android.content.Context;
import android.os.ParcelFileDescriptor;

import com.v2ray.ang.service.TProxyService;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public final class HevTunnel {

    private File configFile;

    public void start(Context context, ParcelFileDescriptor tun) throws Exception {
        String yaml =
                "tunnel:\n" +
                "  mtu: 8500\n" +
                "  ipv4: 10.10.10.10\n" +
                "socks5:\n" +
                "  address: 127.0.0.1\n" +
                "  port: " + DragonByeDpi.PORT + "\n" +
                "  udp: 'udp'\n" +
                "mapdns:\n" +
                "  address: 198.18.0.2\n" +
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
                     new FileOutputStream(configFile, false)) {
            out.write(yaml.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        boolean started = TProxyService.TProxyStartService(
                configFile.getAbsolutePath(),
                tun.getFd()
        );

        if (!started) {
            throw new IllegalStateException(
                    "hev-socks5-tunnel refused to start"
            );
        }

        DnsLog.addRaw(
                "DPI • hev started; mapdns 198.18.0.2:53"
        );
    }

    public void stop() {
        try {
            TProxyService.TProxyStopService();
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

    public static long[] stats() {
        try {
            long[] value = TProxyService.TProxyGetStats();
            return value == null
                    ? new long[]{0, 0, 0, 0}
                    : value;
        } catch (Throwable ignored) {
            return new long[]{0, 0, 0, 0};
        }
    }
}
