package com.plcoding.backgroundlocationtracking.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import com.plcoding.backgroundlocationtracking.data.local.OfflineTrackingManager
import kotlinx.coroutines.*

object NetworkMonitor {

    private const val TAG = "NetworkMonitor"
    private var isRegistered = false
    private var retryJob: Job? = null
    private var lastRetryTime = 0L

    // Giữ instance callback để có thể unregister đúng
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    fun register(context: Context) {
        if (isRegistered) {
            Log.i(TAG, "⚙️ NetworkMonitor đã được đăng ký, bỏ qua.")
            return
        }

        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "🌐 Mạng có lại → bắt đầu retry dữ liệu pending")

                val now = System.currentTimeMillis()
                if (now - lastRetryTime < 3000) {
                    Log.w(TAG, "⏳ Retry bị giới hạn (tránh spam do nhiều callback gần nhau)")
                    return
                }
                lastRetryTime = now

                retryJob?.cancel()
                retryJob = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        // Chờ 1s để đảm bảo mạng thực sự ổn định
                        delay(1000)
                        Log.i(TAG, "🔁 Gọi OfflineTrackingManager.retryOffline() ...")
                        OfflineTrackingManager.retryOffline(context)
                        Log.i(TAG, "✅ Retry hoàn tất (nếu có dữ liệu pending)")
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Lỗi khi retry pending: ${e.message}", e)
                    }
                }
            }

            override fun onLost(network: Network) {
                Log.w(TAG, "🚫 Mất kết nối mạng")
            }

            override fun onUnavailable() {
                Log.w(TAG, "⚠️ Không có mạng khả dụng")
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connectivityManager.registerDefaultNetworkCallback(networkCallback!!)
            } else {
                val networkRequest = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                connectivityManager.registerNetworkCallback(networkRequest, networkCallback!!)
            }

            isRegistered = true
            Log.i(TAG, "✅ Đã đăng ký NetworkCallback theo dõi mạng (ổn định, dài hạn)")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Không thể đăng ký NetworkCallback: ${e.message}", e)
        }
    }

    fun unregister(context: Context) {
        if (!isRegistered || networkCallback == null) return

        try {
            val connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivityManager.unregisterNetworkCallback(networkCallback!!)
            isRegistered = false
            networkCallback = null
            Log.i(TAG, "🧹 Đã hủy đăng ký NetworkCallback")
        } catch (e: Exception) {
            Log.e(TAG, "⚠️ Lỗi khi hủy đăng ký NetworkCallback: ${e.message}")
        }
    }

    /**
     * Hàm tiện ích kiểm tra mạng hiện có (có thể dùng cho retry thủ công)
     */
    fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        return capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }
}
