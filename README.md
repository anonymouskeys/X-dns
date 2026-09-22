# X-dns

Experimental Android local VPN combining a real DNS-over-HTTPS mode with a
full-traffic Dragon/ByeDPI test mode.

## v0.3

### Resolver database

- discovered public DoH endpoints are saved persistently
- discovered endpoints are automatically tested in a bounded worker pool
- working discovered endpoints survive app restarts
- built-in resolver status survives app restarts
- status database shows `✓`, `?`, or `✗`
- working resolvers show saved latency and transport method
- tapping a working resolver selects it
- tapping a failed/unknown resolver retests it

### Dragon DPI mode

v0.3 integrates the same native path used by DragonVPN:

```text
Android apps
    ↓
VpnService full TUN
    ↓
hev-socks5-tunnel
    ↓
127.0.0.1:1080
    ↓
ciadpi / ByeDPI
    ↓
Internet
```

The `Dragon DPI` mode uses the exact Auto/Maximum cascade from
`anonymouskeys/Dragon-vpn` `ByeDpiManager.kt`, including `--auto-mode 1`,
HTTP/TLS targeting and the working progressive Maximum strategy.

The GitHub Actions build compiles the same Dragon native components for:

- arm64-v8a
- armeabi-v7a
- x86
- x86_64

and packages them into one universal APK.

### Important DNS note

The DoH-only mode is fully connected to the selected DoH resolver.

The Dragon DPI mode in v0.3 is intentionally a separate full-traffic proof:
hev owns the TUN file descriptor, so the old Java DoH TUN reader cannot safely
read that same FD at the same time. v0.3 therefore uses a normal VPN DNS server
in Dragon DPI mode while proving the working Dragon/ByeDPI path.

The next native layer is a DNS intercept/sidecar so selected DoH can be used
inside the same full-traffic TUN without two readers racing on one TUN FD.

## Licensing

DragonVPN and ByeDPI components used by the native DPI build are GPL-3.0.
X-dns is therefore intended to be distributed under GPL-3.0 when this mode is
included.
