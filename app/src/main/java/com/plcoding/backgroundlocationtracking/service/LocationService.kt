package com.plcoding.backgroundlocationtracking.service

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.location.Location
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.LocationServices
import com.plcoding.backgroundlocationtracking.LocationApp
import com.plcoding.backgroundlocationtracking.R
import com.plcoding.backgroundlocationtracking.data.local.OfflineTrackingManager
import com.plcoding.backgroundlocationtracking.data.model.TrackingData
import com.plcoding.backgroundlocationtracking.data.network.TrackingRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen

class LocationService : Service() {

    private val TAG = "LocationService"
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var locationClient: DefaultLocationClient
    private lateinit var prefs: SharedPreferences
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var networkCallback: ConnectivityManager.NetworkCallback

    private var isReceiving = false
    private var hasValidIdentity = false
    private var deviceId: String = "UnknownDevice"
    private var title: String? = null
    private var userName: String? = null

    companion object {
        const val NOTIFICATION_ID = 1
        private const val LOCATION_INTERVAL_MS = 5000L
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "✅ Service created")

        prefs = getSharedPreferences("setup_prefs", Context.MODE_PRIVATE)
        loadIdentity()

        prefs.registerOnSharedPreferenceChangeListener { _, key ->
            if (key == "title" || key == "user_name" || key == "device_id") {
                Log.i(TAG, "🔄 SharedPrefs changed — reload identity & restart tracking")
                loadIdentity()
                checkAndStartTracking()
            }
        }

        locationClient = DefaultLocationClient(
            applicationContext,
            LocationServices.getFusedLocationProviderClient(applicationContext)
        )

        // Start foreground service với kênh notify ẩn hoàn toàn
        startForeground(NOTIFICATION_ID, createSilentNotification())

        // Khởi tạo ConnectivityManager và đăng ký network callback
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Retry offline dữ liệu ngay khi service start
        serviceScope.launch {
            try {
                val retried = OfflineTrackingManager.retryOffline(applicationContext)
                Log.i(TAG, "🚀 Retry offline data khi service start: $retried bản ghi gửi thành công.")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Lỗi retry offline khi start service: ${e.message}")
            }
        }

        checkAndStartTracking()
        return START_STICKY
    }

    private fun loadIdentity() {
        deviceId = prefs.getString("device_id", "UnknownDevice") ?: "UnknownDevice"
        title = prefs.getString("title", null)
        userName = prefs.getString("user_name", null)
        hasValidIdentity = !title.isNullOrEmpty() && !userName.isNullOrEmpty()
        Log.i(TAG, "✅ Device Identity loaded: DeviceID=$deviceId, Title=$title, UserName=$userName")
    }

    private fun checkAndStartTracking() {
        if (!hasValidIdentity) {
            Log.w(TAG, "⏸️ Chưa có Title/UserName — dừng tracking tạm thời.")
            stopReceivingLocation()
            return
        }

        if (!isReceiving) startReceivingLocation()
        else Log.i(TAG, "⚙️ Tracking đã hoạt động, không khởi động lại.")
    }

    private fun startReceivingLocation() {
        Log.i(TAG, "📡 Bắt đầu nhận location định kỳ mỗi ${LOCATION_INTERVAL_MS / 1000}s")
        isReceiving = true

        locationClient.getLocationUpdates(LOCATION_INTERVAL_MS)
            .retryWhen { cause, attempt ->
                Log.w(TAG, "⚠️ Lỗi luồng location: ${cause?.message}. Thử lại (attempt=$attempt)")
                delay(2000)
                true
            }
            .catch { e ->
                Log.e(TAG, "❌ Lỗi luồng lấy vị trí", e)
            }
            .onEach { location ->
                Log.d(TAG, "🛠️ Location flow emit: lat=${location.latitude}, lon=${location.longitude}, acc=${location.accuracy}m")
                sendLocation(location)
            }
            .launchIn(serviceScope)
    }

    private fun stopReceivingLocation() {
        if (isReceiving) {
            Log.i(TAG, "🛑 Dừng nhận location")
            serviceScope.coroutineContext.cancelChildren()
            isReceiving = false
        }
    }

    private fun sendLocation(location: Location) {
        val trackingData = TrackingData(
            DeviceID = deviceId,
            Title = title,
            Latitude = location.latitude,
            Longitude = location.longitude,
            UserName = userName
        )

        serviceScope.launch {
            val pendingCountBefore = OfflineTrackingManager.getPendingCount(applicationContext)
            Log.i(TAG, "📊 Pending offline count before send: $pendingCountBefore")

            try {
                val success = TrackingRepository.postTrackingWithRetry(
                    data = trackingData,
                    attempts = 3,
                    initialDelayMs = 1500L
                )
                if (success) Log.i(TAG, "✅ Sent successfully: $trackingData")
                else {
                    Log.w(TAG, "⚠️ Failed to send after retries, saving offline (pending)")
                    OfflineTrackingManager.saveOffline(applicationContext, trackingData)
                }

                val pendingCountAfter = OfflineTrackingManager.getPendingCount(applicationContext)
                Log.i(TAG, "📊 Pending offline count after send: $pendingCountAfter")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Exception while sending, saving offline (pending): $trackingData", e)
                OfflineTrackingManager.saveOffline(applicationContext, trackingData)
            }
        }
    }

    private fun createSilentNotification(): Notification {
        return NotificationCompat.Builder(this, LocationApp.LOCATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_action_name)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setSilent(true)
            .setOngoing(true)
            .build()
    }

    // ========================== NETWORK CALLBACK ==========================
    private fun registerNetworkCallback() {
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                Log.i(TAG, "📶 Mạng khả dụng — retry offline ngay lập tức")
                serviceScope.launch {
                    try {
                        val retried = OfflineTrackingManager.retryOffline(applicationContext)
                        Log.i(TAG, "🚀 Retry offline khi có mạng: $retried bản ghi gửi thành công.")
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Lỗi retry offline khi có mạng: ${e.message}")
                    }
                }
            }
        }

        val request = NetworkRequest.Builder().build()
        connectivityManager.registerNetworkCallback(request, networkCallback)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {}
        isReceiving = false
        Log.i(TAG, "🧹 Service destroyed — Coroutine cancelled & location stopped")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
