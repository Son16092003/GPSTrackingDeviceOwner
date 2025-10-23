package com.plcoding.backgroundlocationtracking.admin

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.UserManager
import android.util.Log
import androidx.annotation.RequiresApi

class PolicyManager(private val context: Context) {
    private val dpm: DevicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val adminComponent = ComponentName(context, MyDeviceAdminReceiver::class.java)

    /** Kiểm tra xem app đã được set làm Device Admin chưa */
    fun isAdminActive(): Boolean = dpm.isAdminActive(adminComponent)

    /** Khóa màn hình ngay lập tức */
    fun lockDevice() {
        if (isAdminActive()) dpm.lockNow()
    }

    /** Xóa toàn bộ dữ liệu thiết bị (factory reset) */
    fun wipeData() {
        if (isAdminActive()) dpm.wipeData(0)
    }

    /** Reset lại mật khẩu thiết bị */
    fun resetPassword(newPass: String) {
        if (isAdminActive()) dpm.resetPassword(newPass, 0)
    }

    /** Chặn người dùng gỡ app MDM (chỉ khi thiết bị là Device Owner) */
    fun blockUninstall(enable: Boolean) {
        if (isAdminActive() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                dpm.setUninstallBlocked(adminComponent, context.packageName, enable)
                Log.d("PolicyManager", "✅ Block uninstall = $enable for ${context.packageName}")
            } catch (e: Exception) {
                Log.e("PolicyManager", "❌ Failed to block uninstall", e)
            }
        } else {
            Log.w("PolicyManager", "⚠️ setUninstallBlocked yêu cầu Device Owner + API >= 24")
        }
    }

    /**
     * 🚫 Chặn user tắt/revoke permission location
     * (ACCESS_FINE_LOCATION / ACCESS_COARSE_LOCATION)
     */
    @RequiresApi(Build.VERSION_CODES.M)
    fun blockLocationPermissionChanges() {
        if (isAdminActive()) {
            try {
                // 1️⃣ Ép quyền location luôn Granted
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

                // 2️⃣ Tự động grant các permission trong tương lai
                dpm.setPermissionPolicy(
                    adminComponent,
                    DevicePolicyManager.PERMISSION_POLICY_AUTO_GRANT
                )

                Log.d("PolicyManager", "✅ Location permissions locked (cannot be revoked by user)")
            } catch (e: Exception) {
                Log.e("PolicyManager", "❌ Failed to lock location permissions", e)
            }
        }
    }

    /**
     * 📍 Ép GPS system luôn bật (MDM Only)
     * - DISALLOW_CONFIG_LOCATION: cấm user chỉnh Location trong Settings
     * - setLocationEnabled(): API 30+ mới hỗ trợ bật GPS
     */
    fun enforceLocationPolicy() {
        if (!isAdminActive()) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // Cấm user vào Settings để tắt Location
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_CONFIG_LOCATION)
                Log.d("PolicyManager", "✅ User cannot change Location settings")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // API 30+ có thể ép GPS luôn bật
                dpm.setLocationEnabled(adminComponent, true)
                Log.d("PolicyManager", "✅ GPS forced ON (API 30+)")
            }
        } catch (e: Exception) {
            Log.e("PolicyManager", "❌ Failed to enforce GPS policy", e)
        }
    }
}