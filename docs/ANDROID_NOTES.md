# Android build notes & gotchas

Hard-won lessons from getting the Chaquopy app running on **Android 17 (SDK 37)**.
Read this before touching versions or debugging "app opens but no data".

## Toolchain versions that work
- Android Gradle Plugin **9.3.1**, Gradle **9.5**
- **Chaquopy 17.0.0** (`gradle/libs.versions.toml`). Chaquopy 16.x does **not**
  support AGP 9 — sync fails at the plugin. 17.0 supports AGP up to 9.2.x; 9.3.1
  works in practice.
- Target Python **3.11** (Chaquopy needs a matching *build* Python installed on
  the PC; installing Python 3.11 on the dev machine was required).

## Gotcha 1 — Gradle configuration cache vs Chaquopy
Gradle 9 enables the configuration cache by default. Chaquopy launches Python
during the configuration phase, which the cache forbids →
`external process started ... during configuration time is unsupported`.
**Fix:** `org.gradle.configuration-cache=false` in `gradle.properties`.

## Gotcha 2 — Android 16+/17 Local Network Protection (the "no data" bug)
On Android 16+, apps targeting SDK 37+ are **blocked from LAN addresses**
(192.168.x.x) by default. `INTERNET` is not enough. Symptom: the WebView loads
(loopback is exempt) but the poller logs `Router login error: timed out`.
**Fix:** declare **and request at runtime** `android.permission.ACCESS_LOCAL_NETWORK`
(see `AndroidManifest.xml` + `MainActivity.onCreate`). After the user taps Allow,
the poller logs `Authenticated with Airtel ZLT router`.

## Gotcha 3 — WebView ignores JS dialogs without a WebChromeClient
`window.confirm()` / `alert()` silently return false in a WebView unless a
`WebChromeClient` is set. This broke the "Stop App" confirm.
**Fix:** `webView.webChromeClient = WebChromeClient()` in `MainActivity`.

## Architecture recap
- `MainActivity` starts Python via Chaquopy (`python/start.py` → `airtel_monitor.main()`
  on a daemon thread), then shows a WebView pointed at `http://127.0.0.1:8080`.
- `app/src/main/python/airtel_monitor.py` is **generated** by `tools/build_mobile.py`
  from `desktop/router_client.py` + `desktop/static/`. Don't edit it by hand —
  edit the desktop sources and re-run the tool.
- "Stop App" uses a `@JavascriptInterface` bridge (`AndroidBridge.stopApp()`) that
  finishes the task and kills the process.

## Rebuild + install from the command line (WSL/Windows)
```
JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
gradlew.bat :app:installDebug -x lint --console=plain
```
