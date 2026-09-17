/**
 * Airtel 5G Router Real-Time Dashboard Client
 * Interfaces with Python backend (/api/status, /api/alias, /api/reboot)
 */

(function () {
  'use strict';

  // State
  let isPaused = false;
  let pollInterval = 1500;
  let pollTimer = null;
  let currentFilter = 'all';
  let currentSort = 'speed';
  let searchQuery = '';
  let activeAliasDevice = null;
  let peakDl = 0;
  let peakUl = 0;
  let rawDevices = [];
  let bandwidthHistory = [];

  // DOM Elements
  const connectionBadge = document.getElementById('connectionBadge');
  const deviceModels = document.getElementById('deviceModels');
  const pollIntervalSelect = document.getElementById('pollIntervalSelect');
  const pauseResumeBtn = document.getElementById('pauseResumeBtn');
  const manualRefreshBtn = document.getElementById('manualRefreshBtn');

  // Hero Card Elements
  const wanDlSpeed = document.getElementById('wanDlSpeed');
  const wanDlBar = document.getElementById('wanDlBar');
  const dlKbsHint = document.getElementById('dlKbsHint');
  const totalDlFlow = document.getElementById('totalDlFlow');
  const peakDlSpeed = document.getElementById('peakDlSpeed');

  const wanUlSpeed = document.getElementById('wanUlSpeed');
  const wanUlBar = document.getElementById('wanUlBar');
  const ulKbsHint = document.getElementById('ulKbsHint');
  const totalUlFlow = document.getElementById('totalUlFlow');
  const peakUlSpeed = document.getElementById('peakUlSpeed');

  const netTypeBadge = document.getElementById('netTypeBadge');
  const carrierName = document.getElementById('carrierName');
  const rsrp5gVal = document.getElementById('rsrp5gVal');
  const sinr5gVal = document.getElementById('sinr5gVal');
  const rsrp4gVal = document.getElementById('rsrp4gVal');
  const sinr4gVal = document.getElementById('sinr4gVal');
  const radioStatusText = document.getElementById('radioStatusText');
  const totalMonthFlow = document.getElementById('totalMonthFlow');
  const signalBarsContainer = document.getElementById('signalBarsContainer');

  const cpuVal = document.getElementById('cpuVal');
  const cpuBar = document.getElementById('cpuBar');
  const tempVal = document.getElementById('tempVal');
  const tempBar = document.getElementById('tempBar');
  const activeDevicesCount = document.getElementById('activeDevicesCount');
  const systemStatusText = document.getElementById('systemStatusText');

  // Devices Elements
  const devicePillCount = document.getElementById('devicePillCount');
  const deviceSearchInput = document.getElementById('deviceSearchInput');
  const deviceSortSelect = document.getElementById('deviceSortSelect');
  const devicesContainer = document.getElementById('devicesContainer');
  const filterTabs = document.querySelectorAll('.tab-btn');

  // Modals
  const aliasModal = document.getElementById('aliasModal');
  const aliasModalIdentifier = document.getElementById('aliasModalIdentifier');
  const aliasInput = document.getElementById('aliasInput');
  const closeAliasModalBtn = document.getElementById('closeAliasModalBtn');
  const cancelAliasBtn = document.getElementById('cancelAliasBtn');
  const saveAliasBtn = document.getElementById('saveAliasBtn');

  const toastContainer = document.getElementById('toastContainer');
  const canvas = document.getElementById('bandwidthCanvas');
  const ctx = canvas.getContext('2d');

  // Canvas DPI Setup
  function resizeCanvas() {
    const rect = canvas.getBoundingClientRect();
    canvas.width = rect.width * window.devicePixelRatio;
    canvas.height = rect.height * window.devicePixelRatio;
    ctx.scale(window.devicePixelRatio, window.devicePixelRatio);
  }
  window.addEventListener('resize', resizeCanvas);
  resizeCanvas();

  // Toast Notification Utility
  function showToast(message, type = 'info') {
    const toast = document.createElement('div');
    toast.className = `toast ${type}`;
    let icon = 'ℹ️';
    if (type === 'success') icon = '✅';
    if (type === 'error') icon = '⚠️';
    toast.innerHTML = `<span>${icon}</span> <span>${message}</span>`;
    toastContainer.appendChild(toast);
    setTimeout(() => {
      if (toast.parentNode) toast.parentNode.removeChild(toast);
    }, 3000);
  }

  // Draw Real-Time Bandwidth Canvas Stream
  function drawBandwidthChart() {
    const width = canvas.getBoundingClientRect().width;
    const height = canvas.getBoundingClientRect().height;
    ctx.clearRect(0, 0, width, height);

    const padding = { top: 20, right: 20, bottom: 25, left: 45 };
    const chartWidth = width - padding.left - padding.right;
    const chartHeight = height - padding.top - padding.bottom;

    if (!bandwidthHistory || bandwidthHistory.length === 0) return;

    // Find max value in history (minimum 5 Mbps for readable scale)
    let maxVal = 5;
    bandwidthHistory.forEach(d => {
      if (d.download_mbps > maxVal) maxVal = d.download_mbps;
      if (d.upload_mbps > maxVal) maxVal = d.upload_mbps;
    });
    maxVal = Math.ceil(maxVal * 1.25);

    // Draw Grid Lines & Y-Axis Labels
    ctx.strokeStyle = 'rgba(255, 255, 255, 0.06)';
    ctx.lineWidth = 1;
    ctx.fillStyle = '#64748b';
    ctx.font = '10px "JetBrains Mono", monospace';
    ctx.textAlign = 'right';

    const ySteps = 4;
    for (let i = 0; i <= ySteps; i++) {
      const yVal = (maxVal / ySteps) * i;
      const yPos = padding.top + chartHeight - (i / ySteps) * chartHeight;
      
      ctx.beginPath();
      ctx.moveTo(padding.left, yPos);
      ctx.lineTo(width - padding.right, yPos);
      ctx.stroke();

      ctx.fillText(`${yVal.toFixed(1)}M`, padding.left - 8, yPos + 3);
    }

    const count = bandwidthHistory.length;
    const stepX = count > 1 ? chartWidth / (count - 1) : chartWidth;

    // Helper: Draw Smooth Bezier Path
    function drawLine(points, strokeColor, fillColor) {
      if (points.length === 0) return;

      ctx.beginPath();
      const coords = points.map((val, idx) => {
        const x = padding.left + idx * stepX;
        const y = padding.top + chartHeight - (val / maxVal) * chartHeight;
        return { x, y };
      });

      ctx.moveTo(coords[0].x, coords[0].y);
      for (let i = 1; i < coords.length; i++) {
        const prev = coords[i - 1];
        const curr = coords[i];
        const cpX = (prev.x + curr.x) / 2;
        ctx.bezierCurveTo(cpX, prev.y, cpX, curr.y, curr.x, curr.y);
      }

      ctx.strokeStyle = strokeColor;
      ctx.lineWidth = 2.5;
      ctx.stroke();

      // Area Fill
      if (fillColor) {
        ctx.lineTo(coords[coords.length - 1].x, padding.top + chartHeight);
        ctx.lineTo(coords[0].x, padding.top + chartHeight);
        ctx.closePath();
        ctx.fillStyle = fillColor;
        ctx.fill();
      }
    }

    // Gradient Fills
    const dlGrad = ctx.createLinearGradient(0, padding.top, 0, padding.top + chartHeight);
    dlGrad.addColorStop(0, 'rgba(6, 182, 212, 0.25)');
    dlGrad.addColorStop(1, 'rgba(6, 182, 212, 0.0)');

    const ulGrad = ctx.createLinearGradient(0, padding.top, 0, padding.top + chartHeight);
    ulGrad.addColorStop(0, 'rgba(16, 185, 129, 0.2)');
    ulGrad.addColorStop(1, 'rgba(16, 185, 129, 0.0)');

    const dlPoints = bandwidthHistory.map(d => d.download_mbps);
    const ulPoints = bandwidthHistory.map(d => d.upload_mbps);

    drawLine(dlPoints, '#06b6d4', dlGrad);
    drawLine(ulPoints, '#10b981', ulGrad);
  }

  // Fetch Latest Status from Server
  async function fetchStatus() {
    try {
      const res = await fetch('/api/status', { cache: 'no-store' });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const data = await res.json();
      updateDashboard(data);
      connectionBadge.className = 'status-badge online';
      connectionBadge.innerHTML = '<span class="status-dot"></span> LIVE';
    } catch (err) {
      console.warn('Polling error:', err);
      connectionBadge.className = 'status-badge offline';
      connectionBadge.innerHTML = '<span class="status-dot"></span> RECONNECTING';
    }
  }

  // Update UI with Latest Data
  function updateDashboard(data) {
    if (!data) return;

    const wan = data.wan || {};
    const cellular = data.cellular || {};
    const cellLock = data.cell_lock || {};
    const hardware = data.hardware || {};
    const devices = data.devices || [];
    bandwidthHistory = data.history || [];

    rawDevices = devices;

    // 1. Update WAN Speeds
    const dlMbps = wan.download_mbps || 0;
    const ulMbps = wan.upload_mbps || 0;
    wanDlSpeed.textContent = dlMbps.toFixed(2);
    wanUlSpeed.textContent = ulMbps.toFixed(2);

    dlKbsHint.textContent = `${(wan.download_kBps || 0).toFixed(1)} kB/s`;
    ulKbsHint.textContent = `${(wan.upload_kBps || 0).toFixed(1)} kB/s`;

    if (dlMbps > peakDl) peakDl = dlMbps;
    if (ulMbps > peakUl) peakUl = ulMbps;
    peakDlSpeed.textContent = `${peakDl.toFixed(1)} Mbps`;
    peakUlSpeed.textContent = `${peakUl.toFixed(1)} Mbps`;

    // Dynamic scale bar (percentage of 100 Mbps)
    const dlPct = Math.min(100, Math.max(2, (dlMbps / 100) * 100));
    const ulPct = Math.min(100, Math.max(2, (ulMbps / 50) * 100));
    wanDlBar.style.width = `${dlPct}%`;
    wanUlBar.style.width = `${ulPct}%`;

    totalDlFlow.textContent = `${wan.dl_flow_mb || '-'} MB`;
    totalUlFlow.textContent = `${wan.ul_flow_mb || '-'} MB`;
    totalMonthFlow.textContent = `${wan.total_flow_mb || '-'} MB`;

    // 2. Cellular Radio
    netTypeBadge.textContent = cellular.network_type || '5G(NSA)';
    carrierName.textContent = cellular.operator || 'Airtel';
    rsrp5gVal.textContent = cellular.rsrp_5g ? `${cellular.rsrp_5g} dBm` : '-';
    sinr5gVal.textContent = cellular.sinr_5g ? `${cellular.sinr_5g} dB` : '-';
    rsrp4gVal.textContent = cellular.rsrp_4g ? `${cellular.rsrp_4g} dBm` : '-';
    sinr4gVal.textContent = cellular.sinr_4g ? `${cellular.sinr_4g} dB` : '-';

    // Signal Bars
    const lvl = cellular.signal_lvl || 3;
    const bars = signalBarsContainer.querySelectorAll('.bar');
    bars.forEach((b, i) => {
      if (i < lvl) b.classList.add('active');
      else b.classList.remove('active');
    });

    const rsrpNum = parseFloat(cellular.rsrp_5g || -100);
    if (rsrpNum > -85) radioStatusText.textContent = 'Excellent (High Speed)';
    else if (rsrpNum > -100) radioStatusText.textContent = 'Good (Stable)';
    else radioStatusText.textContent = 'Moderate';

    // 2b. Cellular & Cell-Lock panel (read-only)
    updateCellLockPanel(cellLock, cellular);

    // 3. Hardware Health
    const cpu = hardware.cpu_usage || 0;
    cpuVal.textContent = `${cpu.toFixed(1)}%`;
    cpuBar.style.width = `${Math.min(100, cpu)}%`;

    const temp = hardware.temperature || '--';
    tempVal.innerHTML = `${temp} &deg;C`;
    const tempNum = parseFloat(temp || 45);
    tempBar.style.width = `${Math.min(100, (tempNum / 80) * 100)}%`;

    activeDevicesCount.textContent = devices.length;
    devicePillCount.textContent = devices.length;

    // 4. Update Device Fleet
    renderDevices();

    // 5. Redraw Bandwidth Canvas
    drawBandwidthChart();
  }

  // Device Avatar Helper
  function getDeviceIcon(type) {
    switch (type) {
      case 'phone': return '📱';
      case 'tablet': return '📟';
      case 'pc': return '💻';
      case 'tv': return '📺';
      case 'gaming': return '🎮';
      case 'router': return '📡';
      default: return '🔌';
    }
  }

  // Render Connected Devices
  function renderDevices() {
    if (!rawDevices || rawDevices.length === 0) {
      devicesContainer.innerHTML = `
        <div class="empty-state">
          <p>No active devices detected on the network.</p>
        </div>`;
      return;
    }

    // Apply Filter
    let filtered = rawDevices.filter(d => {
      if (currentFilter === '5g') return d.band && d.band.includes('5 GHz');
      if (currentFilter === '24g') return d.band && d.band.includes('2.4 GHz');
      if (currentFilter === 'lan') return d.band && (d.band.includes('LAN') || d.band.includes('Ethernet'));
      return true;
    });

    // Apply Search
    if (searchQuery.trim()) {
      const q = searchQuery.toLowerCase().trim();
      filtered = filtered.filter(d => 
        (d.alias && d.alias.toLowerCase().includes(q)) ||
        (d.hostname && d.hostname.toLowerCase().includes(q)) ||
        (d.ip && d.ip.includes(q)) ||
        (d.mac && d.mac.toLowerCase().includes(q))
      );
    }

    // Apply Sort
    filtered.sort((a, b) => {
      if (currentSort === 'speed') {
        const sA = parseFloat(a.tx_rate_mbps) || 0;
        const sB = parseFloat(b.tx_rate_mbps) || 0;
        return sB - sA;
      }
      if (currentSort === 'signal') {
        return (b.signal_percent || 0) - (a.signal_percent || 0);
      }
      if (currentSort === 'name') {
        return (a.alias || a.hostname).localeCompare(b.alias || b.hostname);
      }
      if (currentSort === 'ip') {
        return (a.ip || '').localeCompare(b.ip || '');
      }
      return 0;
    });

    if (filtered.length === 0) {
      devicesContainer.innerHTML = `
        <div class="empty-state">
          <p>No devices matching the current search / filter.</p>
        </div>`;
      return;
    }

    // Build Cards HTML
    devicesContainer.innerHTML = filtered.map(d => {
      const is5G = d.band && d.band.includes('5 GHz');
      const is24G = d.band && d.band.includes('2.4 GHz');
      const isLan = d.band && (d.band.includes('LAN') || d.band.includes('Ethernet'));

      let bandTag = `<span class="tag tag-lan">Ethernet</span>`;
      if (is5G) bandTag = `<span class="tag tag-5g">5 GHz Wi-Fi 6</span>`;
      else if (is24G) bandTag = `<span class="tag tag-24g">2.4 GHz Wi-Fi</span>`;

      const signalBarColor = d.signal_percent > 60 ? '#10b981' : d.signal_percent > 30 ? '#f59e0b' : '#f43f5e';

      return `
        <div class="device-card" data-mac="${d.mac}" data-ip="${d.ip}">
          <div class="device-card-header">
            <div class="device-avatar">${getDeviceIcon(d.type)}</div>
            <div class="device-main-info">
              <div class="device-name-row">
                <h4 title="${d.alias}">${d.alias}</h4>
                <button class="rename-icon-btn" title="Rename Device" onclick="window.openRenameModal('${d.mac}', '${d.alias.replace(/'/g, "\\'")}')">✏️</button>
              </div>
              <div class="device-hostname" title="Host: ${d.hostname}">Host: ${d.hostname}</div>
            </div>
          </div>

          <div class="device-tags">
            ${bandTag}
            ${d.ssid ? `<span class="tag">${d.ssid}</span>` : ''}
          </div>

          <div class="device-stats-grid">
            <div class="device-stat-box" title="Negotiated Wi-Fi radio link rate (capability), not live traffic. This router does not expose per-device throughput.">
              <span class="dev-stat-lbl">PHY Link Rate</span>
              <span class="dev-stat-val">Tx: ${d.tx_rate_mbps}M / Rx: ${d.rx_rate_mbps}M</span>
            </div>
            <div class="device-stat-box">
              <span class="dev-stat-lbl">Wi-Fi Signal</span>
              <div class="signal-meter" title="${d.rssi ? d.rssi + ' dBm' : 'LAN Connection'}">
                <div class="signal-bar-mini">
                  <div class="signal-bar-mini-fill" style="width: ${d.signal_percent}%; background-color: ${signalBarColor};"></div>
                </div>
                <span class="dev-stat-val">${d.signal_percent}%</span>
              </div>
            </div>
          </div>

          <div class="device-card-footer">
            <button class="ip-copy-btn" title="Click to copy IP" onclick="window.copyToClipboard('${d.ip}')">
              <span>${d.ip}</span>
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect>
                <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path>
              </svg>
            </button>
          </div>
        </div>
      `;
    }).join('');
  }

  // Copy IP to Clipboard
  window.copyToClipboard = function (text) {
    if (navigator.clipboard) {
      navigator.clipboard.writeText(text).then(() => {
        showToast(`Copied ${text} to clipboard`, 'success');
      });
    }
  };

  // Close every dialog so only one can ever be open at a time.
  function closeAllModals() {
    aliasModal.classList.remove('active');
  }

  // Open Rename Device Modal
  window.openRenameModal = function (identifier, currentName) {
    closeAllModals();
    activeAliasDevice = { identifier, currentName };
    aliasModalIdentifier.textContent = `Identifier: ${identifier}`;
    aliasInput.value = currentName || '';
    aliasModal.classList.add('active');
    aliasInput.focus();
  };

  saveAliasBtn.addEventListener('click', async () => {
    if (!activeAliasDevice) return;
    const newName = aliasInput.value.trim();
    if (!newName) return;

    try {
      const res = await fetch('/api/alias', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          identifier: activeAliasDevice.identifier,
          alias: newName
        })
      });
      const data = await res.json();
      if (data.success) {
        showToast(`Device renamed to "${newName}"`, 'success');
        aliasModal.classList.remove('active');
        fetchStatus();
      }
    } catch (err) {
      showToast(`Error: ${err.message}`, 'error');
    }
  });

  closeAliasModalBtn.addEventListener('click', () => aliasModal.classList.remove('active'));
  cancelAliasBtn.addEventListener('click', () => aliasModal.classList.remove('active'));

  // Search & Filter Listeners
  deviceSearchInput.addEventListener('input', (e) => {
    searchQuery = e.target.value;
    renderDevices();
  });

  deviceSortSelect.addEventListener('change', (e) => {
    currentSort = e.target.value;
    renderDevices();
  });

  filterTabs.forEach(tab => {
    tab.addEventListener('click', () => {
      filterTabs.forEach(t => t.classList.remove('active'));
      tab.classList.add('active');
      currentFilter = tab.getAttribute('data-filter');
      renderDevices();
    });
  });

  // Polling Controls
  pollIntervalSelect.addEventListener('change', async (e) => {
    pollInterval = parseFloat(e.target.value) * 1000;
    await fetch('/api/set-interval', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ interval: parseFloat(e.target.value) })
    });
    restartPoller();
    showToast(`Poll rate set to ${e.target.value}s`, 'info');
  });

  pauseResumeBtn.addEventListener('click', () => {
    isPaused = !isPaused;
    if (isPaused) {
      clearInterval(pollTimer);
      pauseResumeBtn.innerHTML = `
        <svg class="btn-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <polygon points="5 3 19 12 5 21 5 3"></polygon>
        </svg>
        <span>Resume</span>`;
      pauseResumeBtn.className = 'btn btn-primary';
      showToast('Live telemetry paused', 'info');
    } else {
      pauseResumeBtn.innerHTML = `
        <svg class="btn-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <rect x="6" y="4" width="4" height="16"></rect>
          <rect x="14" y="4" width="4" height="16"></rect>
        </svg>
        <span>Pause</span>`;
      pauseResumeBtn.className = 'btn btn-secondary';
      restartPoller();
      showToast('Live telemetry resumed', 'success');
    }
  });

  manualRefreshBtn.addEventListener('click', () => {
    fetchStatus();
    showToast('Telemetry refreshed', 'info');
  });

  // ---- Cellular & Cell-Lock read-only panel ----
  function setLockPill(el, on, onText, offText) {
    if (!el) return;
    el.textContent = on ? (onText || 'ON') : (offText || 'OFF');
    el.classList.toggle('on', !!on);
    el.classList.toggle('off', !on);
  }

  function updateCellLockPanel(cl, cellular) {
    const $ = (id) => document.getElementById(id);
    const cellNetType = $('cellNetType');
    if (cellNetType) cellNetType.textContent = cellular.network_type || '5G(NSA)';

    if ($('clPci5g')) $('clPci5g').textContent = cl.serving_pci_5g || '-';
    if ($('clFreq5g')) $('clFreq5g').textContent = cl.serving_freq_5g || '-';
    if ($('clPci4g')) $('clPci4g').textContent = cl.serving_pci_4g || '-';
    if ($('clFreq4g')) $('clFreq4g').textContent = cl.serving_freq_4g || '-';
    if ($('clBand5g')) $('clBand5g').textContent = cl.current_band_5g ? `n${cl.current_band_5g}` : '-';
    if ($('clBand4g')) $('clBand4g').textContent = cl.current_bands_4g && cl.current_bands_4g !== '-'
      ? 'B' + String(cl.current_bands_4g).split('+').join(' + B') : '-';

    setLockPill($('clLteLock'), cl.lte_lock_enabled, 'LOCKED', 'Auto');
    if ($('clLteLockDetail')) $('clLteLockDetail').textContent = cl.lte_lock_enabled
      ? `PCI ${cl.lte_lock_pci} · EARFCN ${cl.lte_lock_freq}` : '';
    setLockPill($('clNrLock'), cl.nr_lock_enabled, 'LOCKED', 'Auto');
    if ($('clNrLockDetail')) $('clNrLockDetail').textContent = cl.nr_lock_enabled
      ? `PCI ${cl.nr_lock_pci} · ARFCN ${cl.nr_lock_freq}` : '';

    setLockPill($('clBandLock4g'), cl.band_lock_4g_enabled, '4G Locked', '4G Auto');
    setLockPill($('clBandLock5g'), cl.band_lock_5g_enabled, '5G Locked', '5G Auto');

    if ($('clRsrp')) $('clRsrp').textContent = cellular.rsrp_5g ? `${cellular.rsrp_5g} dBm` : '-';
    if ($('clSinr')) $('clSinr').textContent = (cellular.sinr_5g_live || cellular.sinr_5g) ? `${cellular.sinr_5g_live || cellular.sinr_5g} dB` : '-';
    if ($('clCqi')) $('clCqi').textContent = cellular.nr_cqi && cellular.nr_cqi !== '-' ? `CQI ${cellular.nr_cqi}` : '-';
  }

  // ---- Reboot ----
  const rebootBtn = document.getElementById('rebootBtn');
  const rebootModal = document.getElementById('rebootModal');
  const rebootCancelBtn = document.getElementById('rebootCancelBtn');
  const rebootConfirmBtn = document.getElementById('rebootConfirmBtn');

  if (rebootBtn) rebootBtn.addEventListener('click', () => { rebootModal.style.display = 'flex'; });
  if (rebootCancelBtn) rebootCancelBtn.addEventListener('click', () => { rebootModal.style.display = 'none'; });
  if (rebootModal) rebootModal.addEventListener('click', (e) => { if (e.target === rebootModal) rebootModal.style.display = 'none'; });

  if (rebootConfirmBtn) rebootConfirmBtn.addEventListener('click', async () => {
    rebootConfirmBtn.textContent = 'Rebooting…';
    rebootConfirmBtn.disabled = true;
    try {
      const res = await fetch('/api/reboot', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ confirm: true })
      });
      const data = await res.json();
      if (data.success) {
        showToast('Reboot command sent. Router will be back in ~1–2 minutes.', 'success');
      } else {
        showToast(`Reboot failed: ${data.error || 'router rejected the command'}`, 'error');
      }
    } catch (err) {
      // A dropped connection here is expected once the router goes down.
      showToast('Reboot command sent (connection dropped, as expected).', 'info');
    } finally {
      rebootModal.style.display = 'none';
      rebootConfirmBtn.textContent = 'Yes, reboot now';
      rebootConfirmBtn.disabled = false;
    }
  });

  // ---- LAN DNS ----
  const dnsPrimaryInput = document.getElementById('dnsPrimaryInput');
  const dnsSecondaryInput = document.getElementById('dnsSecondaryInput');
  const dnsSaveBtn = document.getElementById('dnsSaveBtn');
  const dnsReloadBtn = document.getElementById('dnsReloadBtn');
  const dnsStatus = document.getElementById('dnsStatus');

  async function loadDns() {
    if (!dnsPrimaryInput) return;
    dnsStatus.textContent = 'Loading…';
    try {
      const res = await fetch('/api/dns', { cache: 'no-store' });
      const data = await res.json();
      if (!data.success) throw new Error(data.error || 'router did not answer');
      dnsPrimaryInput.value = data.primary || '';
      dnsSecondaryInput.value = data.secondary || '';
      dnsStatus.textContent = data.dhcp_enabled ? 'DHCP on' : 'DHCP off';
    } catch (err) {
      dnsStatus.textContent = 'Unavailable';
      showToast(`Could not read DNS: ${err.message}`, 'error');
    }
  }

  if (dnsReloadBtn) dnsReloadBtn.addEventListener('click', loadDns);

  if (dnsSaveBtn) dnsSaveBtn.addEventListener('click', async () => {
    const primary = dnsPrimaryInput.value.trim();
    const secondary = dnsSecondaryInput.value.trim();
    const summary = secondary ? `${primary} and ${secondary}` : `${primary} (secondary: router)`;
    if (!confirm(`Set router DNS to ${summary}?\nThe router restarts its DHCP service (about 30 seconds).`)) return;
    dnsSaveBtn.disabled = true;
    dnsSaveBtn.textContent = 'Saving…';
    dnsStatus.textContent = 'Saving…';
    try {
      const res = await fetch('/api/dns', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ confirm: true, primary, secondary })
      });
      const data = await res.json();
      if (data.success && data.verified) {
        showToast('DNS saved and confirmed by the router', 'success');
      } else if (data.success) {
        showToast('DNS saved. The router has not confirmed yet; tap Reload shortly.', 'info');
      } else {
        showToast(`DNS not changed: ${data.error}`, 'error');
      }
      if (data.primary !== undefined) {
        dnsPrimaryInput.value = data.primary;
        dnsSecondaryInput.value = data.secondary || '';
      }
      dnsStatus.textContent = data.success ? 'DHCP on' : 'Not changed';
    } catch (err) {
      dnsStatus.textContent = 'Unknown';
      showToast(`DNS save failed: ${err.message}`, 'error');
    } finally {
      dnsSaveBtn.disabled = false;
      dnsSaveBtn.textContent = 'Save DNS';
    }
  });

  // ---- Stop App (native Android only) ----
  // window.AndroidBridge is injected by the Android app's WebView. On desktop it
  // is absent, so the button stays hidden.
  const stopAppBtn = document.getElementById('stopAppBtn');
  if (stopAppBtn && window.AndroidBridge && typeof window.AndroidBridge.stopApp === 'function') {
    stopAppBtn.style.display = '';
    stopAppBtn.addEventListener('click', () => {
      if (confirm('Stop the monitor and close the app?')) {
        showToast('Stopping…', 'info');
        try { window.AndroidBridge.stopApp(); } catch (e) {}
      }
    });
  }

  function startPoller() {
    fetchStatus();
    pollTimer = setInterval(() => {
      if (!isPaused) fetchStatus();
    }, pollInterval);
  }

  function restartPoller() {
    clearInterval(pollTimer);
    if (!isPaused) startPoller();
  }

  // Initial Start
  startPoller();
  loadDns();

})();
