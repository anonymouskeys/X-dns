package com.anonymouskeys.xdns;

import android.content.Context;
import android.os.ParcelFileDescriptor;

import com.v2ray.ang.service.TProxyService;

import java.io.File;

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
                "misc:\n" +
                "  connect-timeout: 10000\n" +
                "  tcp-read-write-timeout: 300000\n" +
                "  udp-read-write-timeout: 60000\n" +
                "  log-level: warn\n";

        configFile = new File(context.getFilesDir(), "xdns-hev.yaml");
        java.nio.file.Files.writeString(
                configFile.toPath(),
                yaml,
                java.nio.charset.StandardCharsets.UTF_8
        );

        boolean started = TProxyService.TProxyStartService(
                configFile.getAbsolutePath(),
                tun.getFd()
        );

        if (!started) {
            throw new IllegalStateException("hev-socks5-tunnel refused to start");
        }

        DnsLog.addRaw("DPI • hev-socks5-tunnel started");
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

        DnsLog.addRaw("DPI • hev-socks5-tunnel stopped");
    }

    public static long[] stats() {
        try {
            long[] value = TProxyService.TProxyGetStats();
            return value == null ? new long[]{0, 0, 0, 0} : value;
        } catch (Throwable ignored) {
            return new long[]{0, 0, 0, 0};
        }
    }
}
