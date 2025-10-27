using Microsoft.AspNetCore.SignalR;

namespace TrackingAPI.Hubs
{
    public class LocationHub : Hub
    {
        // Server gọi hàm này để broadcast dữ liệu vị trí mới cho tất cả client
        public async Task BroadcastLocation(object trackingData)
        {
            await Clients.All.SendAsync("ReceiveLocation", trackingData);
        }

        // Có thể gọi riêng từng nhóm nếu sau này bạn chia nhóm theo user/device
        public async Task BroadcastToGroup(string groupName, object trackingData)
        {
            await Clients.Group(groupName).SendAsync("ReceiveLocation", trackingData);
        }

        // Khi client kết nối
        public override async Task OnConnectedAsync()
        {
            Console.WriteLine($"🔗 Client connected: {Context.ConnectionId}");
            await Clients.Caller.SendAsync("Connected", Context.ConnectionId);
            await base.OnConnectedAsync();
        }

        // Khi client ngắt kết nối
        public override async Task OnDisconnectedAsync(Exception? exception)
        {
            Console.WriteLine($"❌ Client disconnected: {Context.ConnectionId}");
            await base.OnDisconnectedAsync(exception);
        }
    }
}
