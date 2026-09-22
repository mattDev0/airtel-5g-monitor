# Airtel 5G Monitor — Phone App (tap-to-launch)

Turns the monitor into a home-screen icon on Android. Tap it → the server
starts on the phone and the dashboard opens in your browser. No laptop needed.

**Requirement:** the phone must be on the router's Wi-Fi so it
can reach 192.168.1.1.

You copy two files to the phone:
- `airtel_monitor.py`  (the whole app, one file)
- `termux-launch.sh`   (the launcher script)

---

## One-time setup

### 1. Install the apps (use F-Droid for BOTH)
Install **F-Droid** (https://f-droid.org), then from F-Droid install:
- **Termux**
- **Termux:Widget**

> Important: install both from the **same source** (F-Droid). Mixing the
> Play Store Termux with an F-Droid add-on will not work (signature mismatch).

### 2. Set up Python + storage access
Open **Termux** and run:
```bash
pkg update -y && pkg install -y python
termux-setup-storage        # tap "Allow" when prompted
```

### 3. Get the two files onto the phone
Download `airtel_monitor.py` and `termux-launch.sh` to the phone (e.g. open the
**OneDrive** app → Desktop → agy → download both to your Downloads folder).

Then in Termux:
```bash
cp ~/storage/downloads/airtel_monitor.py ~/
mkdir -p ~/.shortcuts
cp ~/storage/downloads/termux-launch.sh ~/.shortcuts/Airtel-Monitor
chmod +x ~/.shortcuts/Airtel-Monitor
```

### 4. Put the icon on your home screen
- Long-press an empty spot on the home screen → **Widgets** →
  find **Termux:Widget** → drag it to the home screen. It lists your scripts;
  tap **Airtel-Monitor** to run.
- For a single app-like icon instead of a list: long-press home →
  **Shortcuts** (or "Widgets" → Termux:Widget "1x1") → pick **Airtel-Monitor**.
  You now have one icon that launches everything.

---

## Daily use
Just **tap the icon**. First tap starts the server (a few seconds) and opens the
dashboard. Tapping again while it's running just re-opens the dashboard.

## Notes
- Keep the phone on the router's Wi-Fi, or it can't reach the router.
- The server keeps running in the background (a wake-lock keeps polling alive).
  Android may still stop it eventually under heavy memory pressure — just tap
  the icon again to restart it.
- **To stop the server:** in Termux run `kill $(cat ~/.airtel_monitor.pid)`
  and `termux-wake-unlock`.
- **To update the app** after I change the dashboard: replace `airtel_monitor.py`
  in `~/` with the new copy (stop it first, then tap the icon again).
