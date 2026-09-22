# X-dns

Experimental Android local VPN focused on DNS-over-HTTPS and, in the next stage,
local anti-DPI traffic processing.

## v0.2

This version contains a real DNS-only VPN engine:

- built-in DoH endpoints:
  - Xfinity
  - Flatuslifir
  - Plan9 Hydra
  - Plan9 Draco
  - Cloudflare
  - Google
  - Quad9
- add custom DoH URLs
- test a DoH endpoint from the app
- intercept Android IPv4 UDP DNS requests through a local `VpnService`
- forward DNS packets as RFC 8484 `application/dns-message` POST requests
- DNS query log with domain, type, result IP and latency
- DNS query / success / error / average latency statistics
- live device RX/TX speed
- per-app bypass/exclusion list for launcher applications

The X-dns process itself bypasses its own VPN so the DoH connection cannot recurse
back into the local DNS tunnel.

## Current pipeline

```text
Android app
    |
    +--> DNS query --> X-dns TUN (10.253.0.1:53)
                         |
                         +--> selected HTTPS DoH endpoint
                         |
                         +--> DNS response back to the app

Excluded applications --> normal Android network/DNS
```

## Next stage

v0.3 will add a full-traffic mode:

```text
Apps -> X-dns TUN -> tun2socks -> local ByeDPI/ciadpi -> Internet
              \
               -> DoH engine
```

The anti-DPI stage will be integrated from source and the project licensing will
be aligned with the GPL-3.0 components used there.
