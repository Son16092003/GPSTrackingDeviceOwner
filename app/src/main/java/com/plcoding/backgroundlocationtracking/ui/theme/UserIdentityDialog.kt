package com.plcoding.backgroundlocationtracking.ui.theme

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.plcoding.backgroundlocationtracking.R

class UserIdentityDialog(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("setup_prefs", Context.MODE_PRIVATE)

    // Lấy DeviceId tự động
    val deviceId: String
        get() = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "UnknownDevice"

    /**
     * Hiển thị dialog nhập Title + UserName.
     * Callback trả về Triple(DeviceId, Title, UserName)
     */
    fun show(onConfirmed: ((deviceId: String, title: String, userName: String) -> Unit)? = null) {
        val savedTitle = prefs.getString("TITLE", null)
        val savedUser = prefs.getString("USERNAME", null)

        // Nếu đã lưu rồi thì không hiển thị dialog, trả luôn callback
        if (!savedTitle.isNullOrEmpty() && !savedUser.isNullOrEmpty()) {
            onConfirmed?.invoke(deviceId, savedTitle, savedUser)
            return
        }

        val inflater = LayoutInflater.from(context)
        val dialogView = inflater.inflate(R.layout.dialog_user_identity, null)
        val titleInput = dialogView.findViewById<EditText>(R.id.etTitle)
        val userInput = dialogView.findViewById<EditText>(R.id.etUserName)

        val alertDialog = AlertDialog.Builder(context)
            .setTitle("Thiết lập định danh")
            .setView(dialogView)
            .setCancelable(false)
            .setPositiveButton("Xác nhận", null) // sẽ set listener riêng
            .create()

        alertDialog.setOnShowListener {
            val button = alertDialog.getButton(AlertDialog.BUTTON_POSITIVE)
            button.setOnClickListener {
                val title = titleInput.text.toString().trim()
                val user = userInput.text.toString().trim()

                if (title.isNotEmpty() && user.isNotEmpty()) {
                    prefs.edit()
                        .putString("TITLE", title)
                        .putString("USERNAME", user)
                        .putBoolean("setup_done", true)
                        .apply()
                    onConfirmed?.invoke(deviceId, title, user)
                    alertDialog.dismiss()
                } else {
                    Toast.makeText(context, "Vui lòng nhập đầy đủ thông tin", Toast.LENGTH_SHORT)
                        .show()
                }
            }
        }

        alertDialog.show()
    }

    /**
     * Lấy thông tin đã lưu: DeviceId + Title + UserName
     */
    fun getSavedIdentity(): Triple<String, String?, String?> {
        val title = prefs.getString("TITLE", null)
        val user = prefs.getString("USERNAME", null)
        return Triple(deviceId, title, user)
    }

    fun markSetupCompleted() {
        prefs.edit().putBoolean("setup_done", true).apply()
    }

    fun isSetupCompleted(): Boolean {
        return prefs.getBoolean("setup_done", false)
    }
}
