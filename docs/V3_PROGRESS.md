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

---

## Phase 1 — Home + Signal

**Status:** Partially complete (read telemetry, charts, neighbor cells, Antenna Alignment Mode, and existing locks verified by unit tests, lint, and debug APK build; `cmd 218` network-mode/CA SET switching deferred pending router web UI HTML/JS schema capture)

### Implemented
- **Domain Models & Phase 1 Parsers:**
  - Added `LinkDetails`, `CellIdentity`, `NeighborCell`, `SignalHistoryPoint`, and `AlignmentSnapshot` in `app/src/main/java/com/airtel/monitor/domain/SignalModels.kt` plus `MonthlyUsageSummary` in `app/src/main/java/com/airtel/monitor/domain/UsageModels.kt`.
  - Extended `CellularMetrics` (5G/4G RSRQ, RSSI, `has5gMetrics`) and `RouterState` (`wanIp`, `wanIpv6`, `onlineDurationText`, `uptimeText`, `linkDetails`, `cellIdentity`, `monthlyUsage`, `networkModeInfo`) in `data/model/RouterModels.kt`.
  - Extended `RouterDtoParsers.kt` (`deviceInfo207` hardware/uptime fallbacks and `routerInfo133` cellular RSRQ/RSSI/network fallbacks) and added `Phase1Parsers.kt` (`parseLinkDetails`, `parseCellIdentity`, `parseNeighborCells` handling JSON arrays and comma-separated tuples, `parseSignalHistory1018`, `parseAlignmentSnapshot`, `parseMonthlyUsage`, `calculateDaysLeftInCycle`, `formatDurationText`, `formatDataSizeMb`).
- **Repositories & Polling:**
  - Updated `StatusRepository.kt` to poll `cmd 133`, `207`, and `337` on the 20s slow refresh cadence, combine `cmd 1018` history with 10s-sampled live `cmd 205` points, expose `signalHistory` and `alignmentSnapshot`, and run `pollAlignment()` (`cmd 205` only).
  - Updated `SignalRepository.kt` to expose `neighborCells` and `loadNeighborCells()` (`cmd 282`), plus `rescanNetwork()` (`cmd 288`).
- **Home Tab (`HomeViewModel.kt`, `HomeScreen.kt`):**
  - Hero status card with network-type badge, aggregated CA summary pill wired to `SharedTransitionLayout`, inline signal bars, compact primary RSRP/SINR readouts, WAN IP, and uptime/online chips.
  - 60-second DL/UL throughput `LineChart` sparkline + current rates and session totals.
  - Monthly data usage card (`cmd 401` + `cmd 337`) with progress indicator, billing-cycle days left, and data-warning badge.
  - Quick toggles for Mobile Data (`cmd 4`), Wi-Fi 2.4 GHz (`cmd 224` / `202`), Wi-Fi 5 GHz (`cmd 225` / `202`), and Flight Mode (`cmd 132`) with Tier-2 `ConfirmSheet` confirmation before changing radio state, plus quick navigation tiles to Signal, Devices, Messages, and Alignment Mode.
- **Signal Tab & Alignment Mode (`SignalViewModel.kt`, `SignalScreen.kt`, `AlignmentScreen.kt`, `AlignmentTonePlayer.kt`, `LockEditorSheet.kt`, `AppShell.kt`, `Routes.kt`):**
  - Restyled `LockEditorSheet.kt` onto `AirtelTheme.colors` semantic tokens and added a serving-carriers strip (`CarrierRow`) inside the sheet.
  - Signal tab displays the shared-element CA pill, serving-carrier `CarrierRow` breakdown, conditional 5G/4G `SignalMeter` bars, Link Details card, Copyable Cell Identity card (long-press clipboard copy via `LocalResources`), ~10-minute RSRP and SINR `LineChart` graphs, Neighbor Cells (`cmd 282`) with load button and format handling, read-only `cmd 218` Network Mode / CA status display, Cell/Band lock summary + editor, and Tier-2 confirmed Re-search Network (`cmd 288`).
  - Added `AlignmentRoute` and full-screen `AlignmentScreen` with 1s `PollingMode.ALIGNMENT` (`cmd 205`), `view.keepScreenOn`, best RSRP/SINR tracking + delta chips + reset, 30s trend `LineChart`, haptic feedback ticks, and `AlignmentTonePlayer` (pitch-scaled `AudioTrack` PCM pip) with sound mute toggle.
- **Tests & Fixtures:**
  - Added redacted test fixtures `133.json`, `207.json`, `282.json`, and `337.json` under `app/src/test/resources/fixtures/`.
  - Added `Phase1ParsersTest.kt` (6 tests) and `StatusRepositoryPhase1Test.kt` (1 test).

### Checks Run
- `sh ./gradlew testDebugUnitTest lintDebug assembleDebug --console=plain` — **PASSED** (`59` unit tests across 9 test suites: 0 skipped, 0 failures, 0 errors; `lintDebug` passed with 0 errors and 8 SARIF entries; `assembleDebug` produced the debug APK).

### Untested on Device / Live Router
- Runtime UI behavior on Android device/emulator: `SharedTransitionLayout` motion between Home and Signal carrier pill, `AlignmentTonePlayer` audio playback on hardware speakers, `view.keepScreenOn`, and haptic feedback triggers.
- Live ZLT X17M validation of `cmd 282` (`neighbor_info`), `cmd 337` (`limitFlow`), `cmd 133` (`tx_mcs` / `rx_mcs` / `dl_qam` / `ul_qam`), and `cmd 207` (`uptime`) against the real router firmware.
- Home "Messages" quick tile currently routes to the Settings tab placeholder until Phase 4 adds the Messages screen.

### Blocked / Deferred
- **No LAN route to `http://192.168.1.1` from this build environment:** `curl -sS --max-time 5 http://192.168.1.1/index.html` timed out (`Connection timed out after 5002 milliseconds`).
- Because write schemas must be confirmed from the router web UI HTML/JS and recorded under `Write schemas` in `docs/ROUTER_API.md` before writing Kotlin, `cmd 218` network-mode / `mode5g` / `lteCA` / `nrCA` SET switching is deferred and rendered read-only in Phase 1. Phases 2–5 SET features (`cmd 23`, `115`, `208`, `367`, `275`, `202` WEP/hide, `214`, `134`, `213`, `503`, `132` PIN, `350`, `13`, `14`, `16`, `102`, `112`) are similarly blocked pending router web UI HTML/JS captures.
