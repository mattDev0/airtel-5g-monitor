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
  - Quick toggles for Mobile Data (`cmd 222`, verified via `cmd 218`), Wi-Fi 2.4 GHz (`cmd 2`), Wi-Fi 5 GHz (`cmd 211`), and Flight Mode (`cmd 226`, verified via `cmd 218`) with Tier-2 `ConfirmSheet` confirmation before changing radio state, plus quick navigation tiles to Signal, Devices, Messages, and Alignment Mode.
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
- Because write schemas must be confirmed from the router web UI HTML/JS and recorded under `Write schemas` in `docs/ROUTER_API.md` before writing Kotlin, `cmd 218` network-mode / `mode5g` / `lteCA` / `nrCA` SET switching is deferred and rendered read-only in Phase 1. Phases 2–5 SET features (`cmd 23`, `115`, `208`, `367`, `275`, `202`, `214`, `134`, `213`, `503`, `132` WPS, `350`, `13`, `14`, `16`, `102`, `112`) are verified from the captured web UI dump ahead of Phase 2+.

### Phase 1.1 Fixes (2026-11-15)
- Removed double status-bar inset in `AppShell.kt` (`contentWindowInsets = WindowInsets(0)` on `Scaffold`; status-bar inset kept once on `HeaderBar`) and set scroll `contentPadding.bottom` to `GlassNavBarHeight + navigationBarsInset + 28.dp` across all tabs so bottom controls (e.g. DNS buttons) sit cleanly above the floating nav bar.
- Extended `formatDurationText` to parse `D-H-M-S` strings (e.g. `0-1-19-52` -> `1 h 19 m`, `2-4-10-30` -> `2 d 4 h`).
- Relabelled Home hero cell tile to `Cell` with `393 · 2CC` (dropping the duplicate `PCI` prefix).
- Merged the 60 s DL/UL sparkline into `MinimalWanSpeedCard` and removed the duplicate throughput card.
- Replaced the misleading `Icons.Rounded.DataUsage` broken ring on the Monthly Data Usage card with a determinate circular progress ring only when a limit is active, and no progress indicator plus a `Set a limit` action when no limit is set.
- Removed the duplicate `B7 + B1 · 25 MHz` subtitle under Signal › Serving Carriers, keeping only the shared-element pill.
- Aligned Home signal bars (`parseSignalBarsLevel` in `RouterDtoParsers.kt` + 5-bar `SignalBars`) to prioritize `cmd 205` / `cmd 394` over stale `cmd 113` values on the router's `0..5` scale from `js/main.js`.
- Updated Alignment Mode (`PollingCoordinator`, `SignalViewModel`, `AlignmentScreen`) to format SINR to one decimal place, remove `cmd 205` internal jargon, poll at 2 s, show a refilling 10 s cadence ring + `Last update Xs ago` hint, and trigger haptics/tone/chart points only when RF values actually change.
- Allowed two lines in `DeviceFleetSection.kt` so `<rate> Mbps` link rates are never clipped by `maxLines = 1`.
- Fixed the Home quick-toggle command numbers above (`222`, `2`/`211`, `226`).
- **Phase 1.1 Checks Run:** `sh ./gradlew testDebugUnitTest lintDebug assembleDebug --console=plain` — **PASSED** (`61` unit tests across 9 test suites: 0 skipped, 0 failures, 0 errors; `lintDebug` passed with 0 errors and 8 SARIF entries; `assembleDebug` built `app-debug.apk`).

---

## Phase 2 — Devices

**Status:** Complete for read-only enrichment, filtering, OUI vendor lookup, and detail inspection (verified by unit tests, lint, and debug APK build; Phase 2 router write features such as static DHCP leases and MAC blocking remain deferred because their second-hop web UI sub-pages are missing from `~/airtelOdu-webui/` and must not be guessed)

### Implemented
- **Offline IEEE OUI Vendor & Private MAC Lookup (`OuiVendorLookup.kt`, `app/src/main/assets/oui.csv`):**
  - Built an offline `oui.csv` asset (`8,028` IEEE OUI entries mapped to major phone, laptop, router, IoT, and chipset vendors from `/usr/share/ieee-data/oui.csv` using word-boundary filtering).
  - Implemented `OuiVendorLookup` with formatted MAC helpers (`formatMac`), vendor lookup (`lookup`), and locally administered unicast MAC detection (`(firstOctet and 0x03) == 0x02`) for randomized/private MACs (`OuiMatch`).
  - Wired `OuiVendorLookup.fromAsset(...)` into `AppContainer` and `DeviceRepository`.
- **Device Enrichment & Alias Management (`RouterModels.kt`, `AliasRepository.kt`, `DeviceRepository.kt`):**
  - Added `DevicePresenceFilter` (`ALL`, `ONLINE`, `OFFLINE`) and extended `DeviceItem` with `vendor`, `isPrivateMac`, `isOnline`, and `hasCustomAlias`.
  - Extracted `AliasStore` in `AliasRepository.kt` and added `clearAlias` plus an `aliasVersion` flow in `DeviceRepository.kt` so renames/resets propagate immediately across UI collectors without waiting for the next poll.
  - Added `determineOnline` heuristic (expired DHCP leases with `expires == "0"` or wireless clients missing active `rssi`/`tx`/`rx` telemetry are marked offline) and OUI-based `DeviceType` refinement when `DEVICE`.
- **Devices Tab & Detail Bottom Sheet (`DevicesViewModel.kt`, `DevicesScreen.kt`, `DeviceDetailSheet.kt`, `strings.xml`):**
  - Added live device counts (`onlineCount`, `offlineCount`, `wifi5gCount`, `wifi24gCount`, `lanCount`), `presenceFilter`, live-recomputed `selectedDevice`, `clearFilters()`, `clearAlias()`, and vendor/IPv6/SSID/`"private randomized"` search matching in `DevicesViewModel`.
  - Rebuilt `DevicesScreen` with interactive summary `StatTile` cards, search bar + presence/band/sort filter chips, `EmptyState` with `"Clear filters"`, rich device rows (online status dot, `"Renamed"` badge, vendor/private MAC subtitle, 5-bar signal/Ethernet/offline indicator, quick rename action), and `DeviceDetailSheet` (`ModalBottomSheet` with copyable MAC/IPv4/IPv6/hostname, vendor & private-MAC explanation, link rate, signal bars, formatted lease expiry, Rename, and Reset name).
- **Web UI Write Schema Audit (`docs/ROUTER_API.md`):**
  - Added `## Write schemas` in `docs/ROUTER_API.md` documenting verified `POST` payloads (`cmd 2`, `211`, `3`, `6`, `51`, `102`, `160`, `161`, `168`, `222`, `226`, `288`, `367`) and cataloging uncaptured second-hop `secondMenuClick` sub-pages in `~/airtelOdu-webui/`.
- **Tests:**
  - Added `OuiVendorLookupTest.kt` (`3` tests) and `DeviceRepositoryAndViewModelTest.kt` (`2` tests).

### Checks Run
- `sh ./gradlew testDebugUnitTest lintDebug assembleDebug --console=plain` — **PASSED** (`66` unit tests across `11` test suites: `0` skipped, `0` failures, `0` errors; `lintDebug` passed with `0` errors and `39` warnings; `assembleDebug` built `app-debug.apk`).

### Untested on Device / Live Router
- Runtime Compose rendering and clipboard copy feedback in `DevicesScreen` and `DeviceDetailSheet` against live `cmd 223` / `cmd 225` / `cmd 3` router payloads.
- Confirming real firmware `expires` and wireless association edge cases for offline DHCP lease detection across extended lease expiry on the ZLT X17M.

### Blocked / Deferred
- Second-hop `secondMenuClick` HTML/JS files for Phase 2 write actions are absent from `~/airtelOdu-webui/`, so these writes were intentionally omitted rather than guessing router schemas:
  - Static DHCP reservations (`cmd 115`: `html/config/dhcpSetting.html/.js`)
  - MAC filtering & default policy (`cmd 23` / `cmd 30`: `html/firewall/macFilter.html`, `defaultMac.html`)
  - Wireless 2.4 / 5 GHz MAC filters (`cmd 278`: `html/config/wifiList24g.html`, `wifiList5g.html`)
  - Parental controls (`cmd 382` / `383` / `385` / `391`: `html/parent/parentControl.html`, `parentSpeed.html`, `parentTime.html`, `parentUrl.html`, gated by `parentControlSwitch` / `pageHideCheck(153)`)


