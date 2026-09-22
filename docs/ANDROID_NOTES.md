# Android Architecture Notes & Toolchain (Kotlin Native Compose)

The Android app has been migrated from Chaquopy (embedded Python + WebView) to **100% Native Kotlin + Jetpack Compose**.

## Toolchain
- **Android Gradle Plugin (AGP)**: 9.3.1
- **Gradle**: 9.5
- **Kotlin**: 2.2.10 (built into AGP 9 with Kotlin Compose Plugin)
- **Jetpack Compose**: Compose BOM `2024.10.01` (Material3 1.3.1, UI 1.7.5)
- **Networking**: OkHttp 4.12.0
- **Target SDK**: 37 (Android 17)
- **Min SDK**: 24 (Android 7.0)

## Architecture Overview
- **Networking & Data Layer** (`com.airtel.monitor.data`):
  - `ZltRouterClient`: Communicates with router's JSON-RPC HTTP CGI engine at `http://192.168.1.1/cgi-bin/http.cgi`.
    - Automated challenge-token SHA-256 login (`cmd 232` -> `cmd 100`) and session re-authentication.
    - WAN throughput delta speed computation using byte counters from `cmd 18`.
    - Fast telemetry polling for dashboard metrics, DHCP client list, 5GHz & 2.4GHz Wi-Fi maps, and system status.
    - Cached slow polling (every 20s) for heavy hardware telemetry (`cmd 1018`), cell-lock status (`cmd 160`), and band-lock switches (`cmd 161`).
    - LAN DNS query and save (`cmd 3`) with multi-poll verification.
    - Router reboot execution (`cmd 6`).
  - `AliasRepository`: Persists custom device aliases to `device_aliases.json` in app storage.
  - `PreferencesRepository`: Manages gateway IP, router credentials, and polling interval in `SharedPreferences`.
- **ViewModel & State** (`com.airtel.monitor.ui.viewmodel`):
  - `MonitorViewModel`: Background polling coroutine loop providing reactive `StateFlow<MonitorUiState>` for real-time UI updates.
- **Jetpack Compose UI** (`com.airtel.monitor.ui`):
  - `HeaderBar`: Brand title, live pulsing status dot, gateway info, poll rate dropdown (1.0s to 5.0s), and quick action buttons (Pause, Refresh, DNS, Reboot, Settings).
  - `HeroMetricCards`: Download speed, upload speed, 5G/4G cellular radio (4-bar signal indicator, RSRP/SINR stats), and router health (CPU & temperature gauges).
  - `BandwidthTimelineChart`: Native Compose `Canvas` rendering 60-second throughput history with cubic Bézier curves and dual vertical gradient fills.
  - `CellularCellLockCard`: Read-only serving 4G/5G PCI/EARFCN, active bands, LTE & NR cell lock pills.
  - `LanDnsCard`: Inline primary and secondary DNS editing and DHCP verification.
  - `DeviceFleetSection`: Connected devices with avatar emojis, rename modal, band tags, PHY link rates (Tx/Rx), signal meter bar, and tap-to-copy IP.
  - `Dialogs`: Modals for renaming devices, router reboot confirmation, and settings.

## Android 16/17 Permission: Local Network Protection
On Android 16+, apps targeting SDK 37+ are blocked from private LAN addresses (e.g. `192.168.1.1`) by default unless granted:
- Permission: `android.permission.ACCESS_LOCAL_NETWORK`
- Declared in `AndroidManifest.xml` and requested at runtime in `MainActivity`.

## Previous Version
The earlier Chaquopy (embedded Python + WebView) app lives in the git history;
its last commit is `140f576` (`git checkout 140f576`).

## Rebuild and Install from Command Line
```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:installDebug -x lint --console=plain
```
