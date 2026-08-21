package com.ailun.habitat.app.network

import android.util.Log
import com.ailun.habitat.app.auth.ApiErrorResponse
import com.ailun.habitat.app.auth.AuthResponse
import com.ailun.habitat.app.auth.LoginRequest
import com.ailun.habitat.app.auth.RegisterRequest
import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

object HabitatApiClient {
    private const val TAG = "HabitatApiClient"

    private val gson = Gson()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private var client: OkHttpClient? = null
    private var baseUrl: String = ""

    fun init(baseUrl: String) {
        if (this.baseUrl == baseUrl && client != null) return
        this.baseUrl = baseUrl.trimEnd('/')
        client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(AuthInterceptor)
            .build()
        Log.d(TAG, "Initialized with baseUrl=$baseUrl")
    }

    fun getBaseUrl(): String = baseUrl

    fun <T> post(path: String, body: Any, responseClass: Class<T>): Result<T> {
        val client = client ?: return Result.failure(IllegalStateException("Client not initialized"))
        val json = gson.toJson(body)
        Log.d(TAG, "POST $baseUrl$path body=$json")
        val requestBody = json.toRequestBody(JSON)
        val request = Request.Builder()
            .url("$baseUrl$path")
            .post(requestBody)
            .build()
        return executeRequest(client, request, responseClass)
    }

    fun <T> get(path: String, responseClass: Class<T>): Result<T> {
        val client = client ?: return Result.failure(IllegalStateException("Client not initialized"))
        val request = Request.Builder()
            .url("$baseUrl$path")
            .get()
            .build()
        return executeRequest(client, request, responseClass)
    }

    fun login(username: String, password: String): Result<AuthResponse> {
        return post("/api/v1/auth/login", LoginRequest(username, password), AuthResponse::class.java)
    }

    fun register(username: String, email: String, password: String): Result<AuthResponse> {
        return post("/api/v1/auth/register", RegisterRequest(username, email, password), AuthResponse::class.java)
    }

    private fun <T> executeRequest(client: OkHttpClient, request: Request, responseClass: Class<T>): Result<T> {
        return try {
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            Log.d(TAG, "Response ${response.code}: ${body.take(200)}")
            if (response.isSuccessful) {
                Result.success(gson.fromJson(body, responseClass))
            } else {
                val error = try {
                    gson.fromJson(body, ApiErrorResponse::class.java)
                } catch (_: Exception) {
                    ApiErrorResponse(response.code, "Error", body)
                }
                Result.failure(ApiException(response.code, error.message, error))
            }
        } catch (e: IOException) {
            Log.e(TAG, "Request failed: ${request.url}", e)
            Result.failure(e)
        }
    }
}

class ApiException(
    val statusCode: Int,
    override val message: String,
    val errorResponse: ApiErrorResponse? = null,
) : Exception(message)
