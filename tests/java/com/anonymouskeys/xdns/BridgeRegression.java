package com.anonymouskeys.xdns;

import java.net.Socket;
import java.util.concurrent.TimeUnit;

/** Run with the Android API jar on the classpath; no Android runtime methods used. */
public final class BridgeRegression {
    public static void main(String[] args) throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            SocksDohBridge bridge = new SocksDohBridge();
            bridge.start(null);
            try (Socket client = new Socket("127.0.0.1", SocksDohBridge.PORT)) {
                client.setSoTimeout(2000);
                client.getOutputStream().write(new byte[]{5, 1, 0});
                if (client.getInputStream().read() != 5 || client.getInputStream().read() != 0)
                    throw new AssertionError("SOCKS greeting failed");
                long started = System.nanoTime();
                bridge.stop(); // Worker is blocked waiting for a CONNECT request.
                if (client.getInputStream().read() != -1) throw new AssertionError("client socket remained open");
                if (System.nanoTime() - started > TimeUnit.SECONDS.toNanos(2))
                    throw new AssertionError("stop blocked too long");
            } finally { bridge.stop(); }
        }
        System.out.println("Bridge regression checks passed (blocked socket close and repeated port rebind).");
    }
}
