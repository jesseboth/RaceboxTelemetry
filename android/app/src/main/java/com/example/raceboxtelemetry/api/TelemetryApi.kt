package com.example.raceboxtelemetry.api

import com.example.raceboxtelemetry.model.TelemetryData
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
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

data class ServerConfig(
    val video_delay_ms: Int,
    val send_frequency_hz: Int,
    val send_interval_ms: Int,
    val update_interval_ms: Int? = null,
    val session: Any? = null
)

interface TelemetryApiService {
    @POST("telemetry")
    suspend fun sendTelemetry(@Body data: TelemetryData): Response<Unit>

    @GET("config")
    suspend fun getConfig(): Response<ServerConfig>

    @POST("config")
    suspend fun updateConfig(@Body config: Map<String, Int>): Response<ConfigResponse>
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
            val logging = HttpLoggingInterceptor()
            logging.level = HttpLoggingInterceptor.Level.BODY

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

    suspend fun getConfig(): Result<ServerConfig> {
        return try {
            val response = getService().getConfig()
            if (response.isSuccessful) {
                val config = response.body()
                if (config != null) {
                    Result.success(config)
                } else {
                    Result.failure(Exception("Empty config response"))
                }
            } else {
                Result.failure(Exception("API error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateConfig(videoDelayMs: Int? = null, sendFrequencyHz: Int? = null): Result<Unit> {
        return try {
            val configMap = mutableMapOf<String, Int>()
            if (videoDelayMs != null) {
                configMap["video_delay_ms"] = videoDelayMs
            }
            if (sendFrequencyHz != null) {
                configMap["send_frequency_hz"] = sendFrequencyHz
            }

            val response = getService().updateConfig(configMap)
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
