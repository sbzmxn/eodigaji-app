package com.example.eodigaji.network

import com.example.eodigaji.BuildConfig
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

// ---- 1. GET /api/v1/keywords ----
data class KeywordsResponse(
    @SerializedName("request_id") val requestId: String,
    @SerializedName("arrival_time") val arrivalTime: String,
    @SerializedName("sunset_time") val sunsetTime: String?,
    @SerializedName("keywords") val keywords: List<KeywordStatus>,
)

data class KeywordStatus(
    @SerializedName("id") val id: String,
    @SerializedName("label") val label: String,
    @SerializedName("enabled") val enabled: Boolean,
    @SerializedName("reason") val reason: String?,
)

// ---- 2. POST /api/v1/recommend ----
data class RecommendRequest(
    @SerializedName("latitude") val latitude: Double,
    @SerializedName("longitude") val longitude: Double,
    @SerializedName("drive_minutes") val driveMinutes: Int,
    @SerializedName("keyword_ids") val keywordIds: List<String> = emptyList(),
    @SerializedName("exclude_content_ids") val excludeContentIds: List<String> = emptyList(),
    @SerializedName("retry_context") val retryContext: RetryContext? = null,
    @SerializedName("require_parking") val requireParking: Boolean = false,
    @SerializedName("exclude_paid") val excludePaid: Boolean = false,
)

data class RetryContext(
    @SerializedName("previous_content_ids") val previousContentIds: List<String>,
    @SerializedName("observed_eta_seconds") val observedEtaSeconds: Int?,
)

data class RecommendResponse(
    @SerializedName("request_id") val requestId: String,
    @SerializedName("blind_name") val blindName: String,
    @SerializedName("destination") val destination: Destination,
    @SerializedName("hint") val hint: Hint,
    @SerializedName("reveal") val reveal: Reveal,
    @SerializedName("alias_source") val aliasSource: String,
    @SerializedName("straight_km") val straightKm: Double,
    @SerializedName("estimated_drive_seconds") val estimatedDriveSeconds: Int,
)

data class Destination(
    @SerializedName("lat") val lat: Double,
    @SerializedName("lng") val lng: Double,
)

data class Hint(
    @SerializedName("parking") val parking: String?,
    @SerializedName("fee") val fee: String?,
    @SerializedName("open_now") val openNow: Boolean?,
)

data class Reveal(
    @SerializedName("content_id") val contentId: String,
    @SerializedName("real_name") val realName: String,
    @SerializedName("address") val address: String,
    @SerializedName("overview_short") val overviewShort: String?,
    @SerializedName("image_url") val imageUrl: String?,
    @SerializedName("theme") val theme: String?,
    @SerializedName("matched_keywords") val matchedKeywords: List<String>,
)

// ---- 3. POST /api/v1/eta-check ----
data class EtaCheckRequest(
    @SerializedName("latitude") val latitude: Double,
    @SerializedName("longitude") val longitude: Double,
    @SerializedName("drive_minutes") val driveMinutes: Int,
    @SerializedName("keyword_ids") val keywordIds: List<String> = emptyList(),
    @SerializedName("exclude_content_ids") val excludeContentIds: List<String> = emptyList(),
    @SerializedName("require_parking") val requireParking: Boolean = false,
    @SerializedName("exclude_paid") val excludePaid: Boolean = false,
    @SerializedName("content_id") val contentId: String,
    @SerializedName("observed_eta_seconds") val observedEtaSeconds: Int,
    @SerializedName("previous_content_ids") val previousContentIds: List<String>,
)

data class EtaCheckResponse(
    @SerializedName("request_id") val requestId: String,
    @SerializedName("decision") val decision: String,
    @SerializedName("attempt") val attempt: Int,
    @SerializedName("max_attempts") val maxAttempts: Int,
    @SerializedName("reason") val reason: String,
    @SerializedName("route_content_id") val routeContentId: String?,
    @SerializedName("recommendation") val recommendation: RecommendResponse?,
)

fun RecommendRequest.toEtaCheck(contentId: String, etaSec: Int, previous: List<String>) = EtaCheckRequest(
    latitude = latitude,
    longitude = longitude,
    driveMinutes = driveMinutes,
    keywordIds = keywordIds,
    excludeContentIds = excludeContentIds,
    requireParking = requireParking,
    excludePaid = excludePaid,
    contentId = contentId,
    observedEtaSeconds = etaSec,
    previousContentIds = previous,
)

// ---- 4. 에러 응답 ----
data class ErrorResponse(@SerializedName("error") val error: ErrorBody)

data class ErrorBody(
    @SerializedName("code") val code: String,
    @SerializedName("message") val message: String,
    @SerializedName("request_id") val requestId: String,
)

fun <T> Response<T>.apiError(gson: Gson): ErrorBody? =
    errorBody()?.charStream()?.use {
        runCatching { gson.fromJson(it, ErrorResponse::class.java).error }.getOrNull()
    }

interface EodigajiApi {
    @GET("api/v1/keywords")
    suspend fun keywords(
        @Query("lat") lat: Double,
        @Query("lng") lng: Double,
        @Query("drive_minutes") driveMinutes: Int,
        @Query("require_parking") requireParking: Boolean = false,
        @Query("exclude_paid") excludePaid: Boolean = false,
    ): Response<KeywordsResponse>

    @POST("api/v1/recommend")
    suspend fun recommend(@Body body: RecommendRequest): Response<RecommendResponse>

    @POST("api/v1/eta-check")
    suspend fun etaCheck(@Body body: EtaCheckRequest): Response<EtaCheckResponse>
}

object EodigajiApiClient {
    val baseUrl: String = BuildConfig.EODIGAJI_API_BASE_URL

    val gson: Gson = Gson()

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val builder = chain.request().newBuilder()
            val apiKey = ApiKeyProvider.getApiKey()
            if (apiKey.isNotBlank()) {
                builder.addHeader("X-API-KEY", apiKey)
            }
            chain.proceed(builder.build())
        }
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    val api: EodigajiApi by lazy {
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(EodigajiApi::class.java)
    }
}
