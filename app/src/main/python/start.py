"""
Chaquopy entry point. MainActivity calls start() once; it launches the
embedded HTTP server (from airtel_monitor.py) on a background daemon thread
and returns immediately so the UI thread is never blocked.
"""
import threading

import airtel_monitor

_lock = threading.Lock()
_started = False


def start():
    global _started
    with _lock:
        if _started:
            return "already-running"
        _started = True
    t = threading.Thread(target=airtel_monitor.main, daemon=True)
    t.start()
    return "started"
