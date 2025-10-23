package com.plcoding.backgroundlocationtracking.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import android.util.Log
import com.plcoding.backgroundlocationtracking.service.LocationService

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                Log.i(TAG, "🔄 Thiết bị vừa khởi động lại — chuẩn bị khởi chạy LocationService")

                val serviceIntent = Intent(context, LocationService::class.java)

                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        ContextCompat.startForegroundService(context, serviceIntent)
                        Log.i(TAG, "📡 Đang khởi chạy foreground service (Android O+)")
                    } else {
                        context.startService(serviceIntent)
                        Log.i(TAG, "📡 Đang khởi chạy background service (dưới Android O)")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Lỗi khi khởi động LocationService sau reboot: ${e.message}", e)
                }
            }

            else -> {
                Log.w(TAG, "⚠️ Nhận intent không mong đợi: ${intent?.action}")
            }
        }
    }
}
