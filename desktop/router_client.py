import urllib.request
import json
import hashlib
import uuid
import time
import os
import threading
from typing import Dict, List, Any, Optional

class ZLTRouterClient:
    """
    Client for Airtel / Tozed ZLT X17M (ODU) + ZLT W304VA PRO (IDU) 5G/4G Routers.
    Interfaces directly with the router's JSON-RPC HTTP CGI engine at http://192.168.1.1/cgi-bin/http.cgi.
    """
    def __init__(self, host: str = "192.168.1.1", username: str = "root", password: str = "admin"):
        self.host = host
        self.username = username
        self.password = password
        self.url = f"http://{host}/cgi-bin/http.cgi"
        self.session_id: Optional[str] = None
        self.lock = threading.Lock()
        
        # State tracking for delta throughput computation
        self.last_poll_time = 0
        # WAN cumulative byte counters from cmd 18 (rxBytes/txBytes). These are the
        # only fields on this firmware that actually move; receiveSpeed/sentSpeed
        # in cmd 401 are always empty strings. Speed = delta(bytes) / delta(time).
        self.prev_wan = None  # {"rx": int, "tx": int, "time": float}
        self.prev_device_flow = {}  # mac -> {rx, tx, time}
        self.history = []  # rolling 60s bandwidth history
        self.max_history = 60
        self.cached_state = {}

        # Slow-polled data (heavy/rarely-changing). cmd 1018 returns a ~210KB
        # history blob and cmd 25 (speed limits) is access-blocked on this
        # firmware; hitting them every fast poll needlessly loads the router CPU.
        # Fetch them on their own slow cadence and cache the result.
        self.slow_interval = 20.0  # seconds between heavy queries
        self._last_slow_poll = 0.0
        self._slow_cache = {"latest_chart": {}, "cell160": {}, "band161": {}}
        
        # Persistent device aliases & custom settings
        self.data_dir = os.path.dirname(os.path.abspath(__file__))
        self.aliases_file = os.path.join(self.data_dir, "device_aliases.json")
        self.custom_aliases = self._load_aliases()

    def _load_aliases(self) -> Dict[str, str]:
        if os.path.exists(self.aliases_file):
            try:
                with open(self.aliases_file, "r", encoding="utf-8") as f:
                    return json.load(f)
            except Exception:
                return {}
        return {}

    def save_alias(self, identifier: str, alias: str):
        with self.lock:
            self.custom_aliases[identifier.lower()] = alias
            try:
                with open(self.aliases_file, "w", encoding="utf-8") as f:
                    json.dump(self.custom_aliases, f, indent=2)
            except Exception as e:
                print(f"Error saving alias: {e}")

    def _send_cmd(self, payload: dict, timeout: float = 4.0) -> dict:
        data = json.dumps(payload).encode('utf-8')
        req = urllib.request.Request(
            self.url,
            data=data,
            headers={
                'Content-Type': 'application/json; charset=UTF-8',
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) ZLT-Monitor/2.0'
            }
        )
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.loads(resp.read().decode('utf-8', errors='replace'))

    def login(self) -> bool:
        with self.lock:
            try:
                # 1. Fetch one-time challenge token via GET_NEXT_LOGIN_TIME (cmd 232)
                t_resp = self._send_cmd({"cmd": 232, "method": "GET", "sessionId": ""})
                token = t_resp.get("token", "")
                if not token:
                    return False
                
                # 2. Compute SHA256(token + password)
                pwd_hash = hashlib.sha256((token + self.password).encode('utf-8')).hexdigest()
                new_session_id = uuid.uuid4().hex + uuid.uuid4().hex
                
                # 3. Post authentication (cmd 100)
                l_resp = self._send_cmd({
                    "cmd": 100,
                    "method": "POST",
                    "sessionId": new_session_id,
                    "username": self.username,
                    "passwd": pwd_hash,
                    "isAutoUpgrade": "0",
                    "subcmd": 0
                })
                
                if l_resp.get("success") or l_resp.get("message") == "success" or l_resp.get("sessionId"):
                    self.session_id = l_resp.get("sessionId") or new_session_id
                    return True
                return False
            except Exception as e:
                print(f"Router login error: {e}")
                return False

    def _query(self, cmd_id: int, method: str = "GET", **extra) -> dict:
        if not self.session_id:
            if not self.login():
                return {}
        payload = {"cmd": cmd_id, "method": method, "sessionId": self.session_id}
        payload.update(extra)
        try:
            res = self._send_cmd(payload)
            # Re-authenticate if session timed out
            if res.get("message") in ["NO_AUTH", "LOGIN_TIMEOUT"] or not res.get("success", True):
                if self.login():
                    payload["sessionId"] = self.session_id
                    res = self._send_cmd(payload)
            return res
        except Exception:
            return {}

    def read_wan_bytes(self) -> Optional[tuple]:
        """Reads cumulative WAN (rx, tx) byte counters from cmd 18.
        Returns None if the counters are unavailable/unparseable."""
        res = self._query(18)
        try:
            rx = int(res.get("rxBytes") or 0)
            tx = int(res.get("txBytes") or 0)
        except (ValueError, TypeError):
            return None
        if rx == 0 and tx == 0:
            return None
        return (rx, tx)

    def _wan_speed_kBps(self, now: float) -> tuple:
        """Computes live WAN download/upload speed in kB/s from byte-counter deltas."""
        counters = self.read_wan_bytes()
        if counters is None:
            return (0.0, 0.0)
        rx, tx = counters
        prev = self.prev_wan
        self.prev_wan = {"rx": rx, "tx": tx, "time": now}
        if not prev:
            return (0.0, 0.0)  # first sample: no baseline yet
        dt = now - prev["time"]
        if dt <= 0:
            return (0.0, 0.0)
        d_rx = rx - prev["rx"]
        d_tx = tx - prev["tx"]
        # Counter reset (reboot / wrap) -> ignore this interval
        if d_rx < 0 or d_tx < 0:
            return (0.0, 0.0)
        rx_kBps = (d_rx / dt) / 1024.0
        tx_kBps = (d_tx / dt) / 1024.0
        return (round(rx_kBps, 2), round(tx_kBps, 2))

    def _infer_device_type(self, hostname: str, mac: str, band: str) -> str:
        h = (hostname or "").lower()
        if any(w in h for w in ["pixel", "iphone", "galaxy", "android", "redmi", "xiaomi", "oppo", "vivo", "realme", "oneplus", "huawei", "honor", "tecno", "infinix"]):
            return "phone"
        if any(w in h for w in ["ipad", "tab", "tablet"]):
            return "tablet"
        if any(w in h for w in ["pc", "laptop", "desktop", "macbook", "thinkpad", "dell", "hp", "lenovo", "asus", "acer", "msi", "surface"]):
            return "pc"
        if any(w in h for w in ["tv", "appletv", "roku", "firetv", "bravia", "chromecast", "webos", "tizen", "smarttv"]):
            return "tv"
        if any(w in h for w in ["playstation", "ps4", "ps5", "xbox", "nintendo", "switch"]):
            return "gaming"
        if any(w in h for w in ["zlt", "router", "ap", "repeater", "extender", "w304"]):
            return "router"
        return "device"

    def _refresh_slow_data(self, now: float, force: bool = False):
        """Fetches the heavy/rarely-changing queries (cmd 1018 history +
        cmd 25 speed limits) at most once per slow_interval, caching the result.
        Keeps the fast poll loop light on the router CPU."""
        if not force and (now - self._last_slow_poll) < self.slow_interval:
            return
        self._last_slow_poll = now
        status_chart = self._query(1018)
        if status_chart.get("device_info"):
            self._slow_cache["latest_chart"] = status_chart["device_info"][-1]
        # Cellular cell-lock (160) + band-lock (161): read-only status.
        # These are small responses but change rarely, so poll them slowly too.
        cell = self._query(160)
        if cell.get("success"):
            self._slow_cache["cell160"] = cell
        band = self._query(161)
        if band.get("success"):
            self._slow_cache["band161"] = band

    def poll_metrics(self) -> dict:
        now = time.time()
        dt = now - self.last_poll_time if self.last_poll_time > 0 else 1.0
        if dt <= 0:
            dt = 1.0

        # Fast, lightweight queries every poll
        dash = self._query(401)
        dhcp = self._query(223)
        wifi5g = self._query(225)
        wifi24g = self._query(224)
        sys_status = self._query(113)

        # Heavy queries (cmd 1018 ~210KB, cmd 25) only every slow_interval.
        # First poll forces one fetch so hardware stats populate immediately.
        self._refresh_slow_data(now, force=(self._last_slow_poll == 0.0))
        latest_chart = self._slow_cache["latest_chart"]

        # Extract DHCP devices
        dhcp_list = dhcp.get("dhcp_list_info") or dash.get("dhcp_list_info") or []
        
        # Build Wi-Fi lookup map
        wifi_map = {}
        for w in (wifi5g.get("wlan5g_wifi_info") or []):
            if isinstance(w, dict):
                mac = w.get("mac", "").lower()
                ip = w.get("ip", "")
                wifi_map[mac] = {"band": "5 GHz (Wi-Fi 6/ac)", **w}
                if ip:
                    wifi_map[ip] = {"band": "5 GHz (Wi-Fi 6/ac)", **w}
                    
        for w in (wifi24g.get("wlan24g_wifi_info") or []):
            if isinstance(w, dict):
                mac = w.get("mac", "").lower()
                ip = w.get("ip", "")
                wifi_map[mac] = {"band": "2.4 GHz (Wi-Fi)", **w}
                if ip:
                    wifi_map[ip] = {"band": "2.4 GHz (Wi-Fi)", **w}
                    
        # Parse WAN Speeds from live byte-counter deltas (cmd 18).
        # dash's receiveSpeed/sentSpeed are always empty on this firmware.
        rx_speed, tx_speed = self._wan_speed_kBps(now)  # kB/s

        devices = []
        for d in dhcp_list:
            if not isinstance(d, dict):
                continue
            mac = d.get("mac", "").lower()
            ip = d.get("ip", "")
            raw_hostname = d.get("hostname", "") or ""
            if raw_hostname == "NULL" or not raw_hostname.strip():
                hostname = "Unknown Device"
            else:
                hostname = raw_hostname.strip()
                
            w_info = wifi_map.get(mac) or wifi_map.get(ip) or {}
            band = w_info.get("band", "Ethernet (LAN)" if d.get("interface") != "wlan" else "Wi-Fi")
            rssi = w_info.get("rssi", "")
            tx_rate = w_info.get("txrate", "")
            rx_rate = w_info.get("rxrate", "")
            ssid = w_info.get("ssid", "")
            
            # Signal quality percentage
            signal_pct = 0
            if rssi:
                try:
                    r_val = float(rssi)
                    signal_pct = max(5, min(100, int((r_val + 100) * 1.42)))
                except ValueError:
                    pass
            elif band == "Ethernet (LAN)":
                signal_pct = 100
                
            # Custom alias
            alias = self.custom_aliases.get(mac) or self.custom_aliases.get(ip) or hostname
            dev_type = self._infer_device_type(hostname + " " + alias, mac, band)

            device_obj = {
                "mac": mac,
                "ip": ip,
                "hostname": hostname,
                "alias": alias,
                "type": dev_type,
                "band": band,
                "ssid": ssid,
                "rssi": rssi,
                "signal_percent": signal_pct,
                "tx_rate_mbps": tx_rate or "-",
                "rx_rate_mbps": rx_rate or "-",
                "interface": d.get("interface", "wlan"),
                "expires": d.get("expires", ""),
                "ipv6": d.get("ipv6", ""),
                "is_router": ip in ["192.168.1.1", "192.168.1.2"] or "zlt" in hostname.lower()
            }
            devices.append(device_obj)
            
        # Speed calculations
        dl_mbps = round((rx_speed * 8) / 1024, 2)
        ul_mbps = round((tx_speed * 8) / 1024, 2)
        
        # Cellular metrics
        cellular = {
            "network_type": sys_status.get("network_type_str") or dash.get("network_type_str", "5G(NSA)"),
            "signal_lvl": int(sys_status.get("signal_lvl") or dash.get("signal_lvl", 3)),
            "rsrp_5g": latest_chart.get("rsrp_5g") or dash.get("RSRP_5G", "-"),
            "sinr_5g": latest_chart.get("sinr_5g", "-"),
            "rsrp_4g": latest_chart.get("rsrp_4g") or dash.get("RSRP", "-"),
            "sinr_4g": latest_chart.get("sinr_4g", "-"),
            "operator": dash.get("network_operator") or "Airtel",
            "board_type": sys_status.get("board_type", "ZLT X17M"),
            "idu_type": sys_status.get("idu_dev_type", "ZLT W304VA PRO"),
            "sinr_5g_live": latest_chart.get("sinr_5g", "-"),
            "nr_cqi": latest_chart.get("nr_cqi", "-"),
            "lte_cqi": latest_chart.get("lte_cqi", "-"),
            "nr_qam_dl": latest_chart.get("nr_qam_dl", "-"),
        }

        # Read-only cell-lock (160) + band-lock (161) detail. Serving cell,
        # active bands, and lock status. Editing is NOT exposed: this firmware's
        # SET path for these commands is unreliable/access-blocked, so we only
        # display. See notes in project docs.
        cell160 = self._slow_cache.get("cell160", {})
        band161 = self._slow_cache.get("band161", {})

        def _tf(v):  # firmware uses "1"/"0" strings
            return str(v) in ("1", "True", "true")

        cell_lock = {
            "serving_pci_4g": cell160.get("PCI", "-"),
            "serving_freq_4g": cell160.get("FREQ", "-"),
            "serving_pci_5g": cell160.get("PCI_5G", "-"),
            "serving_freq_5g": cell160.get("FREQ_5G", "-"),
            "current_bands_4g": cell160.get("current_band", "-"),
            "current_band_5g": cell160.get("current_band_5g", "-"),
            "lte_lock_enabled": _tf(cell160.get("lte_lock_sw")),
            "lte_lock_pci": cell160.get("lte_lock_pci", "") or "-",
            "lte_lock_freq": cell160.get("lte_lock_freq", "") or "-",
            "nr_lock_enabled": _tf(cell160.get("nr_lock_sw")),
            "nr_lock_pci": cell160.get("nr_lock_pci", "") or "-",
            "nr_lock_freq": cell160.get("nr_lock_freq", "") or "-",
            "band_lock_4g_enabled": _tf(band161.get("band_4g_switch")),
            "band_lock_5g_enabled": _tf(band161.get("band_5g_switch")),
        }
        
        # Hardware metrics
        hardware = {
            "cpu_usage": float(latest_chart.get("cpu_usage", 0)),
            "temperature": latest_chart.get("temperature", "-"),
            "memory": latest_chart.get("memory", "-")
        }
        
        # History
        time_str = time.strftime("%H:%M:%S", time.localtime(now))
        self.history.append({
            "time": time_str,
            "download_mbps": dl_mbps,
            "upload_mbps": ul_mbps,
            "rx_kBps": rx_speed,
            "tx_kBps": tx_speed
        })
        if len(self.history) > self.max_history:
            self.history.pop(0)
            
        self.last_poll_time = now
        
        state = {
            "timestamp": now,
            "time_formatted": time_str,
            "wan": {
                "download_kBps": rx_speed,
                "upload_kBps": tx_speed,
                "download_mbps": dl_mbps,
                "upload_mbps": ul_mbps,
                "dl_flow_mb": dash.get("flow_dl", "-"),
                "ul_flow_mb": dash.get("flow_ul", "-"),
                "total_flow_mb": dash.get("mon_total_flow", "-")
            },
            "cellular": cellular,
            "cell_lock": cell_lock,
            "hardware": hardware,
            "devices": devices,
            "device_count": len(devices),
            "history": self.history
        }
        self.cached_state = state
        return state

    def reboot(self) -> bool:
        """Reboots the router. Maps to the default UI's SYS_REBOOT (cmd 6)
        with rebootType=1 (NORMAL_REBOOT). The router drops off the network for
        ~1-2 minutes and comes back automatically."""
        res = self._query(6, method="POST", rebootType=1)
        return bool(res.get("success"))
