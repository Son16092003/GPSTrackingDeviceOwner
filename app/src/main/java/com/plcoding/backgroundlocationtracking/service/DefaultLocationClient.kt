package com.plcoding.backgroundlocationtracking.service

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
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

    @SuppressLint("MissingPermission")
    override fun getLocationUpdates(interval: Long): Flow<Location> = callbackFlow {
        Log.d(TAG, "🚀 [START] getLocationUpdates() với interval=${interval}ms")

        // --- 1. Kiểm tra quyền ---
        val hasFine = context.hasFineLocationPermission()
        val hasCoarse = context.hasCoarseLocationPermission()
        val hasBackground = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            context.hasBackgroundLocationPermission() else true

        Log.d(TAG, "🔍 Kiểm tra quyền:")
        Log.d(TAG, "    ➤ ACCESS_FINE_LOCATION: ${if (hasFine) "✅ GRANTED" else "❌ DENIED"}")
        Log.d(TAG, "    ➤ ACCESS_COARSE_LOCATION: ${if (hasCoarse) "✅ GRANTED" else "❌ DENIED"}")
        Log.d(TAG, "    ➤ ACCESS_BACKGROUND_LOCATION: ${if (hasBackground) "✅ GRANTED" else "❌ DENIED"}")

        if (!hasFine && !hasCoarse) {
            throw LocationClient.LocationException("Missing location permission")
        }

        // --- 2. Kiểm tra GPS ---
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)

        Log.d(TAG, "📡 Trạng thái GPS: ${if (isGpsEnabled) "✅ Enabled" else "❌ Disabled"}")

        if (!isGpsEnabled) {
            throw LocationClient.LocationException("GPS is disabled")
        }

        // --- 3. Tạo LocationRequest ---
        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            interval
        ).apply {
            setMinUpdateIntervalMillis(interval)
            setWaitForAccurateLocation(true)
        }.build()

        Log.d(TAG, "🧩 Đã tạo LocationRequest: interval=${interval}ms, priority=HIGH_ACCURACY")

        // --- 4. Tạo callback ---
        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val newLocation = result.lastLocation ?: return

                // Emit luôn, bất kể khoảng cách
                lastLocation = newLocation
                launch { send(newLocation) }

                Log.d(TAG, "📍 [Fused] lat=${newLocation.latitude}, lon=${newLocation.longitude}, acc=${newLocation.accuracy}m ✅ EMIT")
            }

            override fun onLocationAvailability(availability: LocationAvailability) {
                Log.d(TAG, "📶 Location availability: ${availability.isLocationAvailable}")
                if (!availability.isLocationAvailable) {
                    Log.w(TAG, "⚠️ Fused không khả dụng, re-register location updates...")
                    tryReRegister()
                }
            }

            private fun tryReRegister() {
                try {
                    client.removeLocationUpdates(this)
                    client.requestLocationUpdates(request, this, Looper.getMainLooper())
                    Log.d(TAG, "🔄 Re-registered FusedLocationProviderClient")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Lỗi re-register Fused: ${e.message}")
                }
            }
        }

        // --- 5. Đăng ký callback lần đầu ---
        try {
            client.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
            Log.d(TAG, "✅ Đã đăng ký cập nhật vị trí thành công.")
        } catch (e: Exception) {
            throw LocationClient.LocationException("Failed to request location updates: ${e.message}")
        }

        // --- 6. Dọn dẹp khi Flow bị cancel ---
        awaitClose {
            Log.d(TAG, "🧹 [CLOSE] Dừng cập nhật vị trí...")
            client.removeLocationUpdates(locationCallback)
            Log.d(TAG, "🧽 [DONE] Callback Fused đã gỡ")
        }
    }
}
