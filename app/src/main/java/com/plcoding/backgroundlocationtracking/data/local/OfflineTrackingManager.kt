package com.plcoding.backgroundlocationtracking.data.local

import android.content.Context
import android.util.Log
import com.plcoding.backgroundlocationtracking.data.model.TrackingData
import com.plcoding.backgroundlocationtracking.data.network.TrackingRepository
import kotlinx.coroutines.*

class OfflineTrackingManager private constructor(context: Context) {

    private val TAG = "OfflineTrackingManager"
    private val dao = TrackingDatabase.getInstance(context).offlineTrackingDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var isRetrying = false

    companion object {
        @Volatile
        private var INSTANCE: OfflineTrackingManager? = null

        fun getInstance(context: Context): OfflineTrackingManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: OfflineTrackingManager(context.applicationContext).also { INSTANCE = it }
            }
        }

        fun saveOffline(context: Context, tracking: TrackingData) {
            getInstance(context).saveTracking(tracking)
        }

        suspend fun retryOffline(context: Context): Int {
            return getInstance(context).startRetryQueue()
        }

        // ✅ Sửa lại hàm này thành suspend
        suspend fun getPendingCount(context: Context): Int {
            val pendingList = getInstance(context).dao.getAll() // gọi suspend
            return pendingList.size
        }
    }


    /**
     * Lưu bản ghi vào Room DB (khi không có mạng)
     */
    private fun saveTracking(tracking: TrackingData) {
        scope.launch {
            try {
                dao.insert(
                    OfflineTrackingEntity(
                        oid = tracking.Oid,
                        deviceID = tracking.DeviceID,
                        title = tracking.Title,
                        latitude = tracking.Latitude,
                        longitude = tracking.Longitude,
                        recordDate = tracking.RecordDate,
                        optimisticLockField = tracking.OptimisticLockField,
                        gcRecord = tracking.GCRecord,
                        userName = tracking.UserName
                    )
                )
                Log.w(
                    TAG,
                    "💾 Lưu dữ liệu pending offline: [Oid=${tracking.Oid}, Device=${tracking.DeviceID}, Lat=${tracking.Latitude}, Lon=${tracking.Longitude}]"
                )
            } catch (e: Exception) {
                Log.e(TAG, "❌ Lỗi khi lưu dữ liệu offline: ${e.message}")
            }
        }
    }

    /**
     * Gửi lại toàn bộ dữ liệu offline khi có mạng
     */
    private suspend fun startRetryQueue(): Int {
        if (isRetrying) {
            Log.i(TAG, "⏳ Retry đang chạy, bỏ qua lần gọi này.")
            return 0
        }

        isRetrying = true
        var successCount = 0

        return withContext(Dispatchers.IO) {
            try {
                val pendingList = dao.getAll().toMutableList()
                if (pendingList.isEmpty()) {
                    Log.i(TAG, "✅ Không có dữ liệu pending để retry.")
                    return@withContext 0
                }

                Log.i(TAG, "🚀 Bắt đầu retry ${pendingList.size} bản ghi offline...")

                for (item in pendingList) {
                    val trackingData = TrackingData(
                        Oid = item.oid,
                        DeviceID = item.deviceID,
                        Title = item.title,
                        Latitude = item.latitude,
                        Longitude = item.longitude,
                        RecordDate = item.recordDate,
                        OptimisticLockField = item.optimisticLockField,
                        GCRecord = item.gcRecord,
                        UserName = item.userName
                    )

                    val success = try {
                        TrackingRepository.postTrackingWithRetry(trackingData)
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Lỗi gửi ID=${item.id}: ${e.message}")
                        false
                    }

                    if (success) {
                        dao.deleteById(item.id)
                        successCount++
                        Log.i(TAG, "✅ Gửi thành công ID=${item.id}, xóa khỏi pending.")
                    } else {
                        Log.w(TAG, "⚠️ Gửi thất bại ID=${item.id}, giữ lại pending.")
                    }

                    delay(1000) // throttle tránh spam server
                }

                Log.i(
                    TAG,
                    "🎯 Retry hoàn tất: $successCount/${pendingList.size} bản ghi gửi thành công."
                )
                successCount
            } catch (e: Exception) {
                Log.e(TAG, "❌ Lỗi trong startRetryQueue: ${e.message}")
                0
            } finally {
                isRetrying = false
            }
        }
    }
}
