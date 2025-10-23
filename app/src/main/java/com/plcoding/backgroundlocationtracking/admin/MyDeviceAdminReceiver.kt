package com.plcoding.backgroundlocationtracking.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class MyDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Log.d("MyDeviceAdminReceiver", "✅ Device Admin ENABLED")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.d("MyDeviceAdminReceiver", "⚠️ Device Admin DISABLED")
    }

    override fun onPasswordChanged(context: Context, intent: Intent) {
        Log.d("MyDeviceAdminReceiver", "🔑 Device password changed")
    }
}