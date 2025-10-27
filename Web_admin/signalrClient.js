// --- signalrClient.js ---
let connection = null;

/**
 * Khởi tạo và bắt đầu kết nối tới SignalR Hub
 * @param {string} hubUrl - URL tới .NET Hub, ví dụ: http://0.0.0.0:5089/hubs/location
 * @param {object} handlers - Danh sách hàm callback cho các sự kiện nhận từ server
 */
export async function startSignalR(hubUrl, handlers = {}) {
  if (!hubUrl) throw new Error("❌ Hub URL is required");

  if (connection) {
    try {
      await connection.stop();
    } catch {}
    connection = null;
  }

  connection = new signalR.HubConnectionBuilder()
    .withUrl(hubUrl)
    // Enable automatic reconnect with default retry delays (0, 2s, 10s, 30s)
    .withAutomaticReconnect()
    .configureLogging(signalR.LogLevel.Information)
    .build();

  // Gắn các event handler
  for (const [eventName, handler] of Object.entries(handlers)) {
    // wrap handler to log payloads
    connection.on(eventName, (payload) => {
      try {
        console.debug(`[signalr] Event ${eventName} received:`, payload);
      } catch (e) {}
      try {
        handler(payload);
      } catch (err) {
        console.error(`[signalr] Handler for ${eventName} threw`, err);
      }
    });
  }

  // Detailed lifecycle logging for reconnects/close
  connection.onreconnecting((err) => {
    console.warn("⚠️ SignalR reconnecting:", err);
  });

  connection.onreconnected((connectionId) => {
    console.log("🔁 SignalR reconnected. ConnectionId:", connectionId);
  });

  // onclose will be invoked after reconnect attempts fail (if automatic reconnect gives up)
  connection.onclose((err) => {
    console.warn("⚠️ SignalR connection closed:", err);
    // Don't immediately restart here; automatic reconnect already attempted.
    // If you want stronger retry, uncomment the following fallback to restart manually after delay.
    // setTimeout(() => startSignalR(hubUrl, handlers), 5000);
  });

  try {
    await connection.start();
    console.log("✅ Connected to SignalR hub:", hubUrl);
  } catch (err) {
    console.error("❌ Cannot connect to SignalR hub:", err);
    setTimeout(() => startSignalR(hubUrl, handlers), 5000);
  }

  return connection;
}

export function getConnection() {
  return connection;
}
