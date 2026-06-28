# TG WS Proxy — Android APK

Self-contained Android app that runs [Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy)
(MTProto-over-WebSocket proxy) **on the device itself**, so Telegram can bypass ISP-level
MTProto blocking on **any** network — cellular, foreign Wi-Fi, anywhere.

> ℹ️ Same philosophy as the [HA add-on sibling](../tg-ws-proxy-ha-addon): we **don't fork**
> the proxy. The upstream `proxy/` is fetched verbatim at a pinned commit and embedded via
> [Chaquopy](https://chaquo.com/chaquopy/) (CPython on Android). Updating upstream = bump one
> SHA in `tools/fetch_upstream.*` + the version, rebuild.

## How it works

```
Telegram app  ──MTProto──▶  127.0.0.1:1443  (this app, loopback only)
                                  │
                            embedded CPython 3.11 + proxy/  ──WS/TLS──▶  Telegram DCs
                                  (foreground service)         (via fronting domains)
```

The client→proxy hop never leaves the device (loopback), so DPI never sees the MTProto
fingerprint; the proxy→Telegram hop looks like ordinary TLS to CDN-fronted domains.

## User flow (shareable APK)

1. Install the APK.
2. First launch generates a random MTProto secret (kept in app prefs).
3. Tap **Start proxy** → a foreground service runs the embedded proxy on `127.0.0.1:1443`.
4. Tap **Add to Telegram** → opens `tg://proxy?server=127.0.0.1&port=1443&secret=dd…`
   directly in Telegram's add-proxy dialog.
5. (Recommended) Tap **Disable battery optimization** so Doze doesn't kill connections.

## Build prerequisites

| Tool | Needed | Status on the original build machine |
|---|---|---|
| **JDK 17** | AGP 8.5 / Gradle 8.7 | ⚠️ install (Temurin 17 recommended) |
| **Android SDK** platform-34, build-tools 34 | compile | ✅ present |
| **buildPython** = CPython **3.11** on PATH | Chaquopy bundles matching runtime | ✅ `C:\Soft\Python\Python311` |
| Gradle | via `./gradlew` wrapper | ✅ wrapper included |

Point Gradle at the SDK with a `local.properties` (git-ignored):

```properties
sdk.dir=C:\\Users\\Pavel Larkin\\AppData\\Local\\Android\\Sdk
```

If `buildPython` isn't auto-detected, set it in `app/build.gradle` under
`defaultConfig.python { buildPython "C:/Soft/Python/Python311/python.exe" }`.

## Build steps

```bash
# 1. Pull the upstream proxy code (not vendored):
pwsh tools/fetch_upstream.ps1      # or: bash tools/fetch_upstream.sh

# 2. Build the debug APK:
./gradlew assembleDebug

# 3. Install on a connected device:
./gradlew installDebug
# APK lands at app/build/outputs/apk/debug/app-debug.apk
```

## Validate (milestone 1)

Before polishing UI, confirm on a real phone:

1. **Telegram accepts `server=127.0.0.1`** — most clients do; some warn on local addresses.
2. **`cryptography` wheel installs** for Python 3.11 / arm64 from Chaquopy's repo.
3. **Bypass works on cellular** — i.e. the upstream fronting domains aren't blocked for you.

## Known limitations (v0.1.0)

- **Stop tears the process down** — the upstream owns its own asyncio loop; a graceful
  in-loop shutdown hook is a later milestone.
- ABIs shipped: `arm64-v8a` (phones), `x86_64` (emulator). Add `armeabi-v7a` for very old devices.
- `dc_ips` uses upstream defaults (not exposed in UI yet).

## License

Packaging (this repo): MIT, MrKaa001. Wrapped proxy: MIT, Flowseal — see the fetched
`app/src/main/python/proxy/LICENSE-UPSTREAM`.
