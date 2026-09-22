package com.anonymouskeys.xdns;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import java.io.IOException;

public class XDnsVpnService extends VpnService {

    private static final String CHANNEL_ID = "xdns_vpn";
    private static final int NOTIFICATION_ID = 100;

    private static volatile boolean running = false;

    private ParcelFileDescriptor vpnInterface;

    public static boolean isRunning() {
        return running;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        running = true;

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
            vpnInterface = new Builder()
                    .setSession("X-dns")
                    .setMtu(1500)

                    // Тестовый VPN-маршрут.
                    // Реальный интернет пока НЕ перехватываем.
                    .addAddress("10.253.0.2", 32)
                    .addRoute("10.253.0.1", 32)

                    .establish();
        }

        return START_STICKY;
    }

    @Override
    public void onRevoke() {
        stopSelf();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {

        running = false;

        if (vpnInterface != null) {
            try {
                vpnInterface.close();
            } catch (IOException ignored) {
            }

            vpnInterface = null;
        }

        super.onDestroy();
    }

    private void createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "X-dns VPN",
                    NotificationManager.IMPORTANCE_LOW
            );

            channel.setDescription("X-dns local VPN service");

            NotificationManager manager =
                    getSystemService(NotificationManager.class);

            manager.createNotificationChannel(channel);
        }
    }

    private Notification createNotification() {

        Intent openApp = new Intent(this, MainActivity.class);

        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                openApp,
                PendingIntent.FLAG_UPDATE_CURRENT |
                        PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        return builder
                .setSmallIcon(R.drawable.ic_vpn)
                .setContentTitle("X-dns")
                .setContentText("Local VPN engine is active")
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }
}
