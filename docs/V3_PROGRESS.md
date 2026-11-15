# Airtel 5G Monitor v3 — Rebuild Progress

Branch: `v3-rebuild` (branched from `main`)  
App version: `3.0.0-dev` (`versionCode` 10, `applicationId "dev.mattdev0.airtelmonitor"`)

---

## Phase 0 — Foundation & Refactor

**Status:** Complete (verified by unit tests, lint, and debug APK build; pending on-device / live-router smoke test)

### Implemented
- **Branch & Build Setup:**
  - Branched `v3-rebuild` off `main`, bumped `versionName` to `"3.0.0-dev"` and `versionCode` to `10` in `app/build.gradle`.
  - Added dependencies in `gradle/libs.versions.toml` and `app/build.gradle` for Navigation Compose (type-safe `@Serializable` routes), DataStore Preferences, `kotlinx-serialization-json`, `androidx.lifecycle:lifecycle-process`, and `androidx.compose.material3:material3-adaptive-navigation-suite`.
- **DI Container & Application:**
  - Added `AirtelMonitorApplication` holding `AppContainer` (`app/src/main/java/com/airtel/monitor/core/di/AppContainer.kt`) and registered it in `AndroidManifest.xml`.
- **Navigation Shell & Destinations:**
  - Defined `HomeRoute`, `SignalRoute`, `DevicesRoute`, `SettingsRoute`, and `TopLevelDestination` (`HOME`, `SIGNAL`, `DEVICES`, `SETTINGS`) in `ui/navigation/Routes.kt`.
  - Added `AppShell.kt` with `NavigationSuiteScaffold` (falling back to the floating `GlassNavBar` on compact width and Material 3 navigation rail/drawer on medium/expanded widths), `HeaderBar`, offline/auth error banner, snackbar host, and `ConfirmSheet` reboot confirmation.
  - Updated `GlassNavBar.kt` to bind to `TopLevelDestination.entries` with string resources and active-scheme luminance detection; updated `HeaderBar.kt` to remove DNS/Settings from the overflow menu and add an unread SMS badge action when `smsUnreadCount > 0`.
  - Updated `MainActivity.kt` to inject `AppContainer`, collect theme preference via `ShellViewModel`, and delegate polling lifecycle away from `Activity.onStart`/`onStop`.
- **Theme Tokens & Design System:**
  - Replaced cyan fallback colors with the Airtel red/coral dark + light Material 3 Expressive color schemes (`ui/theme/Color.kt`, `Theme.kt`), `AirtelTypography` / `TabularMonospace` (`Type.kt`), and semantic/spacing/shape composition locals (`AirtelTheme` in `Tokens.kt`).
  - Added `ui/designsystem/` primitives: `SectionCard`, `StatTile`, `SignalMeter` / `SignalBars`, `CarrierRow`, `SettingRow` / `SettingSwitchRow` / `SettingValueRow`, `ConfirmSheet`, `SkeletonBox` / `EmptyState` / `ErrorState`, `Charts`, and `QrCode`.
- **Networking, Session, SettingsWriter & Domain:**
  - Added `RouterResult` (`Ok`, `NotLoggedIn`, `LimitedAccess`, `Unreachable`, `BadResponse`), `RouterTransport` / `OkHttpRouterTransport`, `RouterSession` (CMD 232 SHA-256 challenge login, CMD 233 write token handling, serialized commands, single GET retry on `IOException`, zero POST retry on `IOException`), and `SettingsWriter` (read-before-write -> token -> POST -> re-read verification).
  - Moved polling lifecycle to `ProcessLifecycleOwner` in `PollingCoordinator` with `PollingMode.NORMAL` and `PollingMode.ALIGNMENT` (1s) plus foreground pause/resume.
  - Split telemetry and writes across `StatusRepository`, `SignalRepository`, `DeviceRepository`, `WifiRepository`, `MobileRepository`, `LanRepository`, and `SystemRepository`; kept `ZltRouterClient` as a facade for existing tests.
  - Added `CarrierComponent` (`CarrierTech`) and single-source parser/formatters (`parseCarriers`, `formatCaSummary`, `formatBandPill`, `formatCellPill` in `data/dto/CarrierParser.kt`), wired into `CellularCellLockCard.kt`, `HomeScreen.kt`, and `SignalScreen.kt`.
- **Encrypted Credentials & Preferences:**
  - Implemented `KeystoreCrypto` (`AES/GCM/NoPadding` backed by Android Keystore with testable `AesGcmCipher`), `AppPreferencesRepository` (DataStore Preferences + `ThemePreference` / `SpeedUnitPreference` / `maskSensitive` / `onboardingCompleted`), and one-time `CredentialMigration` from `airtel_monitor_prefs` deleting the legacy plaintext password only after encrypted save succeeds.
- **Per-Feature ViewModels & Feature Screens:**
  - Replaced `MonitorViewModel` and `MonitorDashboardScreen` with `ShellViewModel`, `HomeViewModel` + `HomeScreen`, `SignalViewModel` + `SignalScreen`, `DevicesViewModel` + `DevicesScreen`, and `SettingsHubViewModel` + `SettingsHubScreen` (settings loaded on open).
- **Tests & Fixtures:**
  - Added JSON test fixtures under `app/src/test/resources/fixtures/` (`205_ca.json`, `205_nsa.json`, `160_ca.json`, `161.json`, `223.json`, `225.json`, `1018.json`, `3.json`, `394.json`).
  - Added `CarrierParserTest`, `RouterDtoParsersTest`, `RouterSessionAndWriterTest`, `CryptoAndMigrationTest`, and `PollingCoordinatorTest`.

### Checks Run
- `sh ./gradlew testDebugUnitTest lintDebug assembleDebug --console=plain` — **PASSED** (`52` unit tests across 7 test suites: 0 skipped, 0 failures, 0 errors; `lintDebug` passed with 0 errors; `assembleDebug` produced the debug APK).

### Untested on Device / Live Router
- Runtime UI behavior on Android device/emulator: `AppShell` adaptive switching (`GlassNavBar` vs rail), `KeystoreCrypto` hardware-backed key generation/decryption on API 24–37, and DataStore first-launch migration from a populated `airtel_monitor_prefs`.
- Live ZLT X17M router integration against v3 `RouterSession` + `StatusRepository` + `SignalScreen` carrier breakdown + `SettingsHubScreen` load-on-open.
- SMS unread badge navigation currently routes to the Settings tab placeholder; full Messages screen lands in Phase 4.

### Open Questions / Deferred
- Confirm `ThemePreference` dynamic color opt-in exposure once App Settings lands in Phase 6 (`dynamicColor` currently defaults to `false` to preserve Airtel red/coral branding).
- Capture exact web UI payloads from the live router for neighbor cells (`cmd 282`, Phase 1), MAC filter (`html/firewall/macIndex.html`, `cmd 23`, Phase 2), and Wi-Fi advanced/SMS/security/system schemas ahead of Phases 1–5.
