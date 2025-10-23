using System.Collections.Generic;
using Microsoft.EntityFrameworkCore;
using TrackingAPI.Models;

namespace TrackingAPI.Data
{
    public class TrackingDbContext : DbContext
    {
        public TrackingDbContext(DbContextOptions<TrackingDbContext> options)
            : base(options)
        {
        }

        public DbSet<GPSDeviceTracking> GPS_DeviceTracking { get; set; }
    }
}
