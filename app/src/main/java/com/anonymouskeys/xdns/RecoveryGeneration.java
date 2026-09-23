package com.anonymouskeys.xdns;

/** Orders handovers, STOP, and publication of an AUTO result. */
final class RecoveryGeneration {
    private long value;
    synchronized long current() { return value; }
    synchronized long invalidate() { return ++value; }
    synchronized boolean publish(long ticket, Runnable update) {
        if (ticket != value) return false;
        update.run();
        return true;
    }
}
