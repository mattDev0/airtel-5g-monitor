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

### Blocked / Deferred *(Write schemas resolved across Phases 2–7.1; build sandbox offline from 192.168.1.1)*
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

### Blocked / Deferred *(Resolved in Phase 7.1 below)*
- Second-hop `secondMenuClick` HTML/JS files for Phase 2 write actions are absent from `~/airtelOdu-webui/`, so these writes were intentionally omitted rather than guessing router schemas:
  - Static DHCP reservations (`cmd 115`: `html/config/dhcpSetting.html/.js`)
  - MAC filtering & default policy (`cmd 23` / `cmd 30`: `html/firewall/macFilter.html`, `defaultMac.html`)
  - Wireless 2.4 / 5 GHz MAC filters (`cmd 278`: `html/config/wifiList24g.html`, `wifiList5g.html`)
  - Parental controls (`cmd 382` / `383` / `385` / `391`: `html/parent/parentControl.html`, `parentSpeed.html`, `parentTime.html`, `parentUrl.html`, gated by `parentControlSwitch` / `pageHideCheck(153)`)


---

## Phase 3 — Settings: Wi-Fi, Mobile & SIM PIN, LAN/DHCP

**Status:** Complete for verified Wi-Fi credentials & radio controls, offline Wi-Fi QR sharing, SIM PIN/PUK management, full LAN/DHCP/DNS editing, Mobile controls, Network Diagnosis, and App Preferences (unit tests, lint, and debug APK verified; deferred second-hop sub-pages absent from `~/airtelOdu-webui/`)

### Implemented
- **Offline QR Generator & Input Validators (`QrCodeGenerator.kt`, `WifiAndLanValidators.kt`, `RouterDtoParsers.kt`):**
  - Implemented pure-Kotlin ISO/IEC 18004 QR Code encoder (`Byte` mode, ECC Level `M`, Versions 1–10 up to 213 UTF-8 bytes) plus `formatWifiQrPayload` (`WIFI:T:...;S:...;P:...;;`) with zero external Maven dependencies.
  - Added ZLT web-UI validation rules from `js/step_rules.js`: SSID UTF-8 byte length (`1..32`), whitespace/semicolon/backslash guards, `cmd 80` forbid-list enforcement, WPA key validation (`8..63` chars), IPv4/contiguous subnet-mask/DHCP pool checks, and 4–8 digit PIN / 8-digit PUK checks.
  - Added pure-Kotlin `encodeBase64Utf8` in `RouterDtoParsers.kt` for JVM unit tests and API 24+ compatibility.
- **Models & Safe Write Pipeline (`Phase3Models.kt`, `SettingsWriter.kt`, `WifiRepository.kt`, `LanRepository.kt`, `SimRepository.kt`, `AppContainer.kt`):**
  - Added `WifiBandDetail`, `WifiBandDetails`, `LanConfig`, and `SimPinState`.
  - Added defaulted `verifyPost` hook to `WriteSpec` in `SettingsWriter.kt` so router replies with `{ "success": true, "message": "<nonzero>" }` (such as `cmd 51`) fail cleanly.
  - Extended `WifiRepository` with `bandDetails`, `loadWifiBandDetails()` (including best-effort `cmd 80` `wifi_ssid_forbid` / `wifi_pwd_forbid`), and `saveWifiBandConfig(...)` (Base64 SSID, security mode, key, hidden broadcast, `wifiSames`, and optional paired 2.4 GHz + 5 GHz sync).
  - Extended `LanRepository` with `lanConfig`, `loadLanConfig()`, and `saveLanDhcpSettings(...)` preserving all `LAN_SAVE_KEYS` and `bindPort0` when `supportList` is present.
  - Implemented `SimRepository` (`loadSimStatus`, `setPinLockEnabled`, `unlockPin`, `unlockPuk`, `changePin`) across `cmd 113`, `cmd 134`, and `cmd 51`, refreshing remaining attempt counters after any rejected write.
- **Settings Hub Material 3 Refactor & Bottom Sheets (`SettingsHubViewModel.kt`, `SettingsSheets.kt`, `SettingsHubScreen.kt`, `strings.xml`):**
  - Replaced legacy `LanDnsCard.kt` and `WifiRadiosCard.kt` with Material 3 `SectionCard` + `SettingRow` / `SettingSwitchRow` / `SettingValueRow` sections (`Router Connection`, `Wi-Fi Networks`, `Mobile & SIM`, `LAN & DHCP`, `Diagnostics & Tools`, `App Preferences`).
  - Added Tier-2 connectivity `ConfirmSheet` guards before toggling Wi-Fi radios, changing Wi-Fi credentials, saving LAN/DHCP changes, or toggling Mobile Data / Airplane Mode.
  - Added bottom sheets for editing 2.4 GHz / 5 GHz Wi-Fi (`WifiEditSheet`), sharing credentials via in-app QR (`WifiQrSheet`), managing SIM PIN/PUK (`SimPinSheet`), and editing Router IP / Subnet Mask / DHCP Pool / Lease / DNS (`LanDhcpEditorSheet`, with optional auto-update of the app's stored router host if gateway IP changes).
  - Added App Preferences persistence controls (`ThemePreference`, `SpeedUnitPreference`, `maskSensitive`) and updated `docs/ROUTER_API.md` under `## Write schemas`.
- **Tests:**
  - Added `QrCodeGeneratorTest.kt` (`4` tests covering `WIFI:` URI escaping, QR matrix sizing V1/V2/V3/V10, finder/timing/dark-module invariants, and `WifiAndLanValidators`).
  - Added `WifiLanSimAndSettingsTest.kt` (`4` tests covering `WifiRepository`, `LanRepository`, `SimRepository`, and `SettingsHubViewModel` host migration).

### Checks Run
- `sh ./gradlew testDebugUnitTest lintDebug assembleDebug --console=plain` — **PASSED** (`74` unit tests across `13` test suites: `0` skipped, `0` failures, `0` errors; `lintDebug` passed with `0` errors and `37` warnings; `assembleDebug` built `app-debug.apk`).

### Untested on Device / Live Router
- Live ZLT X17M validation of `cmd 2` / `cmd 211` SSID/key/security edits, paired `wifiSames` behavior, and client Wi-Fi reconnection after credential change.
- Live `cmd 134` / `cmd 51` SIM PIN lock toggle, unlock, and change behavior (**never** intentionally enter wrong PINs or test PUK against an active production SIM).
- Live `cmd 3` DHCP pool, subnet mask, and gateway IP edit with app host migration (`updateHostIfChanged`) and QR code scanning compatibility on Pixel 6 / iPhone 12 Pro.

### Blocked / Deferred *(Resolved in Phase 7.1 below)*
- Uncaptured second-hop `secondMenuClick` pages absent from `~/airtelOdu-webui/` (write payloads omitted rather than guessed):
  - Wi-Fi TX power (`cmd 411`), WPS (`cmd 132`), Wi-Fi schedule (`cmd 503`), Guest Wi-Fi (`cmd 209`/`210`), and advanced channel/bandwidth forms.
  - Mobile network mode / 5G SA/NSA / LTE+NR CA selector (`cmd 218` SET), data roaming switch (`cmd 220` SET), and APN profile editor (`cmd 213`).
- `SpeedUnitPreference` and `maskSensitive` are persisted from Settings UI; applying them across Home/Signal speed formatters and Phase 5 About Router / IMEI/ICCID rows completes in Phases 5–6.
---

## Phase 4 — Messages & Data Usage

**Status:** Complete for SMS Inbox/Outbox pagination, mark-read, compose/reply/forward, delete, SMS settings, and billing-cycle / local daily data-usage history (verified by unit tests, lint, and debug APK build; USSD `cmd 350`, `cmd 337` limit SET, and remote `cmd 405–408` stats tables deferred because their second-hop sub-pages are absent from `~/airtelOdu-webui/`)

### Implemented
- **Web UI Schemas & Validation (`docs/ROUTER_API.md`, `Phase4Models.kt`, `SmsValidators.kt`):**
  - Documented verified `POST` schemas for `cmd 12` (mark read / `"READ ALL"` + `page_num` / `subcmd` Base64 row decoding), `cmd 13` (Inbox `subcmd 0` / Outbox `subcmd 1` single, batch, and `"DELETE ALL"`), `cmd 14` (`phoneNo` + Base64 `content` + `message == "502"` storage-full handling), and `cmd 16` (`smsSw`, `maxSize`, conditional `dmCsca` when `smscSw == "1"`) under `## Write schemas`.
  - Implemented `SmsValidators` matching `js/sms/smsInbox.js` and `js/sms/smsSetting.js`: `/^[\d+]\d{1,30}$/` recipient format, ZLT weighted length (`< 0x007F` = 1, `>= 0x007F` = 2), UTF-16 surrogate emoji rejection, and `50..1000` capacity bounds.
- **SMS & Daily Usage Data Layer (`SmsRepository.kt`, `DailyUsageStore.kt`, `StatusRepository.kt`, `AppContainer.kt`):**
  - Implemented `SmsRepository` handling Inbox/Outbox paged reads, `$`-to-space sender decoding, mark-read / mark-all-read with immediate unread badge synchronization (`cmd 394` / `StatusRepository.updateSmsUnreadCount`), SMS sending with `502` handling, single/all delete per folder, and `cmd 16` configuration save via `SettingsWriter`.
  - Implemented `DailyUsageStore` (`InMemoryDailyUsageStore` + `SharedPreferencesDailyUsageStore`) retaining up to 31 days of locally tracked daily usage deltas from monotonic cumulative totals (including cross-day baseline carryover and mid-day billing cycle resets) and hooked it into `StatusRepository.pollFast`.
- **Navigation, Messages & Data Usage UI (`Routes.kt`, `AppShell.kt`, `HomeScreen.kt`, `SettingsHubScreen.kt`, `MessagesViewModel.kt`, `MessagesScreen.kt`, `MessagesSheets.kt`, `DataUsageViewModel.kt`, `DataUsageScreen.kt`, `strings.xml`):**
  - Added dedicated `MessagesRoute` and `DataUsageRoute` sub-screens in `AppShell.kt`, wired from the top `HeaderBar` unread SMS icon, Home `Messages` quick tile, Home `MonthlyDataUsageCard`, and Settings shortcuts.
  - Built `MessagesScreen` + `MessagesSheets` with Inbox/Outbox segmented switcher, storage-full & disabled banners, Mark All Read, Next/Previous pagination, `MessageDetailSheet` (Reply, Forward, Delete), `ComposeSmsSheet` (with weighted counter and Tier-2 carrier-charge `ConfirmSheet`), and `SmsSettingsSheet`.
  - Built `DataUsageScreen` displaying monthly progress ring, DL/UL split, plan limit, warning threshold, cycle day, remaining days, `LineChart` multi-day trend, and per-day breakdown with `Clear local history`.
- **Tests:**
  - Added `MessagesAndUsageTest.kt` (`4` tests covering `SmsValidators`, `SmsRepository` parsing/mark-read/Outbox delete/`502` send/conditional `dmCsca` settings, `InMemoryDailyUsageStore` delta & cycle-reset math, and `MessagesViewModel` + `DataUsageViewModel`).

### Checks Run
- `sh ./gradlew testDebugUnitTest lintDebug assembleDebug --console=plain` — **PASSED** (`78` unit tests across `14` test suites: `0` skipped, `0` failures, `0` errors; `lintDebug` passed with `0` errors and `37` warnings (`0` new warnings); `assembleDebug` built `app-debug.apk`).

### Untested on Device / Live Router
- Live ZLT X17M `cmd 12` Inbox/Outbox multi-page browsing, unread sync against `cmd 394`, and mark-read confirmation.
- Tier-2 `cmd 14` SMS send, `cmd 13` delete, and `cmd 16` SMS settings persistence on real carrier SIM (never auto-sent against live router).
- Multi-day accumulation in `SharedPreferencesDailyUsageStore` across live router polls.

### Blocked / Deferred *(Resolved in Phase 7.1 below)*
- Uncaptured second-hop `secondMenuClick` pages absent from `~/airtelOdu-webui/`:
  - USSD dialer (`cmd 350`: `html/manage/ussd.html/.js`)
  - Remote traffic limit / cycle write editor (`cmd 337` SET: `html/config/trafficLimit_settings.html/.js`)
  - Remote router daily/hourly stats tables (`cmd 405–408`: `html/industry/trafficStat.html/.js`; local snapshot tracking fills in daily usage history)

---

## Phase 5 — Security, System & Tools

**Status:** Complete for verified Security (`cmd 367` firewall, `cmd 504` ALG switches), About Router (`cmd 207`/`133`/`113` with mask & tap-to-reveal), Device Self-Check (`cmd 264`), Router Time (`cmd 11`), Scheduled Reboot (`cmd 263`), System Logs (`cmd 322`/`17`), Change Admin Password (`cmd 102` with app credential sync), Tier-3 `REBOOT` confirmation, and user-triggered Cloudflare Speed Test (unit tests, lint, and debug APK verified; deferred second-hop sub-pages absent from `~/airtelOdu-webui/`)

### Implemented
- **Web UI Schemas & Validation (`docs/ROUTER_API.md`, `Phase5Models.kt`, `SensitiveFormatters.kt`):**
  - Documented verified `POST` payloads for `cmd 17` (`SYS_LOG`), `cmd 102` (`CHANGE_PASSWD`), `cmd 263` (`TIME_TO_RESTART`), `cmd 322` (`LOG_INFO`), `cmd 367` (`FW_SWITCH_SETTING`), and `cmd 504` (`ALG_SETTING`) under `## Write schemas`, plus deferred uncaptured `secondMenuClick` pages (`dmz`, `upnp`, `ddos`, `portForward`, `ipFilter`, `urlFilter`, `timeSet`, `wanLinkDetect`, standalone `restoreFactory`).
  - Implemented `SensitiveFormatters` (`maskIdentifier`, `validateRouterPassword` matching `js/index.js:validatePass`, `validateLogSizeKb`, `formatUptimeSeconds`).
- **Session, Security, System & Speed Test Data Layer (`RouterSession.kt`, `SecurityRepository.kt`, `SystemRepository.kt`, `SpeedTestRunner.kt`, `AppContainer.kt`):**
  - Extended `RouterSession` to record `accountLevel` from `cmd 100` login (`currentAccountLevel()`, reset on `resetSession()`).
  - Implemented `SecurityRepository` with `SettingsWriter` verification for firewall (`cmd 367`) and 7 ALG flags (`cmd 504`).
  - Extended `SystemRepository` with `loadAboutInfo` (`cmd 207` + `cmd 133`/`cmd 113`), `runSelfCheck` (`cmd 264`), `loadRouterTime` (`cmd 11`), `loadScheduledReboot` / `saveScheduledReboot` (`cmd 263`), `loadLogSettings` / `saveLogSettings` (`cmd 322`), `loadSysLog` / `clearSysLog` (`cmd 17`), and `changeAdminPassword` (`cmd 102`).
  - Implemented `SpeedTestRunner` (ping latency, jitter, download/upload Mbps against `speed.cloudflare.com/__down` / `__up`, invoked only on explicit user Start).
- **Security & System Tools UI (`SecurityViewModel.kt`, `SecurityScreen.kt`, `SystemToolsViewModel.kt`, `SystemToolsScreen.kt`, `SystemToolsSheets.kt`, `Routes.kt`, `SettingsHubScreen.kt`, `AppShell.kt`, `strings.xml`):**
  - Added `SecurityRoute` and `SystemToolsRoute` sub-screens reachable from `SettingsHubScreen`.
  - Built `SecurityScreen` with Tier-2 firewall-disable confirmation, protection level selector, and 7 ALG protocol toggles.
  - Built `SystemToolsScreen` + `SystemToolsSheets` featuring About Router (masking sensitive IMEI/IMSI/ICCID/serial/MSISDN/EID/MAC identifiers with tap-to-reveal per field), on-demand `cmd 264` self-check, inline Cloudflare speed test card, Scheduled Reboot editor sheet, System Log viewer/level/clear sheet, `cmd 102` Password Change sheet (automatically syncing app password, resetting session, and triggering poll refresh on success), and Tier-3 `"REBOOT"` confirmation across System Tools and HeaderBar reboot.
- **Tests:**
  - Added `SecurityAndSystemTest.kt` (`4` tests covering `SensitiveFormatters`, `SecurityRepository` + `SecurityViewModel`, `SystemRepository` + `SystemToolsViewModel` incl. `cmd 207`/`17`/`263`/`322`/`102`, and offline `SpeedTestRunner` via OkHttp mock `Interceptor`).

### Checks Run
- `sh ./gradlew testDebugUnitTest lintDebug assembleDebug --console=plain` — **PASSED** (`82` unit tests across `15` test suites: `0` skipped, `0` failures, `0` errors; `lintDebug` passed with `0` errors and `37` SARIF entries (`0` new warnings); `assembleDebug` built `app-debug.apk`).

### Untested on Device / Live Router
- Live ZLT X17M `cmd 367` firewall, `cmd 504` ALG, `cmd 263` scheduled reboot, `cmd 322` / `cmd 17` syslog, `cmd 264` self-check, and `cmd 102` password change behavior.
- Live Cloudflare speed test execution on device cellular/Wi-Fi WAN link (tested against mock OkHttp `Interceptor` offline).

### Blocked / Deferred *(Resolved in Phase 7.1 below)*
- Uncaptured second-hop `secondMenuClick` pages absent from `~/airtelOdu-webui/` (`dmz`, `upnp`, `ddos`, `portForward`, `ipFilter`, `urlFilter`, `timeSet`, `wanLinkDetect`, standalone `restoreFactory` `cmd 112`).

---

## Phase 6 — Onboarding, Worker, Notifications, Widget & Speed Units

**Status:** Complete (WorkManager 15-minute Wi-Fi-gated background monitor, deduplicated alerts across 4 notification channels, API 33+ contextual `POST_NOTIFICATIONS` prompt, 2x2 & 4x2 `RemoteViews` home-screen widgets, first-launch gateway auto-detection onboarding wizard, and `Mbps` / `MB/s` speed-unit toggle)

### Implemented
- **Dependencies & Preferences (`gradle/libs.versions.toml`, `app/build.gradle`, `AppPreferencesRepository.kt`, `PreferencesRepository.kt`, `NetworkFormatters.kt`):**
  - Added `androidx.work:work-runtime-ktx` (`2.10.3`) and aligned cached `lifecycleCompose` (`2.11.0`) + `savedstate` (`1.5.0`) constraints (using native `AppWidgetProvider` + `RemoteViews` instead of `androidx.glance` since Glance artifacts are not in the local offline cache).
  - Extended `AppPreferencesSnapshot` with `isLoaded`, `backgroundMonitorEnabled`, and notification toggles (`notifyQuotaWarning`, `notifyNewSms`, `notifyNewDevice`, `notifyUnreachable`); automatically marks `onboardingCompleted = true` when legacy v2 credentials migrate.
  - Added `NetworkFormatters.formatThroughput` and `formatPeakThroughput` supporting `Mbps` vs `MB/s` (`/ 8.0`) and wired `speedUnit` into `MinimalWanSpeedCard` (`HeroMetricCards.kt`, `HomeViewModel.kt`, `HomeScreen.kt`).
- **Gateway Detection & Notification Deduplication (`GatewayDetector.kt`, `MonitorAlertEvaluator.kt`, `MonitorStateStore.kt`, `RouterNotificationHelper.kt`):**
  - Implemented `GatewayDetector` (`ConnectivityManager` active Wi-Fi transport default route / DHCP IPv4 server extraction + `/24` expected-subnet check).
  - Implemented `MonitorAlertEvaluator` pure-Kotlin state machine + `MonitorStateStore` (`InMemoryMonitorStateStore` / `SharedPreferencesMonitorStateStore`) deduplicating quota warnings (once per cycle), unread SMS count deltas (silent initial baseline), opt-in new MAC discovery (normalized, silent baseline), and 2x consecutive poll failures (`RouterUnreachable` + single `RouterRecovered`).
  - Created 4 notification channels (`quota`, `sms`, `devices`, `connectivity`) in `RouterNotificationHelper` with API 33+ permission guard and deep-link `PendingIntent`.
- **Background Worker & Home-Screen Widgets (`RouterMonitorWorker.kt`, `RouterMonitorScheduler.kt`, `RouterWidgetStore.kt`, `RouterWidgetProviders.kt`, `res/layout/widget_router_*.xml`, `res/drawable/widget_*.xml`, `res/xml/router_widget_*_info.xml`, `AndroidManifest.xml`, `AppContainer.kt`, `AirtelMonitorApplication.kt`):**
  - Added 15-minute `PeriodicWorkRequest` + immediate tap-to-refresh `OneTimeWorkRequest`; skips off-Wi-Fi runs without incrementing unreachable count and pushes live snapshots on both background worker runs and foreground coordinator ticks.
  - Added 2x2 (`RouterCompactWidgetProvider`) and 4x2 (`RouterExpandedWidgetProvider`) home-screen widgets showing network type, 5-bar signal level, RSRP (4x2), today/monthly data, connected client count, status timestamp, and tap-to-refresh action.
  - Declared `POST_NOTIFICATIONS` and widget receivers in `AndroidManifest.xml`, and ensured notification channels & WorkManager schedule sync on app start without prompting permissions on launch.
- **Onboarding Wizard & Notification Settings (`OnboardingViewModel.kt`, `OnboardingScreen.kt`, `SettingsHubViewModel.kt`, `SettingsHubScreen.kt`, `ShellViewModel.kt`, `Routes.kt`, `AppShell.kt`, `strings.xml`):**
  - Built first-launch `OnboardingScreen` pre-filling detected Wi-Fi gateway IP, warning when not on Wi-Fi, running isolated login/info probes distinguishing wrong credentials from unreachable hosts, and offering `Run Setup Wizard` + `App Version` in `SettingsHubScreen`.
  - Gated initial onboarding route on `shellState.preferencesLoaded && !shellState.onboardingCompleted` to avoid first-launch flicker, and added contextual `POST_NOTIFICATIONS` request in `SettingsHubScreen` when enabling Background Monitoring.
- **Tests:**
  - Added `OnboardingAndMonitorTest.kt` (`6` tests covering `GatewayDetector`, `MonitorAlertEvaluator` quota/SMS/device/unreachable state transitions, `NetworkFormatters` throughput units, `WidgetFormatter.buildSnapshot`, and `OnboardingViewModel` probes & save).

### Checks Run
- `sh ./gradlew testDebugUnitTest lintDebug assembleDebug --console=plain` — **PASSED** (`88` unit tests across `16` test suites: `0` skipped, `0` failures, `0` errors; `lintDebug` passed with `0` errors and `35` SARIF entries (`0` new warnings); `assembleDebug` built `app-debug.apk`).

### Untested on Device / Live Router
- End-to-end WorkManager 15-minute periodic trigger, `POST_NOTIFICATIONS` runtime prompt on Android 13+ device, and home-screen widget pinning / tap-to-refresh on a physical launcher.
- Live router onboarding probe against physical ZLT X17M/W304VA PRO gateway IP.

---

## Phase 7 — Polish, Accessibility, Light Theme & R8 Check

**Status:** Complete for Android V3 (`Phases 0–7` verified on `v3-rebuild` with zero compiler deprecation warnings, zero lint errors, R8 release shrinking passing cleanly, and `README.md` updated; `Phase 8` iOS parity waits on Android review)

### Implemented
- **Light Theme Contrast & System Bars (`Theme.kt`, `NetworkDiagnosisCard.kt`, `CellularCellLockCard.kt`, `Dialogs.kt`):**
  - Migrated remaining legacy dark-only `StatusSuccess` / `StatusWarning` / `StatusError` references in components/dialogs to `AirtelTheme.colors` (`success` / `warning` / `error`) so light-theme surfaces meet AA contrast.
  - Added a `SideEffect` inside `AirtelMonitorTheme` updating `WindowCompat.getInsetsController` `isAppearanceLightStatusBars` / `isAppearanceLightNavigationBars` when users switch Appearance between System, Light, and Dark inside the app.
- **200% Font Scale, Predictive Back, Plurals & Tablet Adaptive Layout (`AppShell.kt`, `HeroMetricCards.kt`, `HomeScreen.kt`, `DataUsageScreen.kt`, `MessagesScreen.kt`, `SignalMeter.kt`, `AndroidManifest.xml`, `strings.xml`, `colors.xml`):**
  - Replaced `currentWindowAdaptiveInfo()` with `currentWindowAdaptiveInfoV2()` in `AppShell.kt` and bounded wide tablet content columns (`widthIn(max = 880.dp)`) when rendering `NavigationRail` / `NavigationDrawer`.
  - Enabled `android:enableOnBackInvokedCallback="true"` (`tools:targetApi="tiramisu"`) in `AndroidManifest.xml` for predictive back gestures.
  - Replaced `rememberModalBottomSheetState(skipPartiallyExpanded = true)` across `ConfirmSheet.kt`, `DeviceDetailSheet.kt`, `MessagesSheets.kt`, `SettingsSheets.kt`, and `SystemToolsSheets.kt` with non-deprecated `rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))`.
  - Wrapped `MinimalWanSpeedCard` headers/footers in `FlowRow`, bounded `ConnectionHeroCard` stat tiles with `widthIn(min = 96.dp)`, moved hardcoded speed-card labels to `strings.xml`, converted `cd_signal_bars` / `home_usage_days_left_fmt` / `tile_messages_unread_fmt` to `<plurals>`, and removed unused `colors.xml` / `strings.xml` entries.
  - Eliminated all `ModifierParameter`, `DefaultLocale`, `EmptySuperCall`, `PluralsCandidate`, and `UnusedResources` findings across Android source/resources.
- **Recomposition Performance & R8 Release Verification (`RouterModels.kt`, `HomeViewModel.kt`, `SignalViewModel.kt`, `DevicesViewModel.kt`, `HomeScreen.kt`, `SignalScreen.kt`, `HeroMetricCards.kt`, `keepRules/rules.keep`):**
  - Marked `RouterState`, nested metric/device models, and `HomeUiState` / `SignalUiState` / `DevicesUiState` `@Immutable`, passed granular slices instead of full `RouterState` to `ConnectionHeroCard` & `MonthlyDataUsageCard`, and `remember`-cached chart/sparkline list transforms against the 2-second poll tick.
  - Populated `app/src/main/keepRules/rules.keep` for `@Serializable` Navigation routes, `RouterMonitorWorker`, and widget `AppWidgetProvider`s (`signingConfig` untouched).
- **Documentation (`README.md`):**
  - Updated `README.md` for V3 Android capabilities, local verification commands, firmware/uncaptured limits, and `Phase 8` iOS roadmap.

### Checks Run
- `sh ./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease --console=plain` — **PASSED** (`88` unit tests across `16` test suites: `0` skipped, `0` failures, `0` errors; `compileDebugKotlin` & `compileReleaseKotlin` clean with `0` warnings; `lintDebug` passed with `0` errors and `21` total SARIF findings (`1` non-dependency item: intentional synchronous legacy pref cleanup `commit()` in `AppPreferencesRepository.kt:107`); `lintVitalRelease` & R8 `minifyReleaseWithR8` passed, building `app-debug.apk` and `app-release-unsigned.apk`).

### Untested on Device / Live Router
- Visual inspection on physical dark/light themes, 200% system font scale, tablet split/rail widths, and Layout Inspector recomposition profiling (headless CLI sandbox has no Android emulator/device or Android Studio).
- Device screenshots were not recaptured in this CLI environment (`README.md` retains existing screenshots).
- `Phase 8` iOS SwiftUI parity (`iosApp/`) intentionally not started yet; waits on Android review.

---

## Phase 7.1 — Deferred Second-Level Web UI Pages

**Status:** Complete for every achievable item previously under `Blocked / Deferred` across Phases 2–5 (`cmd 11`, `112`, `115`, `132`, `172`, `2/211 subcmd 1`, `213`, `220`, `226`, `230/231`, `243`, `244/325`, `248`, `256`, `275`, `321`, `333`, `336`, `337`, `350`, `382/383/385/391`, `400`, `411`, `502`, `503`, `21`, `23`, `24`, `26`, `27`, `28`, `29`, `30`, `32`, `20`; only `LIMITED_ACCESS` / `§6 Out of scope` endpoints excluded)

### Implemented
- **Web UI Schemas & Feature Gating (`docs/ROUTER_API.md`, `RouterFeatureFlags.kt`, `FeatureFlagRepository.kt`, `Phase71Models.kt`, `Phase71Validators.kt`, `SettingsWriter.kt`):**
  - Documented all verified second-level `~/airtelOdu-webui/` `GET`/`POST` payloads and constraints under `## Write schemas` in `docs/ROUTER_API.md`, plus explicit exclusions (`cmd 278`, `cmd 130`, `cmd 405–408`).
  - Implemented `RouterFeatureFlags` (`cmd 80` `parseHexPageHide`, `pageHideCheck`, `pageHideCheck2`, `pageHideCheck3`, `aeraId`, `networkMode`, `ipPassthrough`, `lockPlmnFlag`, `wifiSsidForbid`, `wifiPwdForbid`, `fwSwitch`, `parentControlSwitch`) and `FeatureFlagRepository` (`cmd 80` GET, integrated into `AppContainer` and login/refresh flow).
  - Implemented `Phase71Validators` (unicast MAC normalization & broadcast/zero/multicast rejection, IPv4/IPv6 & `start-end` range checks, `validatePortOrRange` & `cmd 400` conflict lookup, `validateFilterUrl`, APN regex & MTU, WPS 8-digit checksum, `cmd 11` datetime/POSIX timezone formatting, parental time-window & speed validation, UTF-16BE USSD decoder).
  - Extended `SettingsWriter` (`skipPreRead`, `skipVerifyRead` for `cmd 112` / `cmd 350`, and `applyFilter()` for `cmd 20`).
- **Batch A — USSD, Mobile Network/CA/Roaming/APN & Traffic Limit (`UssdRepository.kt`, `MobileRepository.kt`, `MessagesScreen.kt`, `MessagesSheets.kt`, `MessagesViewModel.kt`, `MobileAdvancedSheets.kt`, `SettingsHubScreen.kt`, `SettingsHubViewModel.kt`, `DataUsageScreen.kt`, `DataUsageViewModel.kt`, `StatusRepository.kt`, `Phase1Parsers.kt`):**
  - Added interactive `USSD` dialer (`cmd 350` `subcmd 0/1/2/3/4`, ~3 s polling, UTF-16BE decoding, `506`/`507` status handling, gated by `ussdAvailable`) reachable from Messages.
  - Added `Network Mode` (`cmd 256` with `NetworkModeCatalog` area-specific/bitmask mapping and `lockPlmnFlag` lock notice), `Carrier Aggregation` (`cmd 226` `lteCA` / `nrCA`), `Data Roaming` (`cmd 220` with Tier-2 roaming-charge confirm), and `APN & PDP Config` (`cmd 213 subcmd 3` MTU/NAT + `cmd 248` editable `apn_list` max 4 editable profiles + reset).
  - Added `Traffic Limit & Cycle Settings` (`cmd 337` `limitSwitch`, `limitSize`, `flow_limit_unit` preserving GET convention, `startDate` `1..28`, and counter reset/calibration) in `DataUsageScreen`, plus router monthly usage sync.
- **Batch B — Static DHCP Reservations, Firewall MAC Filtering & Parental Control (`LanRepository.kt`, `SecurityRepository.kt`, `ParentalControlRepository.kt`, `DevicePolicySheets.kt`, `DevicesScreen.kt`, `DevicesViewModel.kt`, `SecurityAdvancedSheets.kt`, `SecurityScreen.kt`, `SecurityViewModel.kt`):**
  - Added Static DHCP reservations (`cmd 115` up to 20 entries, optional `cmd 24` `IP_MAC_BINDING` + `cmd 20` sync gated by `dhcpIpMacBindingSync`).
  - Added MAC filtering (`cmd 23` up to 32 unicast rules) and default policy (`cmd 28` + `cmd 30`) keeping `enableLink = !acceptAll` aligned across `cmd 23` / `cmd 32` / `cmd 21` followed by `cmd 20`.
  - Added Parental Control (`cmd 391` toggle, `cmd 382` speed limit 1..10000 KB/s, `cmd 383` blocked URLs <= 10 per MAC, `cmd 385` time schedules + `cmd 20`), strictly gated by `cmd 80` `parentalControlAvailable` (`pageHideCheck(153)`) and `parentSpeedVisible`/`parentUrlVisible`/`parentTimeVisible`.
- **Batch C — Wi-Fi TX Power, Guest Wi-Fi, Advanced Channel/Bandwidth, WPS & Schedule (`WifiRepository.kt`, `WifiAdvancedSheets.kt`, `SettingsHubScreen.kt`, `SettingsHubViewModel.kt`):**
  - Enforced `cmd 417` (`wifiStatus == "1"`) preflight before Wi-Fi writes and `wifiMode == "1"` STA read-only guard on advanced settings.
  - Added model-conditional Wi-Fi TX Power (`cmd 411` when `wifiPowerCmd411Available`, else per-band `txPower` on `cmd 230`/`231`), Guest Wi-Fi (`cmd 2` / `cmd 211` `subcmd: 1`, Base64 UTF-8 SSID, primary-radio prerequisite), Advanced Radio (`cmd 230` / `cmd 231` including 5 GHz `dfsSwitch` clamping 160 MHz `bandWidth = "4"` to 80 MHz `"3"`), WPS (`cmd 132` toggle, `cmd 243` PBC & checksummed Enrollee PIN, `cmd 244`/`cmd 325` 120 s Random PIN polling, WPA3/`WPA2+WPA3` & hidden-SSID guards), and Wi-Fi Schedule (`cmd 503` `wifiSwitch` & on/off hours/minutes).
- **Batch D — Security Firewall/Routing, Router Time, WAN Link Detect & Factory Reset (`SecurityRepository.kt`, `SecurityAdvancedSheets.kt`, `SecurityScreen.kt`, `SecurityViewModel.kt`, `SystemRepository.kt`, `SystemToolsSheets.kt`, `SystemToolsScreen.kt`, `SystemToolsViewModel.kt`):**
  - Added `Firewall & Routing` controls gated by `RouterFeatureFlags` (`cmd 80` bits + `!ipPassthrough && networkMode != "7"` where applicable): DMZ (`cmd 172 subcmd 6`), DDoS (`cmd 275`), UPnP (`cmd 333` + optional read-only `cmd 202`), Port Forward (`cmd 27` -> `cmd 20` with `cmd 400` system-port conflict warning & Tier-2 confirm), IP/Port Filter (`cmd 32` -> `cmd 20`, clearing ports for `ICMP`/`All`), Port Filter (`cmd 21` -> `cmd 20`), URL Filter (`cmd 26` + `cmd 29` `enableLink` sync -> `cmd 20`), and Trusted IP (`cmd 502` `trusted_ip_0..14` -> `cmd 20`).
  - Added Router Time / NTP & `Sync Phone Time` (`cmd 11`, `cmd 321`), WAN Link Detect (`cmd 336`, gating `LinkDetectAction == "3"` by `wanDetectRescanAvailable` and `reboot_wait_time * 60 >= checkTime`), and Factory Reset (`cmd 112` behind Tier-3 `"RESET"` `ConfirmSheet`).
- **Tests:**
  - Added `Phase71ValidatorsAndFlagsTest.kt` (`8` tests covering `cmd 80` hex flag decoding, `pageHideCheck`/`pageHideCheck2`, `NetworkModeCatalog`, MAC/IP/URL/port/`cmd 400`/WPS PIN/APN/`cmd 11` validators, and UTF-16BE USSD decoding).
  - Added `Phase71SchemasAndReposTest.kt` (`6` tests covering Batch A–D write schemas, `cmd 417` preflight, Guest Wi-Fi Base64, DFS 160 MHz clamp, STA read-only guard, `cmd 23`/`28`/`30`/`32`/`21`/`26`/`29`/`20` sync, Parental Control, DMZ/DDoS/UPnP/PortForward/TrustedIP/`cmd 11`/`321`/`336`/`112` payloads via `FakeTransport`).

### Explicitly Excluded (`LIMITED_ACCESS` or `§6 Out of Scope`)
- `cmd 278` (`WIRELESS_MACFILTER`: `LIMITED_ACCESS` on user account; firewall `cmd 23`/`30` MAC filtering used instead)
- `cmd 130` (`MULTI_APN_INFO`: `LIMITED_ACCESS`; primary APN uses `cmd 213` + `cmd 248`)
- `cmd 405–408` (`TRAFFIC_SETTING` / `TRAFFIC_INTERFACE_LIST` / `TRAFFIC_DAY_STAT` / `TRAFFIC_HOUR_STAT`: `cmd 406` is `LIMITED_ACCESS`, `industryMenu` is disabled in `js/home.js:328-348`, `cmd 405` returns CAN-bus stub fields on Airtel NG, and `html/industry/` is `§6 Out of scope`; local `DailyUsageStore` remains authoritative)

### Checks Run
- `sh ./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease --console=plain` — **PASSED** (`102` unit tests across `18` test suites: `0` skipped, `0` failures, `0` errors; `compileDebugUnitTestKotlin` clean; `lintDebug` passed with `0` errors and `25` SARIF warnings; `lintVitalRelease`, R8 `minifyReleaseWithR8`, `assembleDebug`, and `assembleRelease` succeeded, producing `app-debug.apk` and `app-release-unsigned.apk`).

### Untested on Device / Live Router
- End-to-end live ZLT X17M validation of all newly enabled Batch A–D write commands, `cmd 80` flag gating on physical hardware, USSD carrier session timing (`cmd 350`), WPS PIN/PBC pairing, and Tier-3 Factory Reset (`cmd 112`).
