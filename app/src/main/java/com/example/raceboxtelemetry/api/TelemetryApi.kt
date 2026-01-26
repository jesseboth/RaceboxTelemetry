package com.example.raceboxtelemetry.api

import com.example.raceboxtelemetry.model.TelemetryData
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

data class ConfigUpdate(
    val video_delay_ms: Int
)

data class ConfigResponse(
    val status: String,
    val video_delay_ms: Int? = null,
    val message: String? = null
)

interface TelemetryApiService {
    @POST("telemetry")
    suspend fun sendTelemetry(@Body data: TelemetryData): Response<Unit>

    @POST("config")
    suspend fun updateConfig(@Body config: ConfigUpdate): Response<ConfigResponse>
}

object TelemetryApi {
    private const val DEFAULT_BASE_URL = "http://192.168.1.100:5000/"  // Default, can be changed

    private var baseUrl: String = DEFAULT_BASE_URL
    private var apiService: TelemetryApiService? = null

    fun setBaseUrl(url: String) {
        baseUrl = if (!url.endsWith("/")) "$url/" else url
        apiService = null  // Force recreation with new URL
    }

    fun getService(): TelemetryApiService {
        if (apiService == null) {
            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            }

            val client = OkHttpClient.Builder()
                .addInterceptor(logging)
                .connectTimeout(3, TimeUnit.SECONDS)  // Reduced for faster failure detection
                .writeTimeout(3, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)  // Auto-retry on connection failure
                .build()

            val retrofit = Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()

            apiService = retrofit.create(TelemetryApiService::class.java)
        }
        return apiService!!
    }

    suspend fun sendTelemetry(data: TelemetryData): Result<Unit> {
        return try {
            val response = getService().sendTelemetry(data)
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("API error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateConfig(videoDelayMs: Int): Result<Unit> {
        return try {
            val response = getService().updateConfig(ConfigUpdate(videoDelayMs))
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                val errorMsg = response.body()?.message ?: "API error: ${response.code()}"
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
