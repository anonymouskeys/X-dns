package com.anonymouskeys.xdns;

/** A root HEAD response tests HTTPS reachability, not login or video playback. */
final class ProbePolicy {
    private ProbePolicy() { }
    static boolean reachable(int code) {
        return (code >= 200 && code < 400) || code == 400 || code == 404 || code == 405;
    }
}
