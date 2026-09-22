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
    public static final String KEY_MODE = "vpn_mode";
    public static final String KEY_DPI_TTL = "dpi_fake_ttl";
    public static final String KEY_DPI_STRATEGY = "dpi_strategy";
    public static final String KEY_FORCE_TCP = "force_tcp";
    public static final String KEY_AUTO_PROFILE = "auto_profile";
    public static final String KEY_LAST_START_STAGE = "last_start_stage";

    public static final String MODE_DOH = "doh";
    public static final String MODE_DRAGON_DPI = "dragon_dpi";

    public static final String ACTION_START =
            "com.anonymouskeys.xdns.START";
    public static final String ACTION_STOP =
            "com.anonymouskeys.xdns.STOP";

    public static final String DEFAULT_DOH =
            "https://doh.xfinity.com/dns-query";

    private static final String CHANNEL_ID = "xdns_vpn";
    private static final int NOTIFICATION_ID = 100;

    private static volatile boolean running = false;
    private static volatile String runningMode = MODE_DOH;
    private static volatile String runningStrategy = "";

    private final Object outputLock = new Object();

    private ExecutorService dohPool;
    private ParcelFileDescriptor vpnInterface;
    private FileInputStream vpnInput;
    private FileOutputStream vpnOutput;
    private Thread tunThread;

    private DragonByeDpi byeDpi;
    private SocksDohBridge dohBridge;
    private HevTunnel hevTunnel;

    public static boolean isRunning() {
        return running;
    }

    public static String runningMode() {
        return runningMode;
    }

    public static String runningStrategy() {
        return runningStrategy;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        dohPool = Executors.newFixedThreadPool(4);
        byeDpi = new DragonByeDpi();
        dohBridge = new SocksDohBridge();
        hevTunnel = new HevTunnel();

        createNotificationChannel();
    }

    @Override
    public int onStartCommand(
            Intent intent,
            int flags,
            int startId
    ) {
        String action =
                intent == null
                        ? ACTION_START
                        : intent.getAction();

        if (ACTION_STOP.equals(action)) {
            stopNow();
            return START_NOT_STICKY;
        }

        if (vpnInterface != null) {
            return START_STICKY;
        }

        try {
            startForegroundCompat(
                    createNotification()
            );

            SharedPreferences prefs =
                    getSharedPreferences(
                            PREFS,
                            MODE_PRIVATE
                    );

            String mode = prefs.getString(
                    KEY_MODE,
                    MODE_DOH
            );

            if (MODE_DRAGON_DPI.equals(mode)) {
                startDragonDpi(prefs);
            } else {
                startDnsOnly(prefs);
            }

            return START_STICKY;

        } catch (Throwable e) {
            DnsLog.addRaw(
                    "VPN ERROR • " + safeMessage(e)
            );

            running = false;
            runningStrategy = "";

            stopForegroundCompat();
            stopSelf();

            return START_NOT_STICKY;
        }
    }

    private void startDnsOnly(
            SharedPreferences prefs
    ) throws Exception {

        String dohUrl = prefs.getString(
                KEY_DOH_URL,
                DEFAULT_DOH
        );

        Builder builder = baseBuilder()
                .setSession("X-dns • DoH")
                .setMtu(8500)
                .addAddress("10.253.0.2", 32)
                .addRoute("10.253.0.1", 32)
                .addDnsServer("10.253.0.1");

        applyExclusions(builder, prefs);

        vpnInterface = builder.establish();

        if (vpnInterface == null) {
            throw new IllegalStateException(
                    "Android refused DoH TUN"
            );
        }

        vpnInput = new FileInputStream(
                vpnInterface.getFileDescriptor()
        );

        vpnOutput = new FileOutputStream(
                vpnInterface.getFileDescriptor()
        );

        runningMode = MODE_DOH;
        runningStrategy = "";
        running = true;

        DnsLog.beginSession(
                "DoH • " + dohUrl
        );

        tunThread = new Thread(
                this::tunLoop,
                "xdns-doh-tun"
        );

        tunThread.start();
    }

    private void startDragonDpi(
            SharedPreferences prefs
    ) throws Exception {

        int ttl;

        try {
            ttl = Integer.parseInt(
                    prefs.getString(
                            KEY_DPI_TTL,
                            "8"
                    )
            );
        } catch (Exception e) {
            ttl = 8;
        }

        String strategyId =
                prefs.getString(
                        KEY_DPI_STRATEGY,
                        "maximum"
                );

        DpiStrategies.Preset preset =
                DpiStrategies.find(
                        strategyId,
                        ttl
                );

        DnsLog.beginSession(
                "Dragon DPI • " + preset.name
        );

        prefs.edit()
                .putString(
                        KEY_LAST_START_STAGE,
                        "1/4 starting ciadpi: " + preset.name
                )
                .apply();

        boolean forceTcp =
                prefs.getBoolean(
                        KEY_FORCE_TCP,
                        true
                );

        byeDpi.start(
                this,
                ttl,
                strategyId,
                true
        );

        // HEV sends SOCKS domain requests here. The bridge resolves those
        // domains through the selected/fast DoH pool, then forwards the
        // connection to ciadpi by IP. This keeps ISP/system DNS out of the
        // Dragon path.
        dohBridge.start(prefs);

        prefs.edit()
                .putString(
                        KEY_LAST_START_STAGE,
                        "2/4 ciadpi OK; establishing Android TUN"
                )
                .apply();

        Builder builder = baseBuilder()
                .setSession(
                        "X-dns • " + preset.name
                )
                .setMtu(HevTunnel.MTU)
                .addAddress(
                        HevTunnel.TUN_IPV4,
                        HevTunnel.TUN_PREFIX
                )
                .addRoute("0.0.0.0", 0)
                .addDnsServer(
                        HevTunnel.MAP_DNS
                );

        applyExclusions(builder, prefs);

        vpnInterface = builder.establish();

        if (vpnInterface == null) {
            dohBridge.stop();
            byeDpi.stop();

            throw new IllegalStateException(
                    "Android refused full DPI TUN"
            );
        }

        prefs.edit()
                .putString(
                        KEY_LAST_START_STAGE,
                        "3/4 Android TUN OK; starting HEV"
                )
                .apply();

        try {
            hevTunnel.start(
                    this,
                    vpnInterface
            );
        } catch (Throwable e) {
            closeVpn();
            dohBridge.stop();
            byeDpi.stop();

            prefs.edit()
                    .putString(
                            KEY_LAST_START_STAGE,
                            "HEV startup failed: " + safeMessage(e)
                    )
                    .apply();

            throw e;
        }

        runningMode = MODE_DRAGON_DPI;
        runningStrategy = preset.name;
        running = true;

        prefs.edit()
                .putString(
                        KEY_LAST_START_STAGE,
                        "4/4 running: " + preset.name
                )
                .apply();

        DnsLog.addRaw(
                "DPI • full route active ✓ • "
                        + preset.name
        );
    }

    private Builder baseBuilder() {
        Builder builder = new Builder();

        if (Build.VERSION.SDK_INT
                >= Build.VERSION_CODES.Q) {
            builder.setMetered(false);
        }

        return builder;
    }

    private void applyExclusions(
            Builder builder,
            SharedPreferences prefs
    ) {
        try {
            // Our DoH and ciadpi sockets must use the underlying network.
            builder.addDisallowedApplication(
                    getPackageName()
            );
        } catch (Exception ignored) {
        }

        Set<String> excluded =
                new HashSet<>(
                        prefs.getStringSet(
                                KEY_EXCLUDED_APPS,
                                new HashSet<>()
                        )
                );

        for (String packageName : excluded) {
            if (packageName == null
                    || packageName.equals(
                    getPackageName()
            )) {
                continue;
            }

            try {
                builder.addDisallowedApplication(
                        packageName
                );
            } catch (Exception ignored) {
            }
        }
    }

    private void tunLoop() {
        byte[] buffer = new byte[65535];

        try {
            while (running
                    && MODE_DOH.equals(runningMode)
                    && !Thread.currentThread()
                    .isInterrupted()) {

                int length =
                        vpnInput.read(buffer);

                if (length <= 0) continue;

                byte[] packet =
                        java.util.Arrays.copyOf(
                                buffer,
                                length
                        );

                DnsPacket.Request request =
                        DnsPacket.parseIpv4UdpDns(
                                packet,
                                length
                        );

                if (request == null) continue;

                DnsLog.queryReceived(length);

                ExecutorService pool = dohPool;

                if (pool != null
                        && !pool.isShutdown()) {
                    pool.submit(
                            () -> handleDns(request)
                    );
                }
            }

        } catch (Exception e) {
            if (running
                    && MODE_DOH.equals(
                    runningMode
            )) {
                DnsLog.addRaw(
                        "TUN ERROR • "
                                + safeMessage(e)
                );
            }
        }
    }

    private void handleDns(
            DnsPacket.Request request
    ) {
        String name =
                DnsPacket.queryName(request.dns);

        String type =
                DnsPacket.queryType(request.dns);

        String dohUrl =
                getSharedPreferences(
                        PREFS,
                        MODE_PRIVATE
                ).getString(
                        KEY_DOH_URL,
                        DEFAULT_DOH
                );

        FastDoh.RaceResult raced =
                FastDoh.query(
                        getSharedPreferences(
                                PREFS,
                                MODE_PRIVATE
                        ),
                        request.dns
                );

        DohClient.Result result =
                raced.result;

        try {
            if (result.ok()) {
                String address =
                        DnsPacket.firstAddress(
                                result.body
                        );

                byte[] response =
                        DnsPacket.buildIpv4UdpResponse(
                                request,
                                result.body
                        );

                writePacket(response);

                DnsLog.success(
                        name,
                        type,
                        address,
                        result.latencyMs,
                        response.length
                );

            } else {
                byte[] servFail =
                        DnsPacket.makeServFail(
                                request.dns
                        );

                byte[] response =
                        DnsPacket.buildIpv4UdpResponse(
                                request,
                                servFail
                        );

                writePacket(response);

                String error =
                        (result.error == null
                                ? "DoH failed"
                                : result.error)
                                + " ["
                                + result.method
                                + "]";

                DnsLog.failure(
                        name,
                        type,
                        error,
                        result.latencyMs
                );
            }

        } catch (Exception e) {
            DnsLog.failure(
                    name,
                    type,
                    safeMessage(e),
                    result.latencyMs
            );
        }
    }

    private void writePacket(
            byte[] packet
    ) throws IOException {
        synchronized (outputLock) {
            if (vpnOutput != null) {
                vpnOutput.write(packet);
                vpnOutput.flush();
            }
        }
    }

    public static long[] getDpiStats() {
        if (!running
                || !MODE_DRAGON_DPI.equals(
                runningMode
        )
                || !HevTunnel.isRunning()) {
            return new long[]{0, 0, 0, 0};
        }

        return HevTunnel.stats();
    }

    private void stopNow() {
        running = false;

        if (tunThread != null) {
            tunThread.interrupt();
            tunThread = null;
        }

        try {
            if (hevTunnel != null) {
                hevTunnel.stop();
            }
        } catch (Throwable ignored) {
        }

        try {
            if (dohBridge != null) {
                dohBridge.stop();
            }
        } catch (Throwable ignored) {
        }

        try {
            if (byeDpi != null) {
                byeDpi.stop();
            }
        } catch (Throwable ignored) {
        }

        closeVpn();

        if (dohPool != null) {
            dohPool.shutdownNow();
        }

        runningStrategy = "";

        stopForegroundCompat();
        stopSelf();

        DnsLog.addRaw("STOP completed");
    }

    private void closeVpn() {
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
    }

    @Override
    public void onRevoke() {
        stopNow();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        if (running
                || vpnInterface != null) {
            stopNow();
        }

        super.onDestroy();
    }

    private void startForegroundCompat(
            Notification notification
    ) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo
                            .FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            );
        } else {
            startForeground(
                    NOTIFICATION_ID,
                    notification
            );
        }
    }

    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT
                >= Build.VERSION_CODES.N) {
            stopForeground(
                    STOP_FOREGROUND_REMOVE
            );
        } else {
            stopForeground(true);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT
                >= Build.VERSION_CODES.O) {

            NotificationChannel channel =
                    new NotificationChannel(
                            CHANNEL_ID,
                            "X-dns VPN",
                            NotificationManager
                                    .IMPORTANCE_LOW
                    );

            channel.setDescription(
                    "X-dns local VPN"
            );

            NotificationManager manager =
                    getSystemService(
                            NotificationManager.class
                    );

            manager.createNotificationChannel(
                    channel
            );
        }
    }

    private Notification createNotification() {
        Intent openApp =
                new Intent(
                        this,
                        MainActivity.class
                );

        PendingIntent pendingIntent =
                PendingIntent.getActivity(
                        this,
                        0,
                        openApp,
                        PendingIntent.FLAG_UPDATE_CURRENT
                                | PendingIntent.FLAG_IMMUTABLE
                );

        Notification.Builder builder =
                Build.VERSION.SDK_INT
                        >= Build.VERSION_CODES.O
                        ? new Notification.Builder(
                        this,
                        CHANNEL_ID
                )
                        : new Notification.Builder(
                        this
                );

        return builder
                .setSmallIcon(R.drawable.ic_vpn)
                .setContentTitle("X-dns")
                .setContentText(
                        "Local VPN engine is active"
                )
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private static void closeQuietly(
            java.io.Closeable closeable
    ) {
        if (closeable == null) return;

        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }

    private static String safeMessage(
            Throwable e
    ) {
        String message = e.getMessage();

        return message == null
                || message.trim().isEmpty()
                ? e.getClass().getSimpleName()
                : message;
    }
}
