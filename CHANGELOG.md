# Changelog

All notable changes to this project are documented here. Format follows
[Keep a Changelog](https://keepachangelog.com/); this project is pre-release.

## [0.1.0] — unreleased

First working self-contained APK: Telegram's MTProto-over-WebSocket proxy running
on-device (Chaquopy / embedded CPython 3.11), listening on loopback only.

### Added
- Self-contained APK scaffold — Chaquopy + upstream `proxy/` fetched verbatim (no fork),
  foreground service on `127.0.0.1:1443`, arm64-v8a.
- Offline native-wheel build path to defeat chaquo.com DPI throttling: `tools/grind_get.sh`
  (16 KB byte-range grinding downloader) + `tools/fetch_wheels.sh` (one-shot prefetch of
  cryptography / cffi / chaquopy-libffi), served to Chaquopy pip via `--find-links`.
- Live status: honest TCP liveness probe (`ProxyHealth`) of the loopback listener, driving
  the foreground-service notification ("starting…" → "running" → "not responding"), an
  in-app 2 s heartbeat, and a manual **Check** button with a latency readout.
- Graceful stop — signals upstream's `stop_event` (via `proxy_entry.stop()`) so the asyncio
  loop unwinds cleanly and the app process survives; hard process teardown kept only as a
  timeout backstop.
- "Encrypted Signal" UI redesign — Material 3 dark marine theme, status-hero card with a
  state-tinted signal indicator + loopback endpoint readout, carded secret/link sections.
- Release signing — `signingConfigs.release` reads secrets from `keystore.properties`
  (gitignored; `keystore.properties.example` committed as a stub), so `assembleRelease`
  produces a signed APK that installs over future updates on the same key. R8 minify +
  resource shrinking enabled for release (keep rules for Chaquopy + own package already in
  `proguard-rules.pro`); APK drops from 27.5 MB (debug) to 23 MB. Signed with v2 scheme
  (sufficient for minSdk 26).
- `BACKLOG.md` roadmap.

### Changed
- Status is now derived from real probes, not an optimistic local flag.
- Build targets arm64-v8a only (x86_64 dropped to shrink the APK / avoid a 2nd wheel set).

### Notes
- Graceful-stop loop unwinding verified by build only; confirm on-device.
- The minified release APK builds and is signed, but R8 runtime behavior (Python init at
  startup) is unverified on a device — install `app-release.apk` and confirm the proxy
  starts before handing it around.
