# X-dns

Experimental Android local VPN.

## v0.2.1

Hotfix for the real DNS-over-HTTPS layer:

- explicit START / STOP service actions
- robust DoH client based on OkHttp (HTTP/2 capable)
- RFC 8484 wire-format POST with GET fallback
- test selected DoH
- test every built-in DoH and sort working resolvers by latency
- online public DoH catalog discovery from the curl DoH wiki
- add discovered/custom resolvers
- DNS log, query/error/latency statistics
- application exclusions

Built-in resolvers currently include Xfinity, Flatuslifir, Plan9 Hydra,
Plan9 Draco, Cloudflare, Google, Quad9, AdGuard, DNS.SB and DNS4all.

## Important

v0.2.1 is still **DNS-only**. It does not yet contain ByeDPI/tun2socks.
The next architecture step is a full-traffic mode:

```text
Apps -> X-dns TUN -> tun2socks -> local ByeDPI -> Internet
              \
               -> DoH engine
```

That anti-DPI integration will require native Android/NDK code and GPL-3.0
licensing for the combined work.
