package com.anonymouskeys.xdns;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class XDnsVpnService extends VpnService {

    public static final String PREFS = "xdns";
    public static final String KEY_DOH_URL = "doh_url";
    public static final String KEY_EXCLUDED_APPS = "excluded_apps";

    public static final String DEFAULT_DOH = "https://doh.xfinity.com/dns-query";

    private static final String CHANNEL_ID = "xdns_vpn";
    private static final int NOTIFICATION_ID = 100;

    private static volatile boolean running = false;

    private final Object outputLock = new Object();
    private final ExecutorService dohPool = Executors.newFixedThreadPool(4);

    private ParcelFileDescriptor vpnInterface;
    private FileInputStream vpnInput;
    private FileOutputStream vpnOutput;
    private Thread tunThread;

    public static boolean isRunning() {
        return running;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        Notification notification = createNotification();

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            );
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (vpnInterface == null) {
            try {
                startDnsTunnel();
            } catch (Exception e) {
                DnsLog.addRaw("VPN ERROR • " + e.getMessage());
                stopSelf();
                return START_NOT_STICKY;
            }
        }

        return START_STICKY;
    }

    private void startDnsTunnel() throws Exception {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String dohUrl = prefs.getString(KEY_DOH_URL, DEFAULT_DOH);

        Builder builder = new Builder()
                .setSession("X-dns")
                .setMtu(8500)
                .addAddress("10.253.0.2", 32)
                .addRoute("10.253.0.1", 32)
                .addDnsServer("10.253.0.1");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false);
        }

        // Critical: the X-dns process itself must use the underlying network,
        // otherwise resolving the DoH hostname would recurse back into X-dns.
        try {
            builder.addDisallowedApplication(getPackageName());
        } catch (Exception ignored) {
        }

        Set<String> excluded = new HashSet<>(
                prefs.getStringSet(KEY_EXCLUDED_APPS, new HashSet<>())
        );

        for (String packageName : excluded) {
            if (packageName == null || packageName.equals(getPackageName())) continue;
            try {
                builder.addDisallowedApplication(packageName);
            } catch (Exception ignored) {
            }
        }

        vpnInterface = builder.establish();
        if (vpnInterface == null) {
            throw new IllegalStateException("Android refused to create TUN interface");
        }

        vpnInput = new FileInputStream(vpnInterface.getFileDescriptor());
        vpnOutput = new FileOutputStream(vpnInterface.getFileDescriptor());

        running = true;
        DnsLog.beginSession(dohUrl);

        tunThread = new Thread(this::tunLoop, "xdns-tun");
        tunThread.start();
    }

    private void tunLoop() {
        byte[] buffer = new byte[65535];

        try {
            while (running && !Thread.currentThread().isInterrupted()) {
                int length = vpnInput.read(buffer);
                if (length <= 0) continue;

                byte[] packet = java.util.Arrays.copyOf(buffer, length);
                DnsPacket.Request request = DnsPacket.parseIpv4UdpDns(packet, length);

                if (request == null) continue;

                DnsLog.queryReceived(length);
                dohPool.submit(() -> handleDns(request));
            }
        } catch (Exception e) {
            if (running) {
                DnsLog.addRaw("TUN ERROR • " + safeMessage(e));
            }
        }
    }

    private void handleDns(DnsPacket.Request request) {
        String name = DnsPacket.queryName(request.dns);
        String type = DnsPacket.queryType(request.dns);

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String dohUrl = prefs.getString(KEY_DOH_URL, DEFAULT_DOH);

        DohClient.Result result = DohClient.query(dohUrl, request.dns);

        try {
            if (result.ok()) {
                String address = DnsPacket.firstAddress(result.body);
                byte[] response = DnsPacket.buildIpv4UdpResponse(request, result.body);
                writePacket(response);

                DnsLog.success(
                        name,
                        type,
                        address,
                        result.latencyMs,
                        response.length
                );
            } else {
                byte[] servFail = DnsPacket.makeServFail(request.dns);
                byte[] response = DnsPacket.buildIpv4UdpResponse(request, servFail);
                writePacket(response);

                DnsLog.failure(
                        name,
                        type,
                        result.error == null ? "DoH failed" : result.error,
                        result.latencyMs
                );
            }
        } catch (Exception e) {
            DnsLog.failure(name, type, safeMessage(e), result.latencyMs);
        }
    }

    private void writePacket(byte[] packet) throws IOException {
        synchronized (outputLock) {
            if (vpnOutput != null) {
                vpnOutput.write(packet);
                vpnOutput.flush();
            }
        }
    }

    @Override
    public void onRevoke() {
        stopSelf();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        running = false;

        if (tunThread != null) {
            tunThread.interrupt();
            tunThread = null;
        }

        dohPool.shutdownNow();

        closeQuietly(vpnInput);
        closeQuietly(vpnOutput);
        vpnInput = null;
        vpnOutput = null;

        if (vpnInterface != null) {
            try {
                vpnInterface.close();
            } catch (IOException ignored) {
            }
            vpnInterface = null;
        }

        DnsLog.addRaw("STOP");
        super.onDestroy();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "X-dns VPN",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("DNS-over-HTTPS local VPN");

            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    private Notification createNotification() {
        Intent openApp = new Intent(this, MainActivity.class);

        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        return builder
                .setSmallIcon(R.drawable.ic_vpn)
                .setContentTitle("X-dns")
                .setContentText("DNS-over-HTTPS is active")
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.trim().isEmpty()
                ? e.getClass().getSimpleName()
                : message;
    }
}
