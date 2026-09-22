# Airtel 5G Router Monitor

Real-time bandwidth & telemetry dashboard for an **Airtel 5G router**
(ODU: ZLT X17M · IDU: ZLT W304VA PRO) at `192.168.1.1`. It talks to the router's
internal CGI (`/cgi-bin/http.cgi`) using the challenge-token SHA-256 login, and
shows live WAN throughput, connected devices, 5G/4G radio stats, and a read-only
cell-lock panel — plus router reboot.

Ships in three forms, all from one shared Python core (standard library only, no
pip dependencies):

| Form | Where | Run it |
|------|-------|--------|
| **Desktop** | `desktop/` | `python desktop/server.py` (or `start_monitor.bat`), open `http://127.0.0.1:8080` |
| **Single-file / Termux** | `mobile/airtel_monitor.py` | copy to phone, run in Pydroid 3 / Termux |
| **Android app** | `app/` (Native Kotlin Compose) | build in Android Studio → installable APK |
| **iOS app** | `iosApp/` (Native SwiftUI) | GitHub Actions runner → installable IPA (SideStore/AltStore) |

## Layout
```
desktop/          Laptop app: server.py, router_client.py, static/ (web UI)
mobile/           Single-file bundle + Termux launcher + phone setup guide
app/              Native Android app (Kotlin + Jetpack Compose + OkHttp)
iosApp/           Native iOS app (Swift + SwiftUI + URLSession + CryptoKit)
tools/            build_mobile.py — regenerates the single-file mobile bundle
backup/           Archived copy of previous Chaquopy APK (airtel_monitor_previous_chaquopy.apk)
docs/             ANDROID_BUILD.md + IOS_BUILD.md + ANDROID_NOTES.md
```

## Source of truth & code generation
The shared logic lives in **`desktop/router_client.py`** and the UI in
**`desktop/static/`**. The single-file bundles are **generated** — after editing
the desktop sources, regenerate them:

```
python tools/build_mobile.py
```

This rewrites `mobile/airtel_monitor.py` (binds `0.0.0.0`) and
`app/src/main/python/airtel_monitor.py` (binds `127.0.0.1` for the WebView).

## What works and what doesn't (this firmware)
- ✅ Live **total** WAN download/upload (from the router's `cmd 18` byte counters).
- ✅ Device list, Wi-Fi PHY link rates, signal, rename (local aliases).
- ✅ Read-only band / cell-lock status; router **reboot**.
- ✅ **LAN DNS** — view and set the primary/secondary DNS the router hands to
  devices (`cmd 3`). The stock UI hides the secondary field; the app echoes the
  full LAN record on save so nothing else changes, and reads it back to confirm.
- ❌ **Per-device** throughput — not exposed by the firmware (no per-client counters).
- ❌ **QoS speed limiter** and **band/PCI-lock editing** — the router blocks those
  writes (`LIMITED_ACCESS`), so they were removed rather than shown broken.

## Android
Targets **Android 17 (SDK 37)** via Chaquopy 17. See **`docs/ANDROID_NOTES.md`** for
the three things that will bite you (config-cache vs Chaquopy, the
`ACCESS_LOCAL_NETWORK` permission, and the WebChromeClient dialog quirk).

## Note
Uses the router's default LAN credentials (`root`/`admin`). Intended for use on
your own network only.
