import { startIcon, pauseIcon, liveIcon, endIcon } from "./icons.js";
import { devices, getColorFromId, updateDeviceList } from "./devices.js";

export let summaryMarkers = [];
export let detailedMarkers = [];

// Helper: parse various timestamp representations into a Date
function parseTimestamp(ts) {
  if (ts instanceof Date) return ts;
  if (ts === null || ts === undefined) return new Date();
  if (typeof ts === 'number') return new Date(ts);
  const s = String(ts).trim();
  if (/^\d+$/.test(s)) return new Date(Number(s));
  const d = new Date(s);
  if (!isNaN(d.getTime())) return d;
  return new Date();
}

// Helper: formatted timestamp for popup
function formatTimestamp(ts) {
  const d = parseTimestamp(ts);
  try {
    return d.toLocaleString('vi-VN');
  } catch (e) {
    return d.toString();
  }
}

// Debounce timer cho việc fit map
let fitMapTimer = null;

/**
 * Fit bản đồ đến các thiết bị đang active (realtime và visible)
 */
function fitMapToActiveDevices(map, immediate = false) {
  const performFit = () => {
    const activeDevices = Object.values(devices).filter(
      d => d.visible && d.status === "realtime" && d.coords.length > 0 && !d.isOffline
    );

    if (activeDevices.length === 0) return;

    // Lấy tọa độ mới nhất của tất cả thiết bị active
    const activeCoords = activeDevices.map(device => {
      const lastCoord = device.coords[device.coords.length - 1];
      return [lastCoord.lat, lastCoord.lon];
    });

    if (activeCoords.length === 1) {
      // Nếu chỉ có 1 thiết bị, center và zoom đến vị trí đó
      map.setView(activeCoords[0], 16);
    } else {
      // Nếu có nhiều thiết bị, fit bounds để hiển thị tất cả
      const bounds = L.latLngBounds(activeCoords);
      map.fitBounds(bounds.pad(0.1)); // Thêm 10% padding
    }
  };

  if (immediate) {
    // Fit ngay lập tức cho thiết bị mới
    performFit();
  } else {
    // Clear timer cũ
    if (fitMapTimer) {
      clearTimeout(fitMapTimer);
    }

    // Debounce 2 giây để tránh fit liên tục
    fitMapTimer = setTimeout(performFit, 2000);
  }
}

/**
 * Thêm điểm Live (realtime) + trail + trạng thái
 * map: mapLive truyền vào
 */
export function addLivePoint(deviceId, lat, lon, timestamp, userName, map) {
  const color = getColorFromId(deviceId);
  const time = new Date(Number(timestamp));

  if (!devices[deviceId]) {
    devices[deviceId] = {
      deviceId,
      userName,
      coords: [],
      trailMarkers: [],
      color,
      visible: true,
      liveMarker: null,
      liveTimer: null,
      lastTimestamp: time,
      status: "realtime", // realtime, pause, offline
      isOffline: false,
    };
  }

  const device = devices[deviceId];
  device.coords.push({ lat, lon, timestamp: time });
  if (device.coords.length > 50) device.coords.shift();

  device.lastTimestamp = time;
  device.status = "realtime";
  device.isOffline = false; // Đánh dấu là online lại

  // Remove old liveMarker nếu có
  if (device.liveMarker) {
    map.removeLayer(device.liveMarker);
    clearInterval(device.liveTimer);
  }

  const popupContentLive = `📡 ${userName ? userName + ' — ' : ''}${deviceId}<br>${formatTimestamp(time)}`;
  const marker = L.marker([lat, lon], { icon: liveIcon }).bindPopup(popupContentLive);

  if (device.visible) marker.addTo(map);
  device.liveMarker = marker;

  // Timer check trạng thái Realtime / Pause / Offline
  device.liveTimer = setInterval(() => {
    const now = new Date();
    const diffSec = (now - device.lastTimestamp) / 1000;

    let newStatus = "realtime";
    if (diffSec > 180) newStatus = "offline"; // >3 phút
    else if (diffSec > 60) newStatus = "pause"; // >1 phút

    if (newStatus !== device.status) {
      device.status = newStatus;

      // Xử lý icon
      if (device.liveMarker) map.removeLayer(device.liveMarker);

      if (newStatus === "realtime") {
        device.liveMarker = L.marker([lat, lon], { icon: liveIcon }).addTo(map);
      } else if (newStatus === "pause") {
        device.liveMarker = L.marker([lat, lon], { icon: pauseIcon }).addTo(map);
      } else if (newStatus === "offline") {
        // Hiển thị endIcon thay vì xóa marker
        device.liveMarker = L.marker([lat, lon], { icon: endIcon })
          .bindPopup(`📴 ${deviceId} - Offline<br>${device.lastTimestamp.toLocaleString("vi-VN")}`)
          .addTo(map);
        
        // Giữ lại trail, không xóa
        // Đánh dấu là offline để không hiển thị trong sidebar
        device.isOffline = true;
        
        // Dừng timer vì đã offline
        clearInterval(device.liveTimer);
        device.liveTimer = null;
        
        // Cập nhật sidebar để xóa thiết bị offline
        updateDeviceList(map);
      }
    }
  }, 5000);

  // Thêm trail
  const trailMarker = L.circleMarker([lat, lon], { radius: 4, color });
  // Bind popup to trail markers showing deviceId and timestamp (no userName for historical trail here)
  try {
    trailMarker.bindPopup(`${deviceId}<br>${formatTimestamp(time)}`);
  } catch (e) { /* ignore */ }
  if (device.visible) trailMarker.addTo(map);
  device.trailMarkers.push(trailMarker);

  // Fit bản đồ đến thiết bị realtime nếu đang visible
  if (device.visible && device.status === "realtime") {
    // Nếu đây là điểm đầu tiên của thiết bị, fit ngay lập tức
    const isFirstPoint = device.coords.length === 1;
    fitMapToActiveDevices(map, isFirstPoint);
  }

  updateDeviceList(map);
}

/**
 * Load toàn bộ điểm Summary
 */
export function addSummaryPoints(deviceId, coords, map) {
  let device = devices[deviceId] || { coords: [], trailMarkers: [], color: "#3388ff" };

  // Xóa marker cũ
  device.trailMarkers.forEach((m) => map.removeLayer(m));
  if (device.liveMarker) {
    map.removeLayer(device.liveMarker);
    clearInterval(device.liveTimer);
  }

  // Parse timestamp robustly: support epoch-ms (number or numeric string) and ISO strings
  device.coords = coords.map((c) => {
    let ts = c.timestamp;
    let parsed;
    // If timestamp is a numeric string or number, treat as epoch ms
    if (ts === null || ts === undefined) {
      parsed = new Date();
    } else if (typeof ts === 'number') {
      parsed = new Date(ts);
    } else if (/^\d+$/.test(String(ts).trim())) {
      parsed = new Date(Number(ts));
    } else {
      // Try parsing ISO or other date string
      parsed = new Date(String(ts));
      if (isNaN(parsed.getTime())) {
        // Fallback: current time
        parsed = new Date();
      }
    }

    return {
      lat: c.latitude,
      lon: c.longitude,
      timestamp: parsed,
    };
  });
  device.trailMarkers = [];

  console.log(`addSummaryPoints: deviceId=${deviceId} - ${device.coords.length} coords to plot`);
  if (device.coords.length > 0) {
    console.log("Sample first coord:", device.coords[0]);
    if (device.coords.length > 1) console.log("Sample last coord:", device.coords[device.coords.length - 1]);
  }

  if (device.coords.length === 0) return;

  // Start marker
  const first = device.coords[0];
  const startPopup = `Bắt đầu<br>${devices[deviceId]?.userName ? devices[deviceId].userName + ' — ' : ''}${deviceId}<br>${formatTimestamp(first.timestamp)}`;
  const startMarker = L.marker([first.lat, first.lon], { icon: startIcon })
    .addTo(map)
    .bindPopup(startPopup);
  device.trailMarkers.push(startMarker);
  summaryMarkers.push(startMarker);
  // Open the popup for the start marker so it's visible immediately
  try { startMarker.openPopup(); } catch (e) { /* ignore */ }

  // Trail
  // If there are many coordinates, draw a simplified representation: a polyline
  // for the full path and sampled markers to avoid performance issues.
  const LARGE_TRACK_THRESHOLD = 3000; // if coords exceed this, simplify
  const MAX_SAMPLE_MARKERS = 500; // max number of circle markers to draw when simplifying

  if (device.coords.length > LARGE_TRACK_THRESHOLD) {
    // Large dataset: draw only sampled markers (start, sample, end) to reduce clutter.
    const formattedCount = device.coords.length.toLocaleString();
    const infoMsg = `ℹ️ Dữ liệu lớn (${formattedCount} điểm) — hiển thị mẫu để cải thiện hiệu năng.`;
    try {
      if (window && window.showSummaryMessage) {
        window.showSummaryMessage(infoMsg, 'info');
      } else {
        console.log(infoMsg);
      }
    } catch (e) { /* ignore */ }

    // Sample markers along the path
    const step = Math.max(1, Math.ceil(device.coords.length / MAX_SAMPLE_MARKERS));
    for (let i = 0; i < device.coords.length; i += step) {
      const c = device.coords[i];
  const m = L.circleMarker([c.lat, c.lon], { radius: 4, color: device.color });
  m.addTo(map);
  try { m.bindPopup(`${deviceId}<br>${formatTimestamp(c.timestamp)}`); } catch (e) { /* ignore */ }
  device.trailMarkers.push(m);
  summaryMarkers.push(m);
    }

  } else {
    device.coords.forEach((c) => {
      const m = L.circleMarker([c.lat, c.lon], { radius: 4, color: device.color });
      m.addTo(map);
      try { m.bindPopup(`${deviceId}<br>${formatTimestamp(c.timestamp)}`); } catch (e) { /* ignore */ }
      device.trailMarkers.push(m);
      summaryMarkers.push(m);
    });
  }

  // End marker
  const last = device.coords[device.coords.length - 1];
  const endPopup = `Kết thúc<br>${devices[deviceId]?.userName ? devices[deviceId].userName + ' — ' : ''}${deviceId}<br>${formatTimestamp(last.timestamp)}`;
  const endMarker = L.marker([last.lat, last.lon], { icon: endIcon })
    .addTo(map)
    .bindPopup(endPopup);
  device.trailMarkers.push(endMarker);
  summaryMarkers.push(endMarker);

  console.log(`addSummaryPoints: created ${device.trailMarkers.length} markers for device ${deviceId}`);

  devices[deviceId] = device;

  // Fit map
  const allCoords = device.coords.map((c) => [c.lat, c.lon]);
  map.fitBounds(L.latLngBounds(allCoords).pad(0.2));
}

/**
 * Add detailed points (full markers) for a given set of coords (used for zoomed-in view)
 */
export function addDetailedPoints(deviceId, coords, map, maxPoints = 5000) {
  // Clear existing detailed markers first
  clearDetailedMarkers(map);

  const toPlot = coords.slice(0, maxPoints);
  toPlot.forEach((c) => {
    const m = L.circleMarker([c.latitude, c.longitude], { radius: 4, color: devices[deviceId]?.color || '#3388ff' });
    m.addTo(map);
    try { m.bindPopup(`${devices[deviceId]?.userName ? devices[deviceId].userName + ' — ' : ''}${deviceId}<br>${formatTimestamp(c.timestamp)}`); } catch (e) { /* ignore */ }
    detailedMarkers.push(m);
  });

  console.log(`addDetailedPoints: plotted ${detailedMarkers.length} detailed markers (capped at ${maxPoints})`);
}

export function clearDetailedMarkers(map) {
  detailedMarkers.forEach((m) => m.remove());
  detailedMarkers = [];
}

/**
 * Xóa tất cả marker summary cũ
 */
export function clearSummaryMarkers(map) {
  // Xóa tất cả markers từ summaryMarkers array (nếu có)
  summaryMarkers.forEach((m) => m.remove());
  summaryMarkers = [];
  
  // Xóa tất cả trail markers từ tất cả devices
  Object.values(devices).forEach((device) => {
    if (device.trailMarkers && device.trailMarkers.length > 0) {
      device.trailMarkers.forEach((marker) => {
        if (map) {
          map.removeLayer(marker);
        } else {
          marker.remove();
        }
      });
      device.trailMarkers = [];
    }
    
    // Xóa live marker nếu có (trong summary mode không cần live marker)
    if (device.liveMarker && map) {
      map.removeLayer(device.liveMarker);
      device.liveMarker = null;
    }
    
    // Clear timer nếu có
    if (device.liveTimer) {
      clearInterval(device.liveTimer);
      device.liveTimer = null;
    }
  });
  
  const deviceCount = Object.keys(devices).length;
  console.log(`🧹 Cleared all summary markers from ${deviceCount} devices on map`);
}

/**
 * Export hàm fit map để sử dụng từ file khác
 */
export function fitMapToActiveDevicesExternal(map, immediate = false) {
  fitMapToActiveDevices(map, immediate);
}
