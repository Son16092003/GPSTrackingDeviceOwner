package com.plcoding.backgroundlocationtracking.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
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
                Log.i(TAG, "🔄 Thiết bị vừa khởi động lại — kiểm tra điều kiện trước khi bật service")

                val prefs = context.getSharedPreferences("setup_prefs", Context.MODE_PRIVATE)
                val title = prefs.getString("title", null)
                val userName = prefs.getString("user_name", null)

                // Kiểm tra danh tính trước khi bật tracking
                if (title.isNullOrEmpty() || userName.isNullOrEmpty()) {
                    Log.w(TAG, "⚠️ Thiếu thông tin title hoặc userName — bỏ qua khởi động LocationService sau reboot.")
                    return
                }

                val serviceIntent = Intent(context, LocationService::class.java)

                // Giữ CPU hoạt động trong vài giây để đảm bảo service kịp khởi chạy
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                val wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BootReceiver::WakeLock")
                wakeLock.acquire(5000)

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
                } finally {
                    if (wakeLock.isHeld) {
                        wakeLock.release()
                    }
                }
            }

            else -> {
                Log.w(TAG, "⚠️ Nhận intent không mong đợi: ${intent?.action}")
            }
        }
    }
}
