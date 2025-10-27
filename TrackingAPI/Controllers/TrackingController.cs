using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.SignalR;
using TrackingAPI.Data;
using TrackingAPI.Hubs;
using TrackingAPI.Models;

namespace TrackingAPI.Controllers
{
    [Route("api/GPS_DeviceTracking")]
    [ApiController]
    public class TrackingController : ControllerBase
    {
        private readonly TrackingDbContext _context;
        private readonly IHubContext<LocationHub> _hubContext; // ✅ Thêm SignalR HubContext

        public TrackingController(TrackingDbContext context, IHubContext<LocationHub> hubContext)
        {
            _context = context;
            _hubContext = hubContext;
        }

        [HttpGet("ping")]
        public IActionResult Ping()
        {
            return Ok("Tracking API is alive!");
        }

        [HttpPost]
        public async Task<IActionResult> PostTracking([FromBody] GPSDeviceTracking tracking)
        {
            if (tracking == null)
                return BadRequest("Invalid data.");

            // Nếu client không gửi RecordDate thì gán thời gian hiện tại
            if (tracking.RecordDate == default)
                tracking.RecordDate = DateTime.UtcNow.AddHours(7);

            _context.GPS_DeviceTracking.Add(tracking);
            await _context.SaveChangesAsync();

            // ✅ Gửi realtime đến tất cả client đang kết nối (webadmin)
            await _hubContext.Clients.All.SendAsync("ReceiveLocationUpdate", tracking);

            return Ok(new { message = "Inserted successfully & broadcasted", id = tracking.Oid });
        }
    }
}
