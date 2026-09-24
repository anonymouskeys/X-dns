package com.anonymouskeys.xdns;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

/** A full test is valid only while the same non-VPN default network remains active. */
final class TestNetwork implements AutoCloseable {
    private final ConnectivityManager manager;
    private final Network initial;
    private volatile boolean changed;
    private final ConnectivityManager.NetworkCallback callback =
            new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) {
                    if (!network.equals(initial)) changed = true;
                }
                @Override public void onLost(Network network) {
                    if (network.equals(initial)) changed = true;
                }
            };

    TestNetwork(Context context) {
        manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        initial = manager == null ? null : manager.getActiveNetwork();
        check();
        manager.registerDefaultNetworkCallback(callback);
    }

    void check() {
        Network current = manager == null ? null : manager.getActiveNetwork();
        NetworkCapabilities caps = current == null ? null : manager.getNetworkCapabilities(current);
        if (changed || initial == null || !initial.equals(current) || caps == null
                || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
            throw new IllegalStateException("Network changed, offline, or another VPN is active. Repeat FULL RETEST.");
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("Full retest cancelled");
        }
    }

    @Override public void close() {
        manager.unregisterNetworkCallback(callback);
    }
}
