package com.plcoding.backgroundlocationtracking.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.ListenableWorker.Result
import com.plcoding.backgroundlocationtracking.data.local.OfflineTrackingManager

class RetryTrackingWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val tag = "RetryTrackingWorker"
        return try {
            Log.i(tag, "🔄 Bắt đầu retry dữ liệu pending qua WorkManager...")

            val successCount = OfflineTrackingManager.retryOffline(applicationContext)

            if (successCount > 0) {
                Log.i(tag, "✅ Retry hoàn tất: $successCount bản ghi gửi thành công.")
            } else {
                Log.i(tag, "ℹ️ Không có dữ liệu pending hoặc không gửi được bản ghi nào.")
            }

            // Trả về thành công, WorkManager không cần retry
            Result.success()

        } catch (e: Exception) {
            Log.e(tag, "❌ Lỗi khi retry pending: ${e.message}", e)

            // Cho phép WorkManager tự động retry lại sau backoff delay
            Result.retry()
        }
    }
}
