using Microsoft.AspNetCore.Mvc;
using TrackingAPI.Data;
using TrackingAPI.Models;

namespace TrackingAPI.Controllers
{
    [Route("api/GPS_DeviceTracking")]
    [ApiController]
    public class TrackingController : ControllerBase
    {
        private readonly TrackingDbContext _context;

        [HttpGet("ping")]
        public IActionResult Ping()
        {
            return Ok("Tracking API is alive!");
        }

        public TrackingController(TrackingDbContext context)
        {
            _context = context;
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

            return Ok(new { message = "Inserted successfully", id = tracking.Oid });
        }
    }
}
