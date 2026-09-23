# Airtel 5G Router Monitor

Real-time bandwidth & telemetry dashboard for an **Airtel 5G router**
(ODU: ZLT X17M · IDU: ZLT W304VA PRO) at `192.168.1.1`. It talks to the router's
internal CGI (`/cgi-bin/http.cgi`) using the challenge-token SHA-256 login, and
shows live WAN throughput, connected devices, 5G/4G radio stats, and a read-only
cell-lock panel — plus router reboot.

Ships in four forms. The desktop and Termux versions share one Python core
(standard library only, no pip dependencies); the phone apps are native ports.

| Form | Where | Run it |
|------|-------|--------|
| **Desktop** | `desktop/` | `python desktop/server.py` (or `start_monitor.bat`), open `http://127.0.0.1:8080` |
| **Single-file / Termux** | `mobile/airtel_monitor.py` | copy to phone, run in Pydroid 3 / Termux |
| **Android app** | `app/` (native Kotlin + Compose) | APK from Releases, or build in Android Studio ([docs](docs/ANDROID_BUILD.md)) |
| **iOS app** | `iosApp/` (native SwiftUI) | IPA from Releases, sideload with SideStore/AltStore/Sideloadly ([docs](docs/IOS_BUILD.md)) |

## Layout
```
desktop/          Laptop app: server.py, router_client.py, static/ (web UI)
mobile/           Single-file bundle + Termux launcher + phone setup guide
app/              Native Android app (Kotlin + Jetpack Compose + OkHttp)
iosApp/           Native iOS app (Swift + SwiftUI + URLSession + CryptoKit)
tools/            build_mobile.py (single-file mobile bundle), gen_icon.py (app icons)
docs/             ANDROID_BUILD.md + IOS_BUILD.md + ANDROID_NOTES.md
```

## Source of truth & code generation
The shared logic lives in **`desktop/router_client.py`** and the UI in
**`desktop/static/`**. The single-file bundles are **generated** — after editing
the desktop sources, regenerate them:

```
python tools/build_mobile.py
```

This rewrites `mobile/airtel_monitor.py` (binds `0.0.0.0`). The Android and iOS
apps don't use it; they reimplement the client natively.

## What works and what doesn't (this firmware)
- ✅ Live **total** WAN download/upload (from the router's `cmd 18` byte counters).
- ✅ Device list, Wi-Fi PHY link rates, signal, rename (local aliases).
- ✅ Router **reboot**.
- ✅ **LAN DNS** — view and set the primary/secondary DNS the router hands to
  devices (`cmd 3`). The stock UI hides the secondary field; the app echoes the
  full LAN record on save so nothing else changes, and reads it back to confirm.
- ❌ **Per-device** throughput — not exposed by the firmware (no per-client counters).
- ✅ **Band lock & cell lock** (Android) — choose allowed 4G/5G bands (`cmd 161`) and
  lock to specific 4G/5G cells by EARFCN/ARFCN + PCI (`cmd 160`), like the web UI's
  Advanced Settings (needs the `root` login).
- ❌ **QoS speed limiter** — the router blocks it for this account (`LIMITED_ACCESS`).

## Android
Native Kotlin + Jetpack Compose, targets **Android 17 (SDK 37)**, min Android 7.0.
Build steps: **`docs/ANDROID_BUILD.md`**. Architecture and the Android 16+
`ACCESS_LOCAL_NETWORK` permission: **`docs/ANDROID_NOTES.md`**.

## iOS
Native SwiftUI, iOS 16+. Built unsigned by GitHub Actions (no Mac needed); install
steps in **`docs/IOS_BUILD.md`**.

## Note
Uses the router's default LAN credentials (`root`/`admin`), changeable in the app
settings. Intended for use on your own network only.

This is an independent hobby project. It is not affiliated with or endorsed by
Airtel or ZLT; product names are used only to say which hardware it works with.

## License
[MIT](LICENSE)
