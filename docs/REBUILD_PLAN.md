# Airtel 5G Monitor v3 — rebuild plan

Goal: turn the personal shortcut app into a complete, polished router companion app at the level of
an official vendor app (TP-Link Tether, Huawei AI Life, ZTE Link). It should cover everything a user
does in the router web UI (`http://192.168.1.1`), faster and with fewer taps, plus things the web UI
can't do (notifications, widget, history, antenna alignment).

Executor: Gemini. Reviewer: Claude, after each phase or at the end. **Android first.** iOS parity is
the last phase and starts only after the Android review passes.

Read before starting:
- `docs/ROUTER_API.md`: the command map from a read-only sweep of this exact router (2026-11-15).
- `docs/ANDROID_NOTES.md`, `docs/ANDROID_BUILD.md`
- `app/src/main/java/com/airtel/monitor/data/remote/ZltRouterClient.kt`: working login, session
  retry, token handling and the write calls that already work. Reuse its patterns; don't reinvent them.

---

## 0. Ground rules (non-negotiable)

### Git
- Work on branch `v3-rebuild` off `main`. One or more commits per phase, with clear messages.
- Author: `mattDev0 <amatthew642@gmail.com>` (already the repo's git config). **No `Co-Authored-By`
  lines and no mention of AI, Gemini or Claude** in commits, code comments or docs.
- Never commit `CLAUDE.md`, `GEMINI.md` or anything under `.gemini/` / `.claude/`. Add them to
  `.git/info/exclude` if they show up.
- You may push the `v3-rebuild` branch. **Do not** merge to `main`, tag, publish releases or bump
  release versions until the review is done. Set `versionName "3.0.0-dev"` / `versionCode 10` on the branch.
- Keep `applicationId "dev.mattdev0.airtelmonitor"` and don't touch signing config. Builds made here
  get re-signed with the owner's key before install.

### Router safety (this is a live home internet connection)
- Reads (`method: "GET"`) are always fine. Keep polling cheap: fast poll ≤ 2 s only for cmd 18/401/205
  while the app is in the foreground; cmd 1018 (210 KB) no more often than every 20 s; settings pages
  load on open, not on a timer.
- **Never call these speculatively, even to "see what they return":** 6/99/120 reboot, 112 factory
  reset, 116/228/288 network scans, 342 PCI scan, 48/49 connect/disconnect, 5/150/239 firmware,
  3 upload, 13/14 SMS send/delete, 45/270 AT commands, 237 ADB switch, 409 SSH.
- **Don't build UI for anything listed as LIMITED_ACCESS** in `ROUTER_API.md` (e.g. 25 speed limit,
  130 multi-APN, 219 PLMN lock, 334 remote web port, 371 sleep). Hide the feature; don't show a
  disabled control.
- Every write goes through one `SettingsWriter` helper (see §2) that does: read current object →
  merge only the changed fields → POST with a fresh token → re-read → verify the changed fields round-tripped
  → return a typed result. If the verify fails, surface the error and the raw response; don't retry blindly.
  (History: a naive cmd 161 write once flipped `band_4g_switch` 1→0 because the GET and SET schemas
  differ. That's why every write must use the web UI's own SET payload, never an echoed GET.)
- **Find each SET payload in the web UI, never guess it.** Download the page and its script from the
  router (`curl http://192.168.1.1/html/<area>/<page>.html`, plus any `js/*.js` it loads), find the
  `cmd: <n>, method: "POST"` object it sends, and record the exact field list under a new
  "Write schemas" section in `docs/ROUTER_API.md` **before** writing Kotlin for it.
- Live-test writes only for risk tier 1 (below), and always restore the original value straight
  after. Tier 2 and 3 writes are implemented and unit-tested against fixtures but **not executed on the
  real router**. List them in the phase notes so the reviewer can test them with the owner present.

| Tier | Examples | UI requirement |
|---|---|---|
| 0 read | everything on GET | none |
| 1 harmless toggle | UPnP, DDoS, Wi-Fi schedule, data-usage limit, NTP, SMS settings, LED-type flags | instant toggle + snackbar with Undo |
| 2 connectivity | SSID/password/security/channel, Wi-Fi radio off, LAN IP/DHCP range, network mode, roaming, data switch, band/cell lock, firewall level, port forward, MAC filter, admin password | confirm sheet saying what will drop ("Your phone will disconnect from Wi-Fi; reconnect with the new password") |
| 3 destructive | reboot, factory reset | confirm sheet + type the router name / hold-to-confirm |

### Privacy
- No analytics, crash SDKs or network calls other than the router (exception: optional speed test, §3.7).
- Store the router password encrypted (Android Keystore AES-GCM key + DataStore). Migrate from the
  current SharedPreferences on first launch, then delete the old value.
- Redact IMSI, ICCID, IMEI, serial numbers, Wi-Fi keys and MACs in logs, fixtures and screenshots.

---

## 1. Information architecture

Navigation bar with 4 destinations, keeping the current floating glass nav bar (`GlassNavBar.kt`) and
the slim top bar (`HeaderBar.kt`) that were just shipped in v2.6.0. Use `NavigationSuiteScaffold` so
tablets/foldables get a rail.

1. **Home**: at-a-glance status and the most-used actions
2. **Signal**: everything about the cellular link (power-user tab)
3. **Devices**: who is connected, and control over them
4. **Settings**: grouped settings hub (replaces the old Network/Tools tabs)

The top bar keeps: status line, pause, ⋮ menu (Refresh, update interval, Reboot). Settings moves out
of the menu into the tab. An SMS icon with an unread badge goes in the top bar when `sms_unread > 0` (cmd 394).

---

## 2. Architecture

Single `app` module, with packages split by layer and feature (no Gradle multi-module; keep the build simple):

```
com.airtel.monitor
  core/      network (RouterTransport, RouterSession, SettingsWriter), datastore, crypto, util
  data/      dto parsers per cmd group, repositories (StatusRepo, SignalRepo, DeviceRepo,
             WifiRepo, MobileRepo, LanRepo, SecurityRepo, SystemRepo, SmsRepo, UsageRepo)
  domain/    UI-facing models (CarrierComponent, SignalQuality, ConnectedDevice, WifiNetwork…)
  ui/        theme, designsystem (components), navigation, feature/<home|signal|devices|settings/...>
  work/      WorkManager workers, notifications
  widget/    Glance widget
```

- **DI:** a hand-written `AppContainer` created in `Application` (no Hilt; avoids KSP/AGP 9 friction).
  ViewModels via `viewModelFactory { }`.
- **Navigation:** Navigation Compose with type-safe `@Serializable` routes (add the
  `kotlinx-serialization` plugin and `navigation-compose`).
- **State:** one ViewModel per screen, exposing `StateFlow<UiState>`. Repositories expose `Flow`s. A single
  `PollingCoordinator` owns the timers and pauses when the app goes to the background
  (`ProcessLifecycleOwner`) or when the user pauses. Split the 677-line `MonitorViewModel` up along these lines.
- **Parsing:** keep org.json (already in use and tested) but move all parsing into pure functions
  `fun parseX(json: JSONObject): X` in `data/dto`, each with unit tests against fixtures in
  `app/src/test/resources/fixtures/<cmd>.json` (redacted real responses).
- **Multi-value fields:** cmd 205/160/1018 return CA values joined with `+`, e.g.
  `PCI "393+393"`, `FREQ "3400+225"`, `currentband "7+1"`, `bandwidth "10+15"`,
  `network_type_str "4G+"`. Write one parser, `parseCarriers(json): List<CarrierComponent>`
  (band, earfcn, pci, bandwidthMHz, isPrimary). Use it everywhere; no more ad-hoc string handling in
  composables. Lock fields use `,` instead (`lte_lock_pci "393,393"`).
- **Errors:** a `RouterResult<T>` sealed type (Ok, NotLoggedIn, LimitedAccess, Unreachable, BadResponse).
  Every screen has loading (skeleton), empty, error-with-retry and offline states.

---

## 3. Features by screen

Data sources are cmd numbers from `ROUTER_API.md`. ★ = new compared with v2.6.

### 3.1 Home
- **Connection hero:** network type badge (4G / 4G+ / 5G NSA / 5G SA), operator, signal bars,
  online duration (205 `onlineDuration`), WAN IP (133), ★ CA summary "B7 + B1 · 25 MHz".
- **Live throughput** (cmd 18 deltas) with a ★ 60-second sparkline for download and upload.
- ★ **Data usage card:** this month's total (205/401 `mon_total_flow`), progress against the monthly
  limit (337 `limitSwitch/limitSize/startDate/warn_percentage`) and days left in the cycle. Tap → Usage screen.
- **Quick toggles** (tier 2 confirms where needed): Mobile data (222/227), Wi-Fi 2.4 GHz, Wi-Fi 5 GHz,
  Flight mode.
- **Quick tiles:** Devices online (count, tap → Devices), SMS (unread, tap → inbox), Router temperature
  and CPU (1018), Uptime (207).

### 3.2 Signal ★ (new tab)
- **Carrier table:** one row per component carrier (PCC first): band, EARFCN, PCI, bandwidth.
  This fixes the Cell pill under CA properly. The Home pill shows `PCI 393 · 2CC`.
- **Quality meters** for RSRP, RSRQ, SINR, RSSI, CQI, with colour bands (RSRP ≥ −80 excellent, −80…−90 good,
  −90…−100 fair, < −100 poor; SINR ≥ 20 / 13 / 0 / < 0). A 5G section is shown only when the `_5G` fields are non-empty.
- **Link details:** DL/UL MCS, QAM (`max_dl_qam`, `max_ul_qam`), rank/MIMO, BLER, from 205 and 1018.
- **Cell identity:** PLMN, eNB ID, Cell ID, TAC, ECGI (205). Copy-to-clipboard on long-press.
- **History chart:** RSRP and SINR over the last ~10 min from the cmd 1018 `device_info` history, plus live
  points. Draw with Compose Canvas (no chart library).
- **Neighbour cells** (282 `lte_info` / `nr_info`): parse them when non-empty and hide the section when empty.
- **Antenna alignment mode:** full-screen, huge RSRP/SINR numbers, 1 s polling of 205 only, a tone and
  haptic tick whose pitch rises with SINR, a "best so far" marker and a keep-screen-on flag. This is for aiming
  the outdoor unit; no official app does it, and it's the killer feature for this hardware.
- **Band & cell lock:** keep the existing `LockEditorSheet` and its writes. Restyle it, and show the current
  carriers next to the lock list.
- **Network mode** (218 `networkMode`, `mode5g`) and **CA switches** (`lteCA`, `nrCA`): tier 2.
- **Re-search network:** existing action, tier 2 confirm.

### 3.3 Devices
- List from 223 (DHCP) merged with 224/225 (Wi-Fi association: band, PHY rate). Online/offline, band
  chip, alias (existing `AliasRepository`), IP, MAC (masked by default), ★ vendor from a small bundled
  OUI table (top ~300 vendors, generated once into `assets/oui.csv`).
- ★ **Device detail sheet:** rename, connection band and rate, lease info, ★ "Reserve this IP" (static lease,
  cmd 115), ★ "Block" (MAC filter cmd 23 / wireless MAC filter 278 is LIMITED, so use 23; schema from
  `html/firewall/macIndex.html`). Tier 2.
- ★ **Blocked devices** list with unblock.
- ★ **Parental controls** (382/383/385: speed, URL, time schedules per device), only if the
  web UI schema is confirmed working. Otherwise leave it for a later phase.

### 3.4 Settings hub (grouped list, like an official app)
Each row shows the current value as a subtitle (e.g. "Wi-Fi · MyNetwork, 2 bands on").

**Wi-Fi** (2, 211, 209, 210, 230, 231, 411, 503, 132)
- Per band: on/off, SSID, password (show/hide), security mode, hidden SSID; ★ "same SSID for both bands"
  (`wifiSames`).
- ★ **Share Wi-Fi:** QR code (`WIFI:T:WPA;S:<ssid>;P:<key>;;`), generated in-app (use ZXing core, which is small).
- ★ Advanced per band: channel, bandwidth, TX power (411), WMM, PMF, client isolation, max clients.
- ★ **Wi-Fi schedule** (503): on/off times.
- ★ WPS push-button (132), tier 2.

**Mobile network** (218, 220, 222, 134, 213)
- Data switch, roaming, network mode (Auto / 4G only / 5G NSA / 5G SA, using the options the web UI exposes).
- ★ SIM card: status, PIN lock on/off, change PIN, attempts left (134).
- ★ MTU (213) under Advanced.

**Messages** ★ (12, 13, 14, 16, 394)
- Inbox and outbox threads, read/delete, compose and send. The list needs page params: copy them from
  `html/sms/smsInbox.html`. Airtel balance and data messages arrive here, so this is high value.
- SMS centre and settings (16).
- ★ **USSD** (cmd 350 `USSD_SEND`, page `html/manage/ussdIndex.html`) with shortcut chips for
  common Airtel NG codes (data balance etc.). Make the shortcuts user-editable; don't hard-code a promise
  that a code works.

**Data usage** ★ (337, 405–408)
- Monthly limit, cycle start day, warning %, auto-disconnect flag (if the schema has it).
- Daily/monthly history: 407/408 need params, so check `html/industry/trafficStatIndex.html`. If unavailable,
  record a daily snapshot of `mon_total_flow` locally in DataStore and chart that.

**LAN & DHCP** (208, 115, 175 DNS)
- LAN IP/netmask, DHCP on/off and range (tier 2: warn that changing the LAN IP disconnects and that the
  app's router host must be updated, and offer to update it automatically).
- Static leases list (115) with add/remove.
- DNS: keep the existing `LanDnsCard` logic and restyle it.

**Security** (367, 275, 33/202/333, 21/22/26, 502, 504, 214)
- Firewall on/off and level (367), DDoS protection (275), UPnP on/off plus a list of active mappings (202).
- ★ Port forwarding (`html/firewall/portForwardIndex.html`) with list/add/edit/delete.
- ★ DMZ (214 needs params, see `dmzIndex.html`).
- ★ URL filter (26), IP filter (22), ALG switches (504), trusted IPs (502). Lower priority; do them last in the phase.

**System** (207, 43, 133, 11, 263, 336, 322, 102, 6, 112)
- ★ **About router:** model, IDU/ODU firmware, hardware version, build date, IMEI/ICCID/serial
  (masked, tap to reveal).
- Time and NTP (11).
- ★ **Scheduled reboot** (263): daily/weekly time.
- ★ WAN link detection / auto-recover (336), shown read-only first; editing comes later if the schema is clear.
- System log viewer (322 settings plus 17 log, which timed out on a plain GET, so check `sysLog.html`).
- ★ Change router admin password (102). Tier 2; update the stored app password after success.
- Reboot (tier 3), factory reset (tier 3; also explain it wipes Wi-Fi/APN/locks).

**Tools**
- Ping / traceroute: keep the existing `NetworkDiagnosisCard` (cmd 168).
- ★ **Speed test** (optional, last): client-side test against Cloudflare (`speed.cloudflare.com/__down` /
  `__up`) with ping, jitter, download and upload. It's the only non-router network call allowed, and
  only when the user taps Start.

**App settings**
- Router address and credentials, polling interval, theme (system/light/dark), notification
  preferences, units (Mbps / MB/s), "mask sensitive identifiers" toggle, about and licences.

### 3.5 Onboarding / login ★
- First launch: welcome → router address (pre-filled with the Wi-Fi gateway from `ConnectivityManager`)
  → username/password → "Test connection" → done. Show clear errors for wrong password, router not reachable,
  and phone not on the router's Wi-Fi.
- If a session expires mid-use, re-login silently; show the login screen only when the password is wrong.

### 3.6 Background, notifications, widget ★
- WorkManager periodic job (15 min, network-connected constraint, only when on the router's Wi-Fi):
  - Data usage crossed the warning % (once per cycle).
  - New SMS (compare the unread count / latest id).
  - New device joined (MAC not seen before), opt-in.
  - Router became unreachable / came back (two consecutive failures before alerting).
- Notification channels for each type, plus the Android 13+ runtime permission prompt shown in context, not on launch.
- **Glance home-screen widget** (2×2 and 4×2): network type, signal bars, today's or this month's data,
  device count, refreshed by the same worker and tap-to-refresh.
- Optional: Quick Settings tile for mobile data on/off (tier 2 → opens the confirm activity).

### 3.7 Design system
- Keep the current palette (Airtel red/coral dark theme). Add a proper **light theme**, and bring all colours,
  type scale, spacing (4/8/12/16/24) and shapes into `ui/theme` tokens. No hard-coded colours in feature code.
- Shared components in `ui/designsystem`: `SectionCard`, `StatTile`, `SignalMeter`, `CarrierRow`,
  `SettingRow` (title/subtitle/chevron), `SettingSwitchRow`, `SettingValueRow`, `ConfirmSheet` (tiers 2/3),
  `SkeletonBox`, `EmptyState`, `ErrorState`, `Sparkline`, `LineChart`, `QrCode`.
- Motion: Material 3 Expressive defaults; shared-element transition from the Home carrier pill to the Signal tab.
- Accessibility: every icon button has a `contentDescription`, 48 dp touch targets, contrast AA in both themes,
  and it must work at 200 % font scale (pills wrap or shrink, never clip to nothing).
- Edge-to-edge, predictive back, and `strings.xml` for all user-facing text (no literals in composables).

---

## 4. Phases and acceptance criteria

Each phase ends with these all green, and a short note appended to `docs/V3_PROGRESS.md` (what was done,
what's untested on the real router, open questions):
`sh ./gradlew testDebugUnitTest lintDebug assembleDebug`

| # | Scope | Done when |
|---|---|---|
| 0 | Branch, `AppContainer`, Navigation, theme tokens and design-system components, `RouterTransport` / `SettingsWriter` / `RouterResult`, encrypted credentials + migration, `parseCarriers` + fixtures/tests, polling coordinator | App runs with the old screens moved onto the new nav and data layer, and nothing has regressed |
| 1 | Home + Signal tabs (incl. alignment mode, history chart, CA table) | CA shows correctly with the `393+393` fixture; alignment mode polls only 205 |
| 2 | Devices (list, detail, rename, vendor, static lease, block/unblock) | Block/unblock schema documented; writes unit-tested |
| 3 | Settings: Wi-Fi (incl. QR, schedule, advanced), Mobile network, SIM PIN, LAN/DHCP, DNS | Every write has a documented schema and a round-trip test |
| 4 | Messages + USSD + Data usage | Inbox paging works on the real router (read-only); send is tier 2, so it isn't live-tested |
| 5 | Security + System + Tools (incl. scheduled reboot, admin password, logs, optional speed test) | Same |
| 6 | Onboarding, background worker, notifications, widget, app settings | Worker only runs on the router's Wi-Fi; notifications are deduped |
| 7 | Polish: accessibility pass, light theme, 200 % font, tablet layout, empty/error states, performance (no recomposition storms from the 2 s poll; check with Layout Inspector), README + screenshots | Lint is clean, with no new warnings left in the files you touched |
| 8 | iOS parity (SwiftUI, `iosApp/`): same IA and features where iOS allows (no widget/worker parity required in the first pass; use WidgetKit later). The CI workflow `ios-build.yml` must pass | Starts only after the Android review |

---

## 5. Known facts and gotchas
- Live CA sample (2026-11-15, cmd 205): `network_type_str "4G+"`, `currentband "7+1"`, `FREQ "3400+225"`,
  `PCI "393+393"`, `bandwidth "10+15"`, `ca_state "1"`. The `ca_*` fields were empty, so don't rely on them.
- cmd 160 under CA: `PCI "393+393"`, `current_band "7+1"`, `lte_lock_pci "393,393"`.
- Real throughput only comes from cmd 18 byte-counter deltas; 401 `receiveSpeed`/`sentSpeed` are always empty.
- No per-device traffic exists anywhere in the API, so don't design UI that needs it.
- Some cmds answer "Parameters of the CMD can't identify" on a bare GET: they need extra params, which you
  get from the web UI page.
- Session expiry → the existing client re-logs in and replaces `token` in write payloads (keep that behaviour).
- `aeraId NG0002`, `smsSw 1`, `esim_show 1`, `multiApnNum 3` (cmd 80). Cmd 80 holds the web UI's feature
  flags, so use it to hide features this firmware doesn't support.
- The app on the owner's phone is signed with their Windows debug key. Builds from this server use a
  different key, and the reviewer re-signs them. Don't "fix" signing.

## 6. Out of scope
Firmware upload/flashing, AT command console, ADB/SSH/telnet switches, TR-069/TR-369, VPN servers
(PPTP/L2TP/GRE/IPsec), mesh, VoIP/VoLTE settings, industry/serial/Modbus/MQTT pages, multi-router
management, cloud accounts.
