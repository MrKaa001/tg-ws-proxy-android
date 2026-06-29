# Backlog

Roadmap beyond the working v0.1.0 APK. Upstream `proxy/` TODOs are Flowseal's, not ours
(we don't fork upstream) and are out of scope here.

## Done
- ✅ Self-contained APK (Chaquopy + embedded proxy, arm64-v8a).
- ✅ Offline wheel build (defeat chaquo.com throttling) — `tools/fetch_wheels.sh`.
- ✅ milestone-1 validation on a real phone (works on cellular, Telegram accepts loopback).
- ✅ Live status: honest TCP liveness probe driving the FGS notification + in-app heartbeat + manual "Check".
- ✅ "Encrypted Signal" UI redesign.
- ✅ Graceful stop — signal upstream `stop_event`, unwind the loop, keep the process alive (hard kill only as a timeout backstop).
- ✅ Release signing — `signingConfigs` reading `keystore.properties` (gitignored) + R8 minify + resource shrinking; `assembleRelease` → signed 23 MB APK.
- ✅ Survive reboot / death — `BootReceiver` (BOOT_COMPLETED + MY_PACKAGE_REPLACED) + START_STICKY restart, gated on a persisted `auto_start` intent flag; sticky restart now reuses the saved secret instead of generating a fresh one.
- ✅ Quick Settings tile + Stop action in the notification — `ProxyTileService` (QS tile mirroring the live-socket state, toggling via the existing start/ACTION_STOP paths) + a Stop `NotificationCompat.Action` on the FGS notification. Toggle from the shade without opening the app.

## Next
- 🎨 **Launcher icon** matching the marine identity (currently default-ish, #0088CC).
- 📱 **More ABIs** — `armeabi-v7a` (very old phones), `x86_64` (emulator; dropped to shrink the APK + avoid a 2nd wheel set).
- ⚙️ **Expose `dc_ips` / fronting domains in the UI** — currently upstream defaults are baked in.
