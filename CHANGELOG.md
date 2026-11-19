# Changelog

All notable changes to this project are documented here. Format follows
[Keep a Changelog](https://keepachangelog.com/); this project is pre-release.

## [0.6.1] — 2026-11-19

### Changed
- **Built-in CF Worker fallback domain** (`proud-bird-bd42.pnlarkin.workers.dev`): always
  passed to upstream as a `--cfproxy-worker-domain` candidate, so the WS fallback works
  out of the box. User-entered domains (UI field) are tried first; the built-in one is
  appended and deduped. First release shipped through the self-update channel.

## [0.6.0] — 2026-11-19

Self-update: the app now checks and updates itself from a private GitHub repo's Releases.

### Added
- **In-app self-update** over GitHub Releases of a private repo (coords + PAT in
  `update.properties`, gitignored — see `update.properties.example`): silent check on
  open plus a manual "Check for update" card, numeric per-segment tag compare, APK
  download with progress into `cache/updates/` (.part file + rename + Content-Length and
  metadata size verification), hand-off to the system installer via FileProvider
  (`REQUEST_INSTALL_PACKAGES`; Android walks the user through the "install unknown apps"
  consent). Private-repo asset downloads follow the 302 to the signed URL manually so the
  Authorization header never leaks to the redirect host. The APK embeds a fine-grained
  read-only PAT scoped to this repo only — accepted trade-off for a friends-only
  distribution. The update card hides itself on builds without config. After a self-update
  install, `MY_PACKAGE_REPLACED` (already handled by `BootReceiver`) restarts the proxy
  if it was running.
- `tools/release.sh`: build signed APK → push main → create the release + upload the APK
  asset via API (write PAT from `update.properties`, local-only) → verify
  `/releases/latest` serves the tag and the exact APK size.

## [0.5.0] — 2026-11-17

Reliability polish (ideas from reviewing the independent amurcanov/tg-ws-proxy-android).

### Changed
- `START_STICKY` → **`START_REDELIVER_INTENT`**: after a memory-pressure kill the system
  restarts the service with the original start intent (extras intact) instead of a null
  one — combined with persisted secret/CF-domains this resurrects the exact start.

### Added
- **Port pre-check** before starting the Python core: if `127.0.0.1:1443` is already
  bound (stale instance, another app), the user gets an immediate toast instead of the
  core failing to bind. Bind-and-release probe on loopback, off the main thread.

## [0.4.0] — 2026-11-17

Cloudflare Worker fallback exposed in the app.

### Added
- **CF Worker domains field** (card between secret and connect-link): own
  `*.workers.dev` domains passed to upstream as repeatable `--cfproxy-worker-domain`
  flags — upstream tries them before other fallback methods (useful when the ISP blocks
  both direct DC connects and the public CF-proxy pool; see upstream docs/RU/CfWorker.md).
  Raw string persisted in prefs on focus-loss/IME-done (comma/space separated);
  parsed (lowercase, dedup) at proxy start, survives sticky restarts. Empty → upstream's
  own fallback chain, behaviour unchanged.
- `proxy_entry.start()` grew a 5th arg `cf_worker_domains` (backwards-compatible default None).

## [0.3.0] — 2026-11-16

Upstream proxy refresh: v1.8.1 → **v1.10.4** (Flowseal/tg-ws-proxy@`70b982d`), keeping the
HA add-on sibling and this APK on the same pinned commit. Signed release built and tagged
`v0.3.0` (versionCode 3), 22.5 MB / 767 entries, same signing key as v0.2.0; APK scanned
clean of personal data (paths/names — none).

### Changed
- APK output names are now versioned: `tg-ws-proxy-<version>.apk` (release) /
  `tg-ws-proxy-<version>-debug.apk` (debug) via `applicationVariants.outputs.outputFileName`
  — no more anonymous `app-release.apk` floating around when sharing builds.
- `tools/fetch_upstream.{sh,ps1}` pin bumped to v1.10.4 (`70b982d`); `app/src/main/python/proxy`
  re-fetched (not vendored, so the pin is the only source of truth).
- versionCode 2 → 3, versionName 0.2.0 → 0.3.0.

### Added
- `certifi` to Chaquopy pip installs (pure-python wheel, prefetched into `wheels/`):
  upstream v1.10.4 imports it at module level for TLS root certificates
  (`ssl.create_default_context(cafile=certifi.where())`); without it the proxy dies with
  ImportError at start.

### Notes
- Upstream deltas that matter on-device: direct-connection pool recreates closed idle
  connections; faster DC connects (v1.9.0); CF Workers upload/multi-domain fixes (v1.9.1);
  improved direct-connection stability (v1.10.1); logging auto-hides domains (v1.10.2).
  `utils.logging_setup` import in `tg_ws_proxy.py` only fires with `--log-file`, which we
  don't pass — no extra packaging needed.
- `cryptography` stays at 42.0.8 (Chaquopy cp311/arm64 wheel); upstream's desktop pin moved
  to 46.0.5 but the AES surface it uses is unchanged since v1.8.1 (`proxy/_aes.py` untouched
  in the v1.8.1→v1.10.4 diff).

## [0.2.0] — 2026-06-29

Shade-level control + brand identity. Signed release built and tagged `v0.2.0`
(versionCode 2), 22.3 MB, verified clean of personal data (see Notes).

### Added
- Quick Settings tile (`ProxyTileService`) — toggle the proxy from the shade without
  opening the app. Tile state mirrors the same honest live-socket truth (`ProxyHealth`)
  the notification uses; toggling reuses the existing start / `ACTION_STOP` paths, so
  reboot/death auto-restart and graceful stop keep working. Probe runs off the main
  thread; `onClick` flips optimistically, `onStartListening` reconciles.
- Stop action on the foreground-service notification — a `NotificationCompat.Action`
  wired to `ACTION_STOP` (clears `auto_start` + unwinds the loop gracefully).
- Marine "Encrypted Signal" adaptive launcher icon — emerald live-signal core + azure
  pulse rings on a navy gradient (the in-app palette), replacing the default-ish
  cube-on-#0088CC. Includes a `<monochrome>` layer for themed icons (Android 13+).

### Notes
- Reboot auto-start does **not** fire on aggressive-OEM ROMs (Xiaomi/Huawei/Oppo/Vivo):
  the OEM doesn't deliver `BOOT_COMPLETED` to apps not whitelisted in Autostart. The code
  is correct (`targetSdk 34`, so the Android 15 boot-FGS restriction doesn't apply); no
  code workaround exists. User must enable Autostart + disable battery optimization.
  Deprioritized — not critical. See `BACKLOG.md` → Known limitations.
- APK inspected (739 entries, dex + `.pyc` included): no name/email/build-paths/secret.
  Only identifier is the signing cert DN (`CN=tg-ws-proxy, OU=MrKaa, C=RU` — a chosen
  pseudonym, present in any signed APK). Safe to hand around.

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
- "Always on" — the proxy survives reboots and process death. A persisted `auto_start`
  intent flag (set on Start, cleared on Stop) gates a `BootReceiver` (`BOOT_COMPLETED` +
  `MY_PACKAGE_REPLACED`, so it also comes back after an over-the-top update) and the
  existing `START_STICKY` restart. A deliberate Stop stays stopped across reboots.
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

### Fixed
- Header version tag showed stale hardcoded values — `version_tag` lived in strings.xml
  per-locale and had drifted (en: v0.2.0, ru: still v0.1.0, actual build: 0.3.0). Now
  generated from `versionName` via gradle `resValue` (debug builds show the `-debug`
  suffix too); hardcoded copies removed. Verified byte-level in the APK: exactly one
  `v0.3.0`, zero stale strings.
- Sticky restart (null intent after process death) no longer drops the user's secret and
  lets upstream generate a mismatched one — it now falls back to the persisted secret, so
  the existing `tg://proxy?…secret=…` link keeps working after a restart.

### Notes
- Graceful-stop loop unwinding verified by build only; confirm on-device.
- The minified release APK builds and is signed, but R8 runtime behavior (Python init at
  startup) is unverified on a device — install `app-release.apk` and confirm the proxy
  starts before handing it around.
