package com.plcoding.backgroundlocationtracking.worker

import android.content.Context
import android.util.Log
import androidx.work.*
import java.util.concurrent.TimeUnit

object RetryWorkerScheduler {

    private const val WORK_NAME = "retry_tracking_worker"

    fun schedule(context: Context) {
        try {
            // ⚙️ Điều kiện chỉ chạy khi có mạng
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            // 🕐 Lên lịch chạy định kỳ mỗi 15 phút
            val workRequest = PeriodicWorkRequestBuilder<RetryTrackingWorker>(
                15, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setInitialDelay(2, TimeUnit.MINUTES) // tránh trùng với startup app
                .addTag(WORK_NAME)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )

            Log.i("RetryWorkerScheduler", "✅ Đã lên lịch Worker retry mỗi 15 phút (chỉ chạy khi có mạng).")

        } catch (e: Exception) {
            Log.e("RetryWorkerScheduler", "❌ Lỗi khi schedule Worker: ${e.message}", e)
        }
    }
}
