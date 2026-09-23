package com.anonymouskeys.xdns;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RecoveryRegression {
    private static void check(boolean result, String message) {
        if (!result) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        RecoveryGeneration generation = new RecoveryGeneration();
        long operatorA = generation.current();
        long operatorB = generation.invalidate();
        AtomicBoolean saved = new AtomicBoolean();
        check(!generation.publish(operatorA, () -> saved.set(true)), "old operator published");
        check(!saved.get(), "old profile changed preferences");
        check(generation.publish(operatorB, () -> saved.set(true)), "current profile rejected");
        generation.invalidate(); // STOP
        check(!generation.publish(operatorB, () -> {}), "STOP allowed a late publish");
        generation.invalidate(); // A -> B -> C before debounce completes
        check(!generation.publish(operatorA, () -> {}), "rapid handover reused an old ticket");

        // Reproduce a probe finishing after an operator change on another thread.
        long slowProbe = generation.current();
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        AtomicBoolean stale = new AtomicBoolean();
        Thread worker = new Thread(() -> {
            ready.countDown();
            try { finish.await(); } catch (InterruptedException e) { throw new AssertionError(e); }
            stale.set(generation.publish(slowProbe, () -> {}));
        });
        worker.start();
        check(ready.await(2, TimeUnit.SECONDS), "probe did not start");
        generation.invalidate();
        finish.countDown();
        worker.join(2000);
        check(!worker.isAlive() && !stale.get(), "late probe accepted after handover");

        for (int code : new int[]{200, 204, 301, 302, 400, 404, 405})
            check(ProbePolicy.reachable(code), "expected transport response " + code);
        for (int code : new int[]{-1, 0, 100, 401, 403, 407, 429, 451, 500, 503})
            check(!ProbePolicy.reachable(code), "false service success " + code);
        System.out.println("Recovery regression checks passed (handover, STOP, late results, HTTP policy).");
    }
}
