package com.plcoding.backgroundlocationtracking.service

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import com.plcoding.backgroundlocationtracking.hasFineLocationPermission
import com.plcoding.backgroundlocationtracking.hasCoarseLocationPermission
import com.plcoding.backgroundlocationtracking.hasBackgroundLocationPermission

class DefaultLocationClient(
    private val context: Context,
    private val client: FusedLocationProviderClient
) : LocationClient {

    companion object {
        private const val TAG = "DefaultLocationClient"
    }

    private var lastLocation: Location? = null
    private var fallbackListener: LocationListener? = null

    @SuppressLint("MissingPermission")
    override fun getLocationUpdates(interval: Long): Flow<Location> = callbackFlow {
        Log.i(TAG, "🚀 [START] getLocationUpdates() interval=${interval}ms")

        // --- 1️⃣ Kiểm tra quyền ---
        val hasFine = context.hasFineLocationPermission()
        val hasCoarse = context.hasCoarseLocationPermission()
        val hasBackground = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            context.hasBackgroundLocationPermission() else true

        Log.i(TAG, "🔍 Quyền hiện tại:")
        Log.i(TAG, "   ➤ Fine: ${if (hasFine) "✅" else "❌"} | Coarse: ${if (hasCoarse) "✅" else "❌"} | Background: ${if (hasBackground) "✅" else "❌"}")

        if (!hasFine && !hasCoarse) {
            throw LocationClient.LocationException("❌ Thiếu quyền truy cập vị trí")
        }

        // --- 2️⃣ Kiểm tra GPS ---
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        Log.i(TAG, "📡 GPS: ${if (isGpsEnabled) "✅ Enabled" else "❌ Disabled"}")

        if (!isGpsEnabled) {
            throw LocationClient.LocationException("❌ GPS đang bị tắt")
        }

        // --- 3️⃣ Tạo LocationRequest ---
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, interval)
            .setMinUpdateIntervalMillis(interval)
            .setWaitForAccurateLocation(true)
            .build()
        Log.i(TAG, "🧩 LocationRequest tạo thành công (interval=$interval, priority=HIGH_ACCURACY)")

        // --- 4️⃣ Tạo callback ---
        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                lastLocation = loc
                launch { send(loc) }
                Log.i(TAG, "📍 [Fused] lat=${loc.latitude}, lon=${loc.longitude}, acc=${loc.accuracy}m ✅ EMIT")
            }

            override fun onLocationAvailability(availability: LocationAvailability) {
                Log.w(TAG, "📶 Fused availability = ${availability.isLocationAvailable}")
                if (!availability.isLocationAvailable) {
                    Log.w(TAG, "⚠️ Fused không khả dụng, kích hoạt fallback → LocationManager")
                    startFallback(locationManager, interval, this@callbackFlow)
                }
            }
        }

        // --- 5️⃣ Đăng ký cập nhật ---
        try {
            client.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
            Log.i(TAG, "✅ FusedLocationProviderClient đăng ký thành công.")
        } catch (e: Exception) {
            Log.e(TAG, "❌ FusedLocationProvider lỗi: ${e.message}")
            Log.w(TAG, "⏪ Chuyển sang fallback LocationManager...")
            startFallback(locationManager, interval, this)
        }

        // --- 6️⃣ Dọn dẹp ---
        awaitClose {
            Log.i(TAG, "🧹 Hủy cập nhật vị trí...")
            try {
                client.removeLocationUpdates(locationCallback)
                fallbackListener?.let { locationManager.removeUpdates(it) }
                Log.i(TAG, "🧽 Dừng tất cả location callbacks.")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Lỗi khi cleanup: ${e.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startFallback(
        locationManager: LocationManager,
        interval: Long,
        scope: kotlinx.coroutines.channels.ProducerScope<Location>
    ) {
        try {
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    lastLocation = location
                    scope.launch { scope.send(location) }
                    Log.i(TAG, "📍 [Fallback] lat=${location.latitude}, lon=${location.longitude}, acc=${location.accuracy}m ✅ EMIT")
                }

                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
                    Log.d(TAG, "ℹ️ Fallback provider status: $provider ($status)")
                }
            }

            fallbackListener = listener
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                interval,
                0f,
                listener,
                Looper.getMainLooper()
            )
            Log.i(TAG, "✅ Fallback LocationManager kích hoạt thành công.")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Fallback LocationManager lỗi: ${e.message}")
        }
    }
}
