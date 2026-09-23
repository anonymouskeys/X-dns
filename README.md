# X-dns

Experimental Android local VPN combining DNS-over-HTTPS discovery/benchmarking
with a local Dragon/ByeDPI traffic path.

## Network recovery fix (under device validation)

AUTO now belongs to the foreground service and retests after operator/Wi-Fi changes.
See [recovery behavior, checks, and limits](docs-network-recovery.md).

## Original v0.4 overview

### Persistent DoH database

`FIND + SAVE + TEST FREE DOH` now:

1. downloads the public catalog,
2. saves every discovered HTTPS DoH URL before testing,
3. tests every saved URL,
4. persists status, latency, success count and transport method,
5. keeps working discovered resolvers available after app restart.

Resolver status uses:

- `✓` working,
- `?` not tested,
- `✗` failed.

### AUTO tuning

`AUTO: BEST DOH + DPI FOR YOUTUBE`:

1. loads the persistent resolver database,
2. downloads/saves a catalog when none has been saved yet,
3. ensures multiple DoH candidates work,
4. re-benchmarks the fastest candidates using three DNS probes,
5. keeps the best three stable DoH endpoints,
6. starts local ciadpi with progressively stronger Dragon strategies,
7. performs a real HTTPS probe to YouTube through the local SOCKS proxy,
8. saves the best working DoH + DPI pair.

The strategy order begins with simple split/disorder modes and ends with the
same DragonVPN Maximum/Auto cascade used in `anonymouskeys/Dragon-vpn`.

### Full Dragon traffic mode

```text
Android apps
    ↓
VpnService full TUN
    ↓
hev-socks5-tunnel
    ├─ internal mapdns for app DNS
    ↓
127.0.0.1:1080
    ↓
ciadpi / selected Dragon strategy
    ↓
Internet
```

HEV mapdns is used in the full-TUN mode so ordinary DNS packets are not sent as
SOCKS UDP to ciadpi.

### DoH mode

DoH-only mode still sends the intercepted Android DNS wire packet to the
selected RFC 8484 HTTPS endpoint and writes the DNS response back to the TUN.

The next networking layer will merge selected DoH into the same full-TUN path
instead of HEV mapdns.
