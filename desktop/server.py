import http.server
import json
import os
import sys
import threading
import time
import urllib.parse
import webbrowser
from router_client import ZLTRouterClient

PORT = 8080
STATIC_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "static")

# Instantiate router client
client = ZLTRouterClient(host="192.168.1.1", username="root", password="admin")
is_running = True
poll_interval = 2.0

def background_poller():
    global is_running, poll_interval
    print("[Poller] Starting background metric poller...")
    # Perform initial login
    if not client.login():
        print("[Poller] Initial router login failed, will retry in background loop...")
    else:
        print("[Poller] Successfully authenticated with Airtel ZLT Router.")
        
    while is_running:
        try:
            client.poll_metrics()
        except Exception as e:
            print(f"[Poller Error] {e}")
        time.sleep(poll_interval)

class MonitorHTTPHandler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=STATIC_DIR, **kwargs)

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        
        if parsed.path == "/api/status":
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Cache-Control", "no-cache, no-store")
            self.end_headers()
            
            data = client.cached_state or client.poll_metrics()
            self.wfile.write(json.dumps(data).encode("utf-8"))
            return
            
        elif parsed.path == "/api/config":
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            config = {
                "router_host": client.host,
                "poll_interval": poll_interval,
                "custom_aliases": client.custom_aliases
            }
            self.wfile.write(json.dumps(config).encode("utf-8"))
            return
            
        # Default static file handling
        return super().do_GET()

    def do_POST(self):
        parsed = urllib.parse.urlparse(self.path)
        content_length = int(self.headers.get("Content-Length", 0))
        post_data = self.rfile.read(content_length).decode("utf-8")
        
        try:
            req_json = json.loads(post_data) if post_data else {}
        except Exception:
            req_json = {}

        if parsed.path == "/api/alias":
            identifier = req_json.get("identifier")
            alias = req_json.get("alias", "").strip()
            if not identifier:
                self._send_json({"success": False, "error": "Missing identifier"}, 400)
                return
                
            client.save_alias(identifier, alias)
            client.poll_metrics()
            self._send_json({"success": True, "identifier": identifier, "alias": alias})
            return

        elif parsed.path == "/api/reboot":
            # Guard: require explicit confirmation flag so a stray request
            # can't reboot the router by accident.
            if req_json.get("confirm") is not True:
                self._send_json({"success": False, "error": "Missing confirm:true"}, 400)
                return
            success = client.reboot()
            self._send_json({"success": success})
            return

        elif parsed.path == "/api/set-interval":
            global poll_interval
            interval = float(req_json.get("interval", 1.5))
            poll_interval = max(0.5, min(10.0, interval))
            self._send_json({"success": True, "interval": poll_interval})
            return

        self._send_json({"error": "Endpoint not found"}, 404)

    def _send_json(self, data: dict, status: int = 200):
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(json.dumps(data).encode("utf-8"))

    def log_message(self, format, *args):
        # Suppress noisy GET log spam for /api/status polling
        msg = format % args if args else format
        if "/api/status" not in str(msg):
            sys.stderr.write(f"{self.address_string()} - - [{self.log_date_time_string()}] {msg}\n")

def main():
    # Start poller thread
    t = threading.Thread(target=background_poller, daemon=True)
    t.start()
    
    server_address = ("", PORT)
    httpd = http.server.ThreadingHTTPServer(server_address, MonitorHTTPHandler)
    url = f"http://127.0.0.1:{PORT}"
    print(f"\n=======================================================")
    print(f"  Airtel ZLT Router Real-Time Bandwidth Monitor")
    print(f"  ODU: ZLT X17M  |  IDU: ZLT W304VA PRO")
    print(f"  Live Dashboard: {url}")
    print(f"=======================================================\n")
    
    # Auto-launch default web browser after short delay
    def open_browser():
        time.sleep(1.0)
        webbrowser.open(url)
    threading.Thread(target=open_browser, daemon=True).start()

    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nShutting down server...")
        global is_running
        is_running = False
        httpd.server_close()

if __name__ == "__main__":
    main()
