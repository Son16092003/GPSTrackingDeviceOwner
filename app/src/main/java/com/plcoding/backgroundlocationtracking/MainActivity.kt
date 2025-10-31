package com.plcoding.backgroundlocationtracking

import android.Manifest
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.plcoding.backgroundlocationtracking.admin.MyDeviceAdminReceiver
import com.plcoding.backgroundlocationtracking.admin.PolicyManager
import com.plcoding.backgroundlocationtracking.receiver.BootReceiver
import com.plcoding.backgroundlocationtracking.service.LocationService
import com.plcoding.backgroundlocationtracking.ui.theme.UserIdentityDialog
import com.plcoding.backgroundlocationtracking.util.AppHider
import com.plcoding.backgroundlocationtracking.worker.RetryTrackingWorker
import com.plcoding.backgroundlocationtracking.worker.RetryWorkerScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"

    private lateinit var policyManager: PolicyManager
    private lateinit var adminComponent: ComponentName

    private val permissions = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val allGranted = result.all { it.value }
            if (allGranted) {
                Log.i(TAG, "✅ Quyền được cấp đầy đủ — hiển thị dialog nhập thông tin")
                showUserIdentityDialog()
            } else {
                Log.w(TAG, "⚠️ Người dùng từ chối quyền — hiển thị cảnh báo")
                showPermissionDialog()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedPref = getSharedPreferences("setup_prefs", Context.MODE_PRIVATE)
        if (sharedPref.getBoolean("setup_done", false)) {
            Log.i(TAG, "🚫 Setup đã hoàn thành trước đó — kiểm tra service trước khi đóng app.")

            // 🧩 Kiểm tra nếu service chưa chạy thì khởi động lại
            if (!isLocationServiceRunning()) {
                Log.w(TAG, "⚠️ LocationService chưa chạy — khởi động lại ngay.")
                startLocationService()
            } else {
                Log.i(TAG, "📍 LocationService vẫn đang hoạt động — không cần setup lại")
            }

            finishAndRemoveTask()
            return
        }

        policyManager = PolicyManager(this)
        adminComponent = ComponentName(this, MyDeviceAdminReceiver::class.java)

        // 1️⃣ Kiểm tra Device Admin / Owner
        ensureDeviceAdmin()
    }

    // ==========================
    // 🚀 Device Admin / Device Owner
    // ==========================
    private fun ensureDeviceAdmin() {
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager

        if (!dpm.isAdminActive(adminComponent)) {
            Log.w(TAG, "⚙️ App chưa có quyền Device Admin — yêu cầu người dùng kích hoạt")
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Ứng dụng cần quyền quản trị thiết bị để bảo vệ và quản lý chính sách doanh nghiệp."
                )
            }
            startActivity(intent)
            return
        } else Log.i(TAG, "✅ App đã là Device Admin")

        if (dpm.isDeviceOwnerApp(packageName)) {
            Log.i(TAG, "🏢 App hiện là DEVICE OWNER")
            applyEnterprisePolicies()
        } else Log.w(TAG, "⚠️ App chưa phải Device Owner (chỉ có quyền Device Admin)")
    }

    private fun applyEnterprisePolicies() {
        lifecycleScope.launch {
            Log.i(TAG, "🚀 Áp dụng chính sách Device Owner...")
            policyManager.blockUninstall(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) policyManager.blockLocationPermissionChanges()
            policyManager.enforceLocationPolicy()

            // 🧩 Bật BootReceiver để đảm bảo service tự khởi động sau reboot
            enableBootReceiver()

            // 2️⃣ Sau khi apply policy xong → check quyền
            checkPermissions()
        }
    }

    private fun checkPermissions() {
        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) showUserIdentityDialog()
        else requestPermissionLauncher.launch(permissions)
    }

    // ==========================
    // 📡 User Identity & Tracking
    // ==========================
    private fun showUserIdentityDialog() {
        val dialog = UserIdentityDialog(this)
        dialog.show { deviceId, title, userName ->
            Log.i(TAG, "✅ UserIdentity đã nhập: DeviceID=$deviceId, Title=$title, UserName=$userName")

            // 3️⃣ Lưu SharedPreferences trước khi start service
            val prefs = getSharedPreferences("setup_prefs", Context.MODE_PRIVATE).edit()
            prefs.putBoolean("setup_done", true)
            prefs.putString("device_id", deviceId)
            prefs.putString("title", title)
            prefs.putString("user_name", userName)
            prefs.apply()

            // 4️⃣ Start tracking
            lifecycleScope.launch {
                startTrackingSystem()

                // 5️⃣ Delay để service chạy foreground ổn định trước khi ẩn app
                withContext(Dispatchers.Main) {
                    delay(1000)
                    AppHider.hideAppIcon(this@MainActivity)
                    val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_HOME)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(homeIntent)
                    finishAndRemoveTask()
                }
            }
        }
    }

    private suspend fun startTrackingSystem() {
        var retryCount = 0
        while (!isLocationServiceRunning() && retryCount < 3) {
            Log.w(TAG, "⚠️ Service chưa khởi động, thử lại lần ${retryCount + 1}")
            startLocationService()
            delay(1000)
            retryCount++
        }

        if (isLocationServiceRunning()) {
            Log.i(TAG, "📍 LocationService đã khởi động thành công.")
        } else {
            Log.e(TAG, "❌ LocationService vẫn chưa khởi động được sau 3 lần thử.")
        }

        scheduleRetryWorker()
        Log.i(TAG, "🚀 Tracking system khởi động hoàn chỉnh")
    }

    private fun startLocationService() {
        val serviceIntent = Intent(this, LocationService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            ContextCompat.startForegroundService(this, serviceIntent)
        else
            startService(serviceIntent)
        Log.i(TAG, "📡 Đã khởi động LocationService (Foreground - ẩn hoàn toàn)")
    }

    private fun isLocationServiceRunning(): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return manager.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == LocationService::class.java.name }
    }

    private fun scheduleRetryWorker() {
        val oneTimeRequest = OneTimeWorkRequestBuilder<RetryTrackingWorker>().build()
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            "RetryTrackingWorkerOnce", ExistingWorkPolicy.REPLACE, oneTimeRequest
        )
        RetryWorkerScheduler.schedule(applicationContext)
        Log.i(TAG, "⏰ Lên lịch periodic RetryTrackingWorker")
    }

    private fun showPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("Yêu cầu quyền truy cập")
            .setMessage("Ứng dụng cần quyền Location và Notification để hoạt động chính xác.")
            .setPositiveButton("Thử lại") { _, _ -> requestPermissionLauncher.launch(permissions) }
            .setNegativeButton("Thoát") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun enableBootReceiver() {
        val receiver = ComponentName(this, BootReceiver::class.java)
        packageManager.setComponentEnabledSetting(
            receiver,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )
        Log.i(TAG, "🔔 BootReceiver đã được bật đảm bảo tự khởi động sau reboot")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "🧹 MainActivity bị hủy (app vẫn chạy nền qua service).")
    }
}
