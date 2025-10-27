using Microsoft.EntityFrameworkCore;
using TrackingAPI.Data;
using TrackingAPI.Hubs;

var builder = WebApplication.CreateBuilder(args);

// Add services to the container
builder.Services.AddControllers();

// DbContext
builder.Services.AddDbContext<TrackingDbContext>(options =>
    options.UseSqlServer(builder.Configuration.GetConnectionString("DefaultConnection"))
);

// ✅ Thêm SignalR
builder.Services.AddSignalR();

// ✅ Thêm CORS (rất quan trọng khi frontend kết nối SignalR)
builder.Services.AddCors(options =>
{
    options.AddPolicy("AllowAll", policy =>
    {
        policy.AllowAnyHeader()
              .AllowAnyMethod()
              .AllowCredentials()
              .SetIsOriginAllowed(_ => true); // Cho phép mọi domain (kể cả ngrok)
    });
});

// Swagger
builder.Services.AddEndpointsApiExplorer();
builder.Services.AddSwaggerGen();

// Logging
builder.Logging.AddFile("Logs/tracking-{Date}.log");

var app = builder.Build();

// Middleware pipeline
if (app.Environment.IsDevelopment())
{
    app.UseSwagger();
    app.UseSwaggerUI();
}

app.UseHttpsRedirection();

// ✅ Áp dụng CORS trước khi map SignalR Hub
app.UseCors("AllowAll");

app.UseAuthorization();

app.MapControllers();

// ✅ Map Hub endpoint
app.MapHub<LocationHub>("/hubs/location");

app.Run();
