package com.plcoding.backgroundlocationtracking.data.network

import android.util.Log
import com.plcoding.backgroundlocationtracking.data.model.TrackingData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object TrackingRepository {
    private const val TAG = "TRACKING_REPO"

    /**
     * Gửi với retry basic.
     * Trả về true nếu gửi thành công.
     */
    suspend fun postTrackingWithRetry(data: TrackingData, attempts: Int = 3, initialDelayMs: Long = 2000L): Boolean {
        val json = data.toJsonString()

        for (i in 1..attempts) {
            val result = withContext(Dispatchers.IO) {
                ApiClient.postJsonSync(json)
            }

            val success = result.first
            val info = result.second

            if (success) {
                Log.i(TAG, "Sent tracking (attempt $i): ${data.DeviceID} -> $info")
                return true
            } else {
                Log.w(TAG, "Fail send (attempt $i): $info")
                if (i < attempts) {
                    val backoff = initialDelayMs * (1L shl (i - 1)) // exponential backoff
                    Log.i(TAG, "Retrying in $backoff ms")
                    try {
                        kotlinx.coroutines.delay(backoff)
                    } catch (e: InterruptedException) {
                        // continue
                    }
                }
            }
        }
        Log.e(TAG, "All retries failed for device ${data.DeviceID}")
        return false
    }
}
