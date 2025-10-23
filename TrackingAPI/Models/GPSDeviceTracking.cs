using System;
using System.ComponentModel.DataAnnotations;
using System.ComponentModel.DataAnnotations.Schema;

namespace TrackingAPI.Models
{
    [Table("GPS_DeviceTracking")]
    public class GPSDeviceTracking
    {
        [Key]
        public Guid Oid { get; set; } = Guid.NewGuid();

        [Required]
        [StringLength(1000)]
        public string DeviceID { get; set; } = string.Empty;

        [StringLength(100)]
        public string? Title { get; set; }

        [Required]
        public double Latitude { get; set; }

        [Required]
        public double Longitude { get; set; }

        [Required]
        public DateTime RecordDate { get; set; }

        public int? OptimisticLockField { get; set; }
        public int? GCRecord { get; set; }

        [StringLength(100)]
        public string? UserName { get; set; }
    }
}
