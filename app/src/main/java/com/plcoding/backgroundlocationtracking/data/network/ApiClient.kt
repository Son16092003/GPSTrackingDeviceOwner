package com.plcoding.backgroundlocationtracking.data.network

import android.util.Log
import com.plcoding.backgroundlocationtracking.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object ApiClient {

    private const val TAG = "API_CLIENT"

    // Sử dụng BASE_URL từ BuildConfig
    val BASE_URL = BuildConfig.BASE_URL

    // Cấu hình interceptor chỉ bật log khi ở chế độ debug
    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = if (BuildConfig.ENABLE_HTTP_LOG) {
            HttpLoggingInterceptor.Level.BODY
        } else {
            HttpLoggingInterceptor.Level.NONE
        }
    }

    // Tạo OkHttpClient với interceptor
    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .build()
    }

    // --- Hàm gửi POST JSON đồng bộ ---
    fun postJsonSync(json: String): Pair<Boolean, String> {
        return try {
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val body = json.toRequestBody(mediaType)

            val request = Request.Builder()
                .url(BASE_URL)
                .post(body)
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Pair(true, response.body?.string() ?: "No content")
                } else {
                    Pair(false, "HTTP ${response.code}: ${response.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in postJsonSync", e)
            Pair(false, e.message ?: "Unknown error")
        }
    }
}
