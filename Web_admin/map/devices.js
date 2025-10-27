export let devices = {};

/**
 * Sinh màu cố định từ deviceId
 */
export function getColorFromId(deviceId) {
  let hash = 0;
  for (let i = 0; i < deviceId.length; i++) {
    hash = deviceId.charCodeAt(i) + ((hash << 5) - hash);
  }
  return "#" + ((hash >> 0) & 0xffffff).toString(16).padStart(6, "0");
}

/**
 * Toggle hiển thị device (Live mode)
 */
export function toggleDevice(deviceId, visible, mapInstance) {
  const device = devices[deviceId];
  if (!device || !mapInstance) return;
  device.visible = visible;

  // Hiển thị/ẩn trail
  device.trailMarkers?.forEach((m) => (visible ? m.addTo(mapInstance) : m.remove()));

  // Hiển thị/ẩn live marker (bao gồm cả offline marker với endIcon)
  if (device.liveMarker) {
    if (visible) {
      device.liveMarker.addTo(mapInstance);
    } else {
      device.liveMarker.remove();
    }
  }
}

/**
 * Cập nhật danh sách sidebar Live
 */
export function updateDeviceList(mapInstance) {
  const container = document.getElementById("device-list");
  if (!container) return;
  container.innerHTML = "";

  // Lấy danh sách userName duy nhất từ các deviceId (chỉ thiết bị online)
  const userMap = {}; // { userName: [deviceId1, deviceId2] }
  Object.values(devices).forEach((d) => {
    if (!d.userName || d.isOffline) return; // Bỏ qua thiết bị offline
    if (!userMap[d.userName]) userMap[d.userName] = [];
    userMap[d.userName].push(d.deviceId);
  });

  Object.keys(userMap).forEach((userName) => {
    const div = document.createElement("div");
    div.style.marginBottom = "5px";

    const checkbox = document.createElement("input");
    checkbox.type = "checkbox";
    // nếu bất kỳ device của user này visible thì tick
    checkbox.checked = userMap[userName].some((id) => devices[id].visible);
    checkbox.onchange = () => {
      userMap[userName].forEach((id) =>
        toggleDevice(id, checkbox.checked, mapInstance)
      );
      
      // Save visibility preferences
      import('../main.js').then(module => {
        if (module.saveDeviceVisibility) {
          module.saveDeviceVisibility();
        }
      }).catch(() => {
        // Fallback - call via window if module import fails
        if (window.saveDeviceVisibility) {
          window.saveDeviceVisibility();
        }
      });
      
      // Nếu bật thiết bị lên, fit map đến các thiết bị active
      if (checkbox.checked) {
        // Import và gọi hàm fit từ markers.js
        import('./markers.js').then(module => {
          if (module.fitMapToActiveDevicesExternal) {
            module.fitMapToActiveDevicesExternal(mapInstance, true);
          }
        });
      }
    };

    const colorBox = document.createElement("span");
    colorBox.style.display = "inline-block";
    colorBox.style.width = "12px";
    colorBox.style.height = "12px";
    colorBox.style.background = devices[userMap[userName][0]].color; // màu đại diện user
    colorBox.style.marginRight = "6px";
    colorBox.style.verticalAlign = "middle";

    const label = document.createElement("label");
    label.style.marginLeft = "5px";
    label.innerText = userName;

    div.appendChild(checkbox);
    div.appendChild(colorBox);
    div.appendChild(label);
    container.appendChild(div);
  });
}

