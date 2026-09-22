# Airtel 5G Monitor for iOS (iPhone & iPad)

Native Swift & SwiftUI companion application for **Airtel 5G (ZLT X17M / W304VA PRO)** routers.

---

## Highlights

- **Pure Native Swift 5.9+ / SwiftUI**: No heavy runtimes or bridges. Fast startup, minimal battery usage (<5 MB app size).
- **Parity with Android**:
  - Live WAN download/upload throughput and peak speeds.
  - Cellular & RF diagnostics (5G/4G RSRP, SINR, CQI, Carrier, PCI, Band Lock info).
  - Wi-Fi Radios management (toggle 2.4 GHz and 5 GHz independently with safety locks).
  - LAN DNS configuration (view & update Primary/Secondary DNS with custom presets).
  - Diagnostics (in-app Ping and Traceroute with sticky auto-scroll terminal).
  - Connected device fleet with custom naming/aliases and link rates.
- **Battery & Privacy Conscious**:
  - Automatically pauses polling when the screen locks or the app is sent to the background via SwiftUI `scenePhase`.
  - Communicates directly with the router on your local Wi-Fi (`http://192.168.1.1/cgi-bin/http.cgi`). Zero external telemetry or cloud tracking.

---

## Automated GitHub Actions CI/CD

Because building iOS applications requires macOS and Xcode, this repository includes an automated GitHub Actions workflow (`.github/workflows/ios-build.yml`) that runs on Apple Silicon macOS runners (`macos-14`).

### How It Works
1. When changes are pushed to `iosApp/**` or triggered manually via **Actions > Build iOS IPA > Run workflow**:
2. The GitHub runner sets up Xcode and XcodeGen.
3. It compiles the app for `iphoneos` in Release mode.
4. It packages the executable into `AirtelMonitor.ipa`.
5. It uploads the `.ipa` as a build artifact (and optionally creates a GitHub Release).

---

## Installing on your iPhone

Because the `.ipa` is built without paid Apple developer signing, you can install it using any of the following free sideloading tools:

### Option 1: SideStore (Recommended - On-Device, No PC needed after setup)
1. Install [SideStore](https://sidestore.io/) on your iPhone.
2. Open Safari on your iPhone, go to your GitHub repository's **Releases** or **Actions** tab, and download `AirtelMonitor.ipa`.
3. Open SideStore, tap the `+` button in the top left, and select the downloaded `AirtelMonitor.ipa`.
4. SideStore will sign the app with your free Apple ID and install it.
5. On your iPhone, go to **Settings > General > VPN & Device Management**, tap your Apple ID under Developer App, and tap **Trust**.
6. Open **Airtel 5G** and allow the **Local Network** permission prompt so the app can communicate with `192.168.1.1`.

### Option 2: AltStore (PC or Mac)
1. Install [AltStore](https://altstore.io/) on your computer and your iPhone.
2. Download `AirtelMonitor.ipa` onto your iPhone.
3. Open AltStore, go to **My Apps**, tap `+`, and select `AirtelMonitor.ipa`.
4. AltStore will sign and install it onto your device.

### Option 3: Sideloadly (Windows PC via USB)
1. Download and open [Sideloadly](https://sideloadly.io/) on your Windows PC.
2. Plug your iPhone into the PC.
3. Drag and drop `AirtelMonitor.ipa` into Sideloadly.
4. Enter your Apple ID and click **Start**.
5. Trust the developer certificate in **Settings > General > VPN & Device Management** on your iPhone.

### Option 4: TrollStore (iOS 14.0–16.6.1 / 17.0)
- If your device is running TrollStore, simply share `AirtelMonitor.ipa` to TrollStore for permanent installation without 7-day re-signing limits.
