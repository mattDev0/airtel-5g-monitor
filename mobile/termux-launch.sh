#!/data/data/com.termux/files/usr/bin/bash
# ==================================================================
#  Airtel 5G Monitor - Termux:Widget launcher
#  Tap the widget/icon -> starts the server (if not already running)
#  -> opens the live dashboard in your browser.
#
#  Install location on the phone:  ~/.shortcuts/Airtel-Monitor
#  (see PHONE_SETUP.md for the full one-time setup)
# ==================================================================

URL="http://localhost:8080"
APP="$HOME/airtel_monitor.py"
PIDFILE="$HOME/.airtel_monitor.pid"
LOG="$HOME/airtel_monitor.log"

# Keep the CPU awake so the poller keeps running with the screen off.
termux-wake-lock 2>/dev/null

# Start the server only if it isn't already running.
if [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE" 2>/dev/null)" 2>/dev/null; then
    echo "Airtel monitor already running."
else
    echo "Starting Airtel monitor..."
    nohup python "$APP" > "$LOG" 2>&1 &
    echo $! > "$PIDFILE"
    # Give it a few seconds to bind port 8080.
    sleep 4
fi

# Open the dashboard in the default browser.
am start -a android.intent.action.VIEW -d "$URL" >/dev/null 2>&1

echo "Opened $URL"
