package com.plcoding.backgroundlocationtracking.admin

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.UserManager
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * PolicyManager: Quản lý chính sách Device Admin / Device Owner
 * Dùng trong ứng dụng tracking để:
 *  - Ngăn gỡ cài đặt app
 *  - Giữ GPS luôn bật
 *  - Ép quyền location luôn granted
 *  - Tự động khởi động lại tracking sau reboot
 */
class PolicyManager(private val context: Context) {

    private val dpm: DevicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val adminComponent = ComponentName(context, MyDeviceAdminReceiver::class.java)

    companion object {
        private const val TAG = "PolicyManager"
    }

    /** ✅ Kiểm tra xem app đã được set làm Device Admin chưa */
    fun isAdminActive(): Boolean = dpm.isAdminActive(adminComponent)

    /** ✅ Kiểm tra xem app có phải Device Owner (MDM) hay không */
    fun isDeviceOwner(): Boolean = dpm.isDeviceOwnerApp(context.packageName)

    /** 🔒 Khóa màn hình ngay lập tức */
    fun lockDevice() {
        if (isAdminActive()) {
            try {
                dpm.lockNow()
                Log.d(TAG, "🔒 Device locked by PolicyManager")
            } catch (e: Exception) {
                Log.e(TAG, "❌ lockDevice() failed: ${e.message}")
            }
        } else {
            Log.w(TAG, "⚠️ lockDevice() yêu cầu quyền Device Admin")
        }
    }

    /** 🧹 Xóa toàn bộ dữ liệu thiết bị (Factory Reset) */
    fun wipeData() {
        if (isAdminActive()) {
            try {
                dpm.wipeData(0)
                Log.d(TAG, "🧹 wipeData() executed successfully")
            } catch (e: Exception) {
                Log.e(TAG, "❌ wipeData() failed: ${e.message}")
            }
        } else {
            Log.w(TAG, "⚠️ wipeData() yêu cầu quyền Device Admin")
        }
    }

    /** 🔑 Reset lại mật khẩu thiết bị */
    fun resetPassword(newPass: String) {
        if (isAdminActive()) {
            try {
                dpm.resetPassword(newPass, 0)
                Log.d(TAG, "🔑 Password reset to: $newPass")
            } catch (e: Exception) {
                Log.e(TAG, "❌ resetPassword() failed: ${e.message}")
            }
        } else {
            Log.w(TAG, "⚠️ resetPassword() yêu cầu quyền Device Admin")
        }
    }

    /** 🚫 Chặn người dùng gỡ app (chỉ khi là Device Owner, API >= 24) */
    fun blockUninstall(enable: Boolean) {
        if (isDeviceOwner() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                dpm.setUninstallBlocked(adminComponent, context.packageName, enable)
                Log.d(TAG, "✅ setUninstallBlocked = $enable for ${context.packageName}")
            } catch (e: Exception) {
                Log.e(TAG, "❌ blockUninstall() failed: ${e.message}")
            }
        } else {
            Log.w(TAG, "⚠️ blockUninstall() yêu cầu Device Owner + API >= 24")
        }
    }

    /**
     * 🛰️ Ép quyền location luôn Granted
     * - Chặn user thu hồi quyền Location
     * - Tự động cấp quyền trong tương lai
     */
    @RequiresApi(Build.VERSION_CODES.M)
    fun blockLocationPermissionChanges() {
        if (!isAdminActive()) {
            Log.w(TAG, "⚠️ blockLocationPermissionChanges() yêu cầu Device Admin")
            return
        }

        try {
            // 1️⃣ Ép quyền Location luôn Granted
            dpm.setPermissionGrantState(
                adminComponent,
                context.packageName,
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
            )
            dpm.setPermissionGrantState(
                adminComponent,
                context.packageName,
                android.Manifest.permission.ACCESS_COARSE_LOCATION,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
            )

            // 2️⃣ Auto-grant các quyền trong tương lai
            dpm.setPermissionPolicy(
                adminComponent,
                DevicePolicyManager.PERMISSION_POLICY_AUTO_GRANT
            )

            Log.d(TAG, "✅ Location permissions permanently granted & auto-managed")
        } catch (e: Exception) {
            Log.e(TAG, "❌ blockLocationPermissionChanges() failed: ${e.message}")
        }
    }

    /**
     * 📍 Ép GPS luôn bật & cấm chỉnh Location trong Settings
     * - DISALLOW_CONFIG_LOCATION: cấm user thay đổi Location Settings
     * - setLocationEnabled(): API 30+ có thể ép bật GPS
     */
    fun enforceLocationPolicy() {
        if (!isAdminActive()) {
            Log.w(TAG, "⚠️ enforceLocationPolicy() yêu cầu Device Admin")
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_CONFIG_LOCATION)
                Log.d(TAG, "✅ User cannot change Location settings")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                dpm.setLocationEnabled(adminComponent, true)
                Log.d(TAG, "✅ GPS forced ON (API 30+)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ enforceLocationPolicy() failed: ${e.message}")
        }
    }

    /**
     * 🧭 Hàm tổng hợp: kích hoạt toàn bộ chính sách cần thiết cho tracking 24/7
     */
    fun applyFullPolicy() {
        Log.d(TAG, "🚀 [INIT] Áp dụng toàn bộ Device Policy cho tracking...")

        if (!isAdminActive()) {
            Log.w(TAG, "⚠️ App chưa được set làm Device Admin — cần kích hoạt trước.")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            blockLocationPermissionChanges()
        }
        enforceLocationPolicy()
        blockUninstall(true)

        Log.d(TAG, "✅ [DONE] Tất cả Device Policy đã được áp dụng thành công.")
    }
}
