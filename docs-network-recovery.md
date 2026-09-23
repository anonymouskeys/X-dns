# Network recovery and AUTO validation

This change addresses stale operator-specific DNS/routes and overlapping AUTO/VPN
engines. It does not claim a universal censorship bypass.

## Behavior

- The foreground service watches the calling app's default network. X-dns is
  excluded from its own VPN; VPN capability events are ignored. No location or
  telephony identifiers are collected.
- A network loss/change invalidates pending results immediately. Recovery starts
  after a 1.5-second debounce, with native engine ownership serialized across
  service instances. The service remains foreground while tuning or waiting.
- Starting Dragon mode, switching networks, or pressing AUTO tests the current
  network. DoH-only mode restarts without a DPI search. No-network state waits for
  a callback. A failed AUTO remains stopped with a visible reason; use AUTO again
  to retry on the same network. There is no endless retry loop.
- STOP cancels work and unregisters callbacks. A generation guard prevents a late
  profile from being published after a handover or STOP. Existing bridge sockets
  are closed during cleanup, including sockets waiting for a SOCKS request.
- Recovery clears route memory, resolver measurements, DNS caches and pooled DoH
  connections. Cache generations reject late DNS cache writes.
- Resolver discovery tests up to 24 saved/built-in endpoints with six workers and
  a 75-second collection window. The public catalog is no longer downloaded on
  the critical recovery path. A four-minute search budget is checked between
  benchmarks/candidates; an in-flight network operation can exceed this budget.
- AUTO probes domain-form SOCKS through the same DNS bridge and ByeDPI used by
  apps. Candidate DoH is selected in memory without prematurely publishing it.
  It checks four YouTube endpoints and separately checks Instagram's website/API
  and TikTok's website. Among tested YouTube-capable pairs, broader service
  reachability ranks before latency. It stops early when all tested services pass.
- HTTP 401/403/407/429/451 and server errors are not counted as success. Root HEAD
  responses such as 404/405 indicate HTTPS transport, not working login, media
  delivery, every CDN hostname, or regional app functionality. The YouTube media
  check is the redirector endpoint, not a full video download. Route-memory TCP
  indicators are explicitly labelled as TCP-only.
- Meta IPv6 candidates are considered only when the underlying link reports an
  IPv6 default route. This is a prerequisite, not proof that every IPv6 destination
  is reachable.

## Remaining limits

This app uses local packet manipulation and direct Internet routes, not a remote
exit server. If all usable destination IPs are blocked, another DoH resolver or
TLS splitting may be insufficient. A user-controlled external proxy/tunnel is
needed for that scenario; this change does not silently add a third-party proxy.
During recovery the old VPN TUN is closed, as in manual AUTO; this is not a kill
switch. Android always-on "block connections without VPN" changes that behavior.

## Validation

- All Java sources type-checked with Android API 34, Java 17, OkHttp 4.12.0,
  Okio 3.6.0, Kotlin stdlib 1.9.10 (temporary generated R stub for the icon).
- `tests/run-regression.sh`: late results, repeated handovers, STOP publication
  guard, and HTTP success classification.
- `BridgeRegression`: real localhost SOCKS greeting, blocked-reader cleanup,
  and three successive bridge start/stop/rebind cycles.
- Full Gradle APK packaging was not run locally: Java's Gradle download could not
  reach the distribution host. The PR workflow builds the native components and
  APK on GitHub and runs the pure Java recovery regression checks.

## Required device acceptance checks

Use the existing signing key for updates. Keep that key and its password local.

1. Start AUTO on operator A. Open a YouTube video, Instagram feed/reels, and TikTok.
   Compare real playback with the separate HTTPS diagnostic results.
2. Switch A to B while connected, then while AUTO is running. Confirm a single
   new AUTO starts after debounce and no old result is applied.
3. Toggle airplane mode, restore data, then switch Wi-Fi/mobile data. Confirm
   waiting/recovery states and successful reconnection without reopening the app.
4. Press STOP mid-test, wait 30 seconds, and verify no VPN/native process restarts.
5. Repeat with IPv4-only and dual-stack networks and with the activity backgrounded.
6. On a blocked service, retain the AUTO error and app log; do not infer successful
   playback solely from a TCP-route indicator or a root HTTPS response.
