/**
 * Tạo map instance với id container cụ thể
 */
export function createMap(containerId) {
  const map = L.map(containerId).setView([10.762622, 106.660172], 13);
  L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
    attribution: "© OpenStreetMap contributors",
  }).addTo(map);
  return map;
}
