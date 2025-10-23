package com.plcoding.backgroundlocationtracking

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log

class LocationApp : Application() {

    companion object {
        const val LOCATION_CHANNEL_ID = "location"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("LocationApp", "🚀 App started")

        // ✅ Tạo notification channel ẩn hoàn toàn nếu Device Owner
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "location",
                "Background Location",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }

            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)

            Log.d("LocationApp", "✅ Silent NotificationChannel created")
        }
    }

    // 🔹 Tự động restart LocationService nếu app bị kill (nếu cần)
    private fun startCriticalServices() {
        // startForegroundService(LocationService) nếu cần
    }
}
