package com.v2ray.ang.service;

/**
 * JNI contract used by the hev-socks5-tunnel binary compiled by Dragon-vpn.
 * Dragon compiles it with PKGNAME=com/v2ray/ang/service.
 */
public final class TProxyService {

    static {
        System.loadLibrary("hev-socks5-tunnel");
    }

    private TProxyService() {}

    public static native boolean TProxyStartService(String configPath, int fd);
    public static native boolean TProxyStopService();
    public static native boolean TProxyIsRunning();
    public static native long[] TProxyGetStats();
}
