// --- main.js ---
import { createMap } from "./map/mapInit.js";
import { devices, getColorFromId, updateDeviceList } from "./map/devices.js";
import { startIcon, pauseIcon, liveIcon, endIcon } from "./map/icons.js";
import { startSignalR } from "./signalrClient.js";

// URL của SignalR Hub (.NET Web API)
// NOTE: '0.0.0.0' is a server listen address and is NOT routable from the browser.
// If you run the server locally, use 'http://localhost:5089/hubs/location' or
// 'http://127.0.0.1:5089/hubs/location' or the machine's LAN IP.
const HUB_URL = "http://192.168.0.114:5089/hubs/location"; // đổi thành URL thật của bạn

let mapLive = null;
let mapSummary = null;

// Auto-fit configuration and debounce timer
let _autoFitTimer = null;
const autoFitConfig = {
  delay: 2000, // ms debounce for non-immediate fits
  padding: [50, 50], // pixels padding passed to fitBounds
  onlyFitWhenToggled: true, // if true, only fit when a device is toggled on (or explicit 'toggle' reason). Set to true so updates don't re-center map.
  minDevices: 1, // minimum number of devices to trigger fitBounds (if >1 will only fit bounds)
  singleDeviceZoom: 16 // zoom level when centering a single device
};

// Expose a runtime API to adjust auto-fit behavior
window.setAutoFitOptions = function(opts = {}) {
  Object.assign(autoFitConfig, opts || {});
  console.info('[main] autoFitConfig updated:', autoFitConfig);
};

window.getAutoFitOptions = function() {
  return Object.assign({}, autoFitConfig);
};

// Allow other modules or console to trigger fit
window.fitMapToActiveDevices = function(reason) {
  try {
    autoFitActiveDevices(reason);
  } catch (e) {
    console.error('[main] fitMapToActiveDevices error', e);
  }
};

window.saveDeviceVisibility = saveDeviceVisibility; // để devices.js có thể gọi

// ================================================
// 1️⃣ Khởi tạo map & tab giao diện
// ================================================
document.addEventListener("DOMContentLoaded", async () => {
  mapLive = createMap("map-live");
  mapSummary = createMap("map-summary");

  // Leaflet sometimes needs an explicit invalidateSize after the container is laid out
  // give the browser a tick to finish layout then invalidate
  setTimeout(() => {
    try {
      mapLive?.invalidateSize();
    } catch (e) {
      console.warn('Could not invalidate mapLive size', e);
    }
  }, 200);

  // Tab switching
  const tabLive = document.getElementById("tab-live");
  const tabSummary = document.getElementById("tab-summary");

  tabLive.addEventListener("click", () => switchTab("live"));
  tabSummary.addEventListener("click", () => switchTab("summary"));

  // Hướng dẫn dialog
  const guideBtn = document.getElementById("btn-guide");
  const guideDialog = document.getElementById("guide-dialog");
  const closeGuide = document.getElementById("close-guide");
  guideBtn.onclick = () => (guideDialog.style.display = "flex");
  closeGuide.onclick = () => (guideDialog.style.display = "none");

  // Load trạng thái hiển thị thiết bị từ localStorage
  loadDeviceVisibility();

  // Khôi phục trạng thái thiết bị (last known positions) từ localStorage
  restoreDevicesFromStorage();

  // After restoring from storage, ensure map layout is ready and fit to active devices
  setTimeout(() => {
    try {
      mapLive?.invalidateSize();
    } catch (e) {}
    try {
      // On refresh we want to fit to all devices present on the interface
      fitMapToAllDevices();
    } catch (e) {
      console.warn('[main] fitMapToAllDevices after restore failed', e);
    }
  }, 300);

  // Kết nối SignalR realtime
  createSignalRStatusUI();
  initSignalR();
});

function switchTab(tab) {
  const liveMap = document.getElementById("map-live");
  const summaryMap = document.getElementById("map-summary");
  const liveSidebar = document.getElementById("live-sidebar");
  const summarySidebar = document.getElementById("summary-sidebar");
  const tabLive = document.getElementById("tab-live");
  const tabSummary = document.getElementById("tab-summary");

  if (tab === "live") {
    tabLive.classList.add("active");
    tabSummary.classList.remove("active");
    liveMap.style.display = "block";
    summaryMap.style.display = "none";
    liveSidebar.style.display = "block";
    summarySidebar.style.display = "none";
  } else {
    tabSummary.classList.add("active");
    tabLive.classList.remove("active");
    summaryMap.style.display = "block";
    liveMap.style.display = "none";
    summarySidebar.style.display = "block";
    liveSidebar.style.display = "none";
  }

  // Invalidate sizes for whichever map is now visible (allow layout)
  setTimeout(() => {
    try {
      if (tab === "live") {
        mapLive?.invalidateSize();
      } else {
        mapSummary?.invalidateSize();
      }
    } catch (e) {
      console.warn('Could not invalidate map size after tab switch', e);
    }
  }, 150);
}

// ================================================
// 2️⃣ SignalR handlers
// ================================================
function initSignalR() {
  const handlers = {
    // original handler (kept)
    ReceiveTracking: (data) => {
      if (!data) return;
      console.debug('[main] ReceiveTracking payload:', data);
      updateSignalRStatus('lastMessage', JSON.stringify(data));
      handleRealtimeTracking(data);
    },
    // some servers emit a 'connected' notification — show in UI
    connected: (payload) => {
      console.info('[main] server -> connected:', payload);
      updateSignalRStatus('connected', String(payload || 'server connected'));
    },
    // server in your logs calls 'receivelocationupdate' (lowercase) — handle it
    ReceiveLocationUpdate: (data) => {
      console.debug('[main] ReceiveLocationUpdate payload:', data);
      updateSignalRStatus('lastMessage', JSON.stringify(data));
      handleRealtimeTracking(data);
    },
    receivelocationupdate: (data) => {
      console.debug('[main] receivelocationupdate payload:', data);
      updateSignalRStatus('lastMessage', JSON.stringify(data));
      handleRealtimeTracking(data);
    }
  };

  // startSignalR will manage connection and reconnection; await to update UI status
  // But first sanity-check the HUB_URL: browsers can't reach 0.0.0.0
  let effectiveHubUrl = HUB_URL;
  try {
    const urlObj = new URL(HUB_URL);
    if (urlObj.hostname === '0.0.0.0') {
      // warn user and attempt a localhost fallback (common for local dev)
      updateSignalRStatus('error', "HUB_URL uses 0.0.0.0 which is not browser-routable.");
      console.warn("[main] HUB_URL uses 0.0.0.0 — replacing with 'localhost' for browser connect attempt.");
      urlObj.hostname = 'localhost';
      effectiveHubUrl = urlObj.toString();
      // also show fallback in UI
      updateSignalRStatus('connected', `Trying fallback: ${effectiveHubUrl}`);
    }
  } catch (e) {
    // ignore URL parse errors — we'll try original HUB_URL
  }

  startSignalR(effectiveHubUrl, handlers)
    .then((conn) => {
      updateSignalRStatus('connected', effectiveHubUrl);
      console.log('[main] SignalR connection established to', effectiveHubUrl);
    })
    .catch((err) => {
      updateSignalRStatus('error', String(err));
      console.error('[main] SignalR failed to start', err);
    });
}

// ================================================
// 3️⃣ Xử lý sự kiện Realtime từ Hub
// ================================================
function handleRealtimeTracking(data) {
  // Normalize commonly used field names coming from different server DTOs
  const deviceId = data.deviceId || data.DeviceID || data.DeviceId || data.deviceID || data.Device || data.device;
  const userName = data.userName || data.UserName || data.Title || data.title || data.username;
  const latitude = data.latitude || data.Latitude || data.Lat || data.lat || data.LatitudeValue;
  const longitude = data.longitude || data.Longitude || data.Lng || data.lng || data.lon || data.LongitudeValue;
  const timestamp = data.timestamp || data.Timestamp || data.RecordDate || data.RecordDateTime || data.recordDate || data.recordedAt || data.time;

  if (!deviceId || latitude == null || longitude == null) {
    console.warn('[main] Ignoring tracking payload due to missing fields:', data);
    return;
  }

  console.info(`[main] Processing device=${deviceId} lat=${latitude} lon=${longitude} user=${userName}`);

  let device = devices[deviceId];
  const color = getColorFromId(deviceId);

  // Nếu thiết bị chưa có, tạo mới
  const isNewDevice = !device;
  if (isNewDevice) {
    device = {
      deviceId,
      userName,
      color,
      coords: [],
      trailMarkers: [],
      visible: true,
      isOffline: false,
    };
    devices[deviceId] = device;
    console.info(`[main] Created new device entry for ${deviceId}`);
  }

  // Tạo marker vị trí hiện tại
  const latLng = L.latLng(latitude, longitude);

  // push to coords history (keep last 500)
  try {
    device.coords = device.coords || [];
    // normalize coord entry so other modules (markers.js) can read .lat and .lon
    const coordEntry = {
      latitude: Number(latitude),
      longitude: Number(longitude),
      lat: Number(latitude),
      lon: Number(longitude),
      // store timestamp as ms number for deterministic parsing
      timestamp: (device && device.lastTimestamp instanceof Date) ? device.lastTimestamp.getTime() : (parseTimestamp(timestamp).getTime())
    };
    device.coords.push(coordEntry);
    if (device.coords.length > 500) {
      device.coords.splice(0, device.coords.length - 500);
    }
  } catch (e) {
    console.warn('[main] error pushing to device.coords', e);
  }

  // Nếu có marker cũ → thêm vệt trail
  // Tạo một điểm trail dạng circle marker (thay vì vẽ polyline)
  try {
    if (device.visible) {
      const trailPoint = L.circleMarker(latLng, { radius: 4, color: device.color });
      trailPoint.addTo(mapLive);
      device.trailMarkers.push(trailPoint);
    }
  } catch (e) {
    console.warn('[main] error creating trail circle marker', e);
  }

  // Cập nhật marker chính
  if (device.liveMarker) {
    device.liveMarker.setLatLng(latLng);
    console.info(`[main] Updated existing marker for ${deviceId}`);
  } else {
    device.liveMarker = L.marker(latLng, { icon: liveIcon })
      .addTo(mapLive)
      .bindPopup(`<b>${userName || deviceId}</b><br>${new Date(timestamp).toLocaleTimeString()}`);
    console.info(`[main] Added new marker for ${deviceId}`);
  }

  // Update last seen timestamp (prefer server timestamp if available)
  // Parse server timestamp robustly (handles ms, seconds, ISO strings). Fallbacks to now.
  const lastTs = parseTimestamp(timestamp);
  device.lastLatLng = latLng;
  device.lastTimestamp = lastTs;
  device.lastUpdate = Date.now();
  device.isOffline = false;

  // Ensure status is set to realtime immediately when new data arrives
  try {
    device.status = 'realtime';
    device.isOffline = false;
    if (device.liveMarker) {
      try { device.liveMarker.setIcon(liveIcon); } catch (e) {}
    }
  } catch (e) {}

  // If there was a scheduled removal (device was marked offline earlier), cancel it now
  try {
    if (device.removeTimer) {
      clearTimeout(device.removeTimer);
      device.removeTimer = null;
      console.info('[main] cancelled scheduled removal for', deviceId);
    }
  } catch (e) {}

  // Update popup and permanent tooltip label (username • time)
  try {
    if (device.liveMarker) {
      // update popup content if present
      try {
        const popup = device.liveMarker.getPopup && device.liveMarker.getPopup();
        if (popup && popup.setContent) {
          popup.setContent(`<b>${device.userName || device.deviceId}</b><br>${device.lastTimestamp.toLocaleTimeString()}`);
        }
      } catch (e) {}

      // update or bind a permanent tooltip label above the marker
      try {
        const label = getDeviceLabel(device);
        if (device.liveMarker.getTooltip && device.liveMarker.getTooltip()) {
          device.liveMarker.getTooltip().setContent(label);
        } else {
          device.liveMarker.bindTooltip(label, { permanent: true, direction: 'top', offset: [0, -20], className: 'device-label' }).openTooltip();
        }
      } catch (e) {}
    }
  } catch (e) {
    /* ignore tooltip update errors */
  }

  // Ensure a liveTimer exists to monitor realtime/pause/offline status
  if (!device.liveTimer) {
    device.liveTimer = setInterval(() => {
      try {
        const now = new Date();
        const diffSec = (now - (device.lastTimestamp || now)) / 1000;

        let newStatus = 'realtime';
        if (diffSec > 180) newStatus = 'offline';
        else if (diffSec > 60) newStatus = 'pause';

        if (newStatus !== device.status) {
          device.status = newStatus;

          // Update marker icon according to status
          if (device.liveMarker) {
            try {
              if (newStatus === 'realtime') {
                device.liveMarker.setIcon(liveIcon);
              } else if (newStatus === 'pause') {
                device.liveMarker.setIcon(pauseIcon);
              } else if (newStatus === 'offline') {
                device.liveMarker.setIcon(endIcon);
              }
            } catch (e) {
              console.warn('[main] error setting icon for', deviceId, e);
            }
          }

          // Mark offline flag and update device list when changed
          device.isOffline = newStatus === 'offline';
          updateDeviceList(mapLive);

          // If offline, stop the status timer and schedule removal from map after grace period
          if (newStatus === 'offline') {
            try {
              clearInterval(device.liveTimer);
            } catch (e) {}
            device.liveTimer = null;

            // clear any existing removal timer
            if (device.removeTimer) {
              clearTimeout(device.removeTimer);
              device.removeTimer = null;
            }

            // schedule removal after 60 seconds (configurable later)
            device.removeTimer = setTimeout(() => {
              try {
                // remove visuals and mark device as not visible so it won't be restored to map
                removeDeviceVisuals(deviceId);
              } catch (e) {
                console.warn('[main] error during scheduled removal for', deviceId, e);
              }
            }, 60 * 1000);
          }
        }
      } catch (e) {
        console.warn('[main] liveTimer error for', deviceId, e);
      }
    }, 5000);
  }

  updateDeviceList(mapLive);
  // Choose reason: 'new' if first point, else 'update'
  autoFitActiveDevices(isNewDevice ? 'new' : 'update');

  // Persist last-known state (debounced)
  schedulePersist();
}

// Remove device visuals (live marker and trail) from the map and mark device as hidden.
function removeDeviceVisuals(deviceId) {
  try {
    const device = devices[deviceId];
    if (!device) return;

    // Remove live marker
    try {
      if (device.liveMarker) {
        device.liveMarker.remove();
        device.liveMarker = null;
      }
    } catch (e) {}

    // Remove trail markers
    try {
      if (device.trailMarkers && device.trailMarkers.length) {
        device.trailMarkers.forEach((m) => {
          try { m.remove(); } catch (e) {}
        });
        device.trailMarkers = [];
      }
    } catch (e) {}

    // Clear any timers
    try { if (device.liveTimer) { clearInterval(device.liveTimer); device.liveTimer = null; } } catch (e) {}
    try { if (device.removeTimer) { clearTimeout(device.removeTimer); device.removeTimer = null; } } catch (e) {}

    // Mark as not visible so it won't be shown on refresh
      // Remove device object entirely to free memory and ensure it won't be restored
      try {
        delete devices[deviceId];
      } catch (e) {
        // fallback: mark not visible if delete fails
        try { device.visible = false; } catch (ee) {}
      }

      updateDeviceList(mapLive);

      // Persist state so removal persists across refresh (device will no longer be in devices)
      schedulePersist();
  } catch (e) {
    console.warn('[main] removeDeviceVisuals error for', deviceId, e);
  }
}

// ================================================
// 4️⃣ Các tiện ích
// ================================================
function autoFitActiveDevices() {
  // legacy signature support: if first arg is a reason, use it
  const args = Array.from(arguments);
  const reason = typeof args[0] === 'string' ? args[0] : null; // 'new' | 'update' | 'toggle'

  const performFit = () => {
    const activeLatLngs = Object.values(devices)
      .filter((d) => d.visible && d.liveMarker && !d.isOffline)
      .map((d) => d.liveMarker.getLatLng());

    if (activeLatLngs.length === 0) return;

    // If autoFitConfig.onlyFitWhenToggled is true and reason is not 'toggle' or 'new', skip
    if (autoFitConfig.onlyFitWhenToggled && reason !== 'toggle' && reason !== 'new') {
      return;
    }

    // If fewer than minDevices and only one device, center and zoom
    if (activeLatLngs.length <= autoFitConfig.minDevices) {
      const latlng = activeLatLngs[0];
      mapLive.setView(latlng, autoFitConfig.singleDeviceZoom);
      return;
    }

    const bounds = L.latLngBounds(activeLatLngs);
    // padding: allow either number or array
    const pad = autoFitConfig.padding || [50, 50];
    mapLive.fitBounds(bounds, { padding: pad });
  };

  // Debounce behavior
  if (reason === 'new') {
    // immediate fit for new device first point
    performFit();
    return;
  }

  if (_autoFitTimer) clearTimeout(_autoFitTimer);
  _autoFitTimer = setTimeout(performFit, autoFitConfig.delay);
}

// Small helper: produce the label content shown above the marker (username • time)
function getDeviceLabel(device) {
  const name = device.userName || device.deviceId || 'unknown';
  let timeStr = '';
  try {
    const ts = device.lastTimestamp instanceof Date ? device.lastTimestamp : new Date(device.lastTimestamp);
    if (!isNaN(ts.getTime())) timeStr = ts.toLocaleTimeString();
  } catch (e) {
    timeStr = '';
  }
  return timeStr ? `${name} • ${timeStr}` : `${name}`;
}

// Fit map to all devices currently known/visible (used on refresh to show full area)
function fitMapToAllDevices() {
  try {
    const latLngs = Object.values(devices)
      .filter(d => d && d.visible)
      .map(d => {
        if (d.lastLatLng && d.lastLatLng.lat != null && d.lastLatLng.lng != null) return d.lastLatLng;
        if (d.coords && d.coords.length > 0) {
          const last = d.coords[d.coords.length - 1];
          if (last && (last.lat != null || last.latitude != null)) {
            return L.latLng(Number(last.lat != null ? last.lat : last.latitude), Number(last.lon != null ? last.lon : last.longitude));
          }
        }
        return null;
      })
      .filter(Boolean);

    if (!mapLive || latLngs.length === 0) return;

    if (latLngs.length === 1) {
      mapLive.setView(latLngs[0], autoFitConfig.singleDeviceZoom);
      return;
    }

    const bounds = L.latLngBounds(latLngs);
    const pad = autoFitConfig.padding || [50, 50];
    mapLive.fitBounds(bounds, { padding: pad });
  } catch (e) {
    console.warn('[main] fitMapToAllDevices error', e);
  }
}

// Robust timestamp parser: accepts Date, ms number, seconds number, ISO/string. Returns a Date.
function parseTimestamp(ts) {
  try {
    if (!ts && ts !== 0) return new Date();
    if (ts instanceof Date) {
      if (!isNaN(ts.getTime())) return ts;
      return new Date();
    }
    // numeric: could be seconds or milliseconds
    if (typeof ts === 'number') {
      // if seconds (<=1e10), convert to ms
      if (ts > 0 && ts < 1e11) {
        // likely seconds
        if (ts < 1e10) return new Date(ts * 1000);
      }
      const d = new Date(ts);
      if (!isNaN(d.getTime())) return d;
      return new Date();
    }
    if (typeof ts === 'string') {
      // try numeric string first
      const n = Number(ts);
      if (!isNaN(n)) {
        return parseTimestamp(n);
      }
      const d = new Date(ts);
      if (!isNaN(d.getTime())) return d;
      return new Date();
    }
  } catch (e) {
    /* fallback */
  }
  return new Date();
}

function saveDeviceVisibility() {
  const visibility = {};
  Object.keys(devices).forEach((id) => {
    visibility[id] = devices[id].visible;
  });
  localStorage.setItem("deviceVisibility", JSON.stringify(visibility));
}

function loadDeviceVisibility() {
  const saved = localStorage.getItem("deviceVisibility");
  if (!saved) return;
  const visibility = JSON.parse(saved);
  Object.keys(visibility).forEach((id) => {
    if (devices[id]) devices[id].visible = visibility[id];
  });
}

// -----------------------
// SignalR status UI helpers (debug)
// -----------------------
function createSignalRStatusUI() {
  if (document.getElementById('signalr-status')) return;
  const div = document.createElement('div');
  div.id = 'signalr-status';
  div.style.position = 'fixed';
  div.style.right = '10px';
  div.style.bottom = '10px';
  div.style.zIndex = 3000;
  div.style.background = 'rgba(1,31,130,0.95)';
  div.style.color = 'white';
  div.style.padding = '8px 12px';
  div.style.borderRadius = '8px';
  div.style.fontSize = '13px';
  div.style.boxShadow = '0 6px 18px rgba(0,0,0,0.3)';
  div.innerHTML = `<div style="font-weight:600;margin-bottom:4px">SignalR</div><div id="signalr-status-state">Not connected</div><div id="signalr-status-last" style="opacity:0.9;margin-top:6px;font-size:12px;max-width:260px;word-break:break-word">-</div>`;
  document.body.appendChild(div);
}

function updateSignalRStatus(type, payload) {
  const state = document.getElementById('signalr-status-state');
  const last = document.getElementById('signalr-status-last');
  if (!state || !last) return;
  if (type === 'connected') {
    state.innerText = `Connected → ${payload}`;
    state.style.color = '#b8f2ff';
  } else if (type === 'error') {
    state.innerText = `Error: ${payload}`;
    state.style.color = '#ffcccc';
  } else if (type === 'lastMessage') {
    last.innerText = payload;
  }
}

// Debug helper: call from browser console to simulate incoming tracking payload
// Example usage in console:
// window.testTracking({ DeviceID: 'dev123', Latitude: 10.8, Longitude: 106.6, RecordDate: new Date().toISOString(), Title: 'User A' })
window.testTracking = function(payload) {
  console.info('[debug] testTracking payload:', payload);
  try {
    handleRealtimeTracking(payload);
  } catch (e) {
    console.error('[debug] testTracking error', e);
  }
};

// Persist last-known device positions to localStorage so UI can restore after refresh
function persistDevicesState() {
  try {
    const state = {};
    Object.keys(devices).forEach((id) => {
      const d = devices[id];
      if (!d) return;
      // only persist if we have a lastLatLng
      const latLng = d.lastLatLng || (d.liveMarker && d.liveMarker.getLatLng());
      if (!latLng) return;
      state[id] = {
        deviceId: d.deviceId,
        userName: d.userName,
        latitude: latLng.lat,
        longitude: latLng.lng,
        // store timestamp as milliseconds to make restore parsing deterministic
        timestamp: (d.lastTimestamp instanceof Date ? d.lastTimestamp.getTime() : (typeof d.lastTimestamp === 'number' ? d.lastTimestamp : (d.lastUpdate || Date.now()))),
        visible: !!d.visible,
        color: d.color,
        // normalize coords for persistence: ensure latitude/longitude/timestamp(ms)
        coords: (d.coords || []).slice(-500).map((c) => ({
          latitude: c.latitude != null ? Number(c.latitude) : (c.lat != null ? Number(c.lat) : null),
          longitude: c.longitude != null ? Number(c.longitude) : (c.lon != null ? Number(c.lon) : null),
          timestamp: (c.timestamp instanceof Date ? c.timestamp.getTime() : (typeof c.timestamp === 'number' ? c.timestamp : (Number(c.timestamp) || Date.now())))
        })) // last up to 500 points
      };
    });
    localStorage.setItem('deviceLastState', JSON.stringify(state));
  } catch (e) {
    console.warn('[main] persistDevicesState error', e);
  }
}

function restoreDevicesFromStorage() {
  try {
    const raw = localStorage.getItem('deviceLastState');
    if (!raw) return;
    const state = JSON.parse(raw);
    const keys = Object.keys(state);
    keys.forEach((k) => {
      const s = state[k];
      try {
        const id = s.deviceId;
        const color = s.color || getColorFromId(id);
        // normalize restored coords: ensure each entry has latitude, longitude, lat, lon and numeric timestamp (ms)
        const restoredCoords = Array.isArray(s.coords) ? s.coords.slice(-500).map((c) => {
          const lat = c.latitude != null ? Number(c.latitude) : (c.lat != null ? Number(c.lat) : null);
          const lon = c.longitude != null ? Number(c.longitude) : (c.lon != null ? Number(c.lon) : null);
          const tsNum = (typeof c.timestamp === 'number') ? c.timestamp : (c.timestamp ? Number(c.timestamp) : NaN);
          const timestampMs = !isNaN(tsNum) && tsNum > 0 ? tsNum : (new Date().getTime());
          if (lat == null || lon == null) {
            console.warn('[main] restore coord missing lat/lon for', id, c);
          }
          return {
            latitude: lat,
            longitude: lon,
            lat: lat,
            lon: lon,
            timestamp: timestampMs
          };
        }) : [];

        const device = {
          deviceId: id,
          userName: s.userName,
          color,
          coords: restoredCoords,
          trailMarkers: [],
          visible: s.visible !== false,
          isOffline: false,
          liveMarker: null,
          lastLatLng: null,
        };
        devices[id] = device;

        // recreate trail polyline if coords exist
        if (device.coords && device.coords.length > 0) {
          // recreate trail as circle markers (last up to 500 points)
          try {
            device.coords.forEach((c) => {
              try {
                const m = L.circleMarker([c.latitude, c.longitude], { radius: 4, color: device.color });
                if (device.visible) m.addTo(mapLive);
                device.trailMarkers.push(m);
              } catch (e) {
                /* ignore individual marker errors */
              }
            });
          } catch (e) {
            console.warn('[main] error restoring trail markers for', id, e);
          }

          // add live marker at last point
          const last = device.coords[device.coords.length - 1];
          try {
            const lastLatLng = L.latLng(last.latitude, last.longitude);
            device.lastLatLng = lastLatLng;

            // determine lastTimestamp and status based on stored timestamp
            let lastTs = null;
            // Parse stored timestamp and validate; fallback to local time if invalid
            if (last.timestamp) {
              lastTs = new Date(last.timestamp);
              if (isNaN(lastTs.getTime())) {
                console.warn('[main] restored timestamp invalid for', id, last.timestamp, '— using now');
                lastTs = new Date();
              }
            } else {
              lastTs = new Date();
            }
            device.lastTimestamp = lastTs;

            // compute status: realtime (<60s), pause (60-180s), offline (>180s)
            const now = new Date();
            const diffSec = (now - device.lastTimestamp) / 1000;
            let status = 'realtime';
            if (diffSec > 180) status = 'offline';
            else if (diffSec > 60) status = 'pause';
            device.status = status;
            device.isOffline = status === 'offline';

            // pick icon according to status
            let icon = liveIcon;
            if (status === 'pause') icon = pauseIcon;
            else if (status === 'offline') icon = endIcon;

            device.liveMarker = L.marker(lastLatLng, { icon }).addTo(mapLive)
              .bindPopup(`<b>${device.userName || id}</b><br>${new Date(device.lastTimestamp).toLocaleTimeString()}`);

            // bind a permanent tooltip label showing username and time
            try {
              const label = getDeviceLabel(device);
              device.liveMarker.bindTooltip(label, { permanent: true, direction: 'top', offset: [0, -20], className: 'device-label' }).openTooltip();
            } catch (e) {
              /* ignore tooltip binding errors */
            }

            // start liveTimer to monitor status transitions (unless already offline)
            if (!device.isOffline) {
              device.liveTimer = setInterval(() => {
                try {
                  const now2 = new Date();
                  const diff = (now2 - (device.lastTimestamp || now2)) / 1000;
                  let newStatus = 'realtime';
                  if (diff > 180) newStatus = 'offline';
                  else if (diff > 60) newStatus = 'pause';

                  // optional debug logging (enable by setting window.DEBUG_STATUS = true in console)
                  if (window.DEBUG_STATUS) {
                    console.debug('[main] statusTimer', id, 'diffSec=', Math.round(diff), 'current=', device.status, 'new=', newStatus);
                  }

                  if (newStatus !== device.status) {
                    device.status = newStatus;
                    device.isOffline = newStatus === 'offline';
                    try {
                      if (device.liveMarker) {
                        if (newStatus === 'realtime') device.liveMarker.setIcon(liveIcon);
                        else if (newStatus === 'pause') device.liveMarker.setIcon(pauseIcon);
                        else if (newStatus === 'offline') device.liveMarker.setIcon(endIcon);
                      }
                    } catch (e) {}
                    updateDeviceList(mapLive);
                    if (newStatus === 'offline') {
                      clearInterval(device.liveTimer);
                      device.liveTimer = null;
                    }
                  }
                } catch (e) {
                  /* ignore */
                }
              }, 5000);
            }

            // If already offline when restored, schedule removal after 60s
            if (device.isOffline) {
              try {
                if (device.removeTimer) clearTimeout(device.removeTimer);
                device.removeTimer = setTimeout(() => {
                  removeDeviceVisuals(id);
                }, 60 * 1000);
              } catch (e) {}
            }

          } catch (e) {
            console.warn('[main] error restoring liveMarker for', id, e);
          }
        }
      } catch (e) {
        console.warn('[main] restoreDevicesFromStorage entry error', e);
      }
    });

    updateDeviceList(mapLive);
    console.info('[main] Restored devices from storage:', Object.keys(state).length);
  } catch (e) {
    console.warn('[main] restoreDevicesFromStorage error', e);
  }
}

// Persist state periodically and on unload
window.addEventListener('beforeunload', () => {
  persistDevicesState();
});

// Also persist when devices change (throttle to avoid excessive writes)
let _persistTimer = null;
function schedulePersist() {
  if (_persistTimer) clearTimeout(_persistTimer);
  _persistTimer = setTimeout(() => {
    persistDevicesState();
  }, 500);
}
