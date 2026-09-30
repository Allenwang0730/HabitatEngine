package com.ailun.habitat.app.network

import android.util.Log
import com.ailun.habitat.BuildConfig
import com.ailun.habitat.app.auth.ApiErrorResponse
import com.ailun.habitat.app.auth.AuthResponse
import com.ailun.habitat.app.auth.LoginRequest
import com.ailun.habitat.app.auth.RegisterRequest
import com.google.gson.Gson
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object HabitatApiClient {
    private const val TAG = "HabitatApiClient"

    private val gson = Gson()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** 允许明文 HTTP 发送凭据的本机/模拟器地址。 */
    private val LOCAL_HOSTS = setOf("10.0.2.2", "localhost", "127.0.0.1")

    @Volatile private var client: OkHttpClient? = null
    @Volatile private var baseUrl: String = ""

    fun init(baseUrl: String) {
        val normalized = baseUrl.trimEnd('/')
        if (this.baseUrl == normalized && client != null) return
        this.baseUrl = normalized
        client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(AuthInterceptor)
            .build()
        Log.d(TAG, "Initialized with baseUrl=$normalized")
    }

    fun getBaseUrl(): String = baseUrl

    /** 是否允许在该地址上发送凭据/令牌：https，或本机/模拟器地址，或 debug 构建。 */
    internal fun isCredentialTransportAllowed(url: HttpUrl): Boolean =
        url.isHttps || url.host in LOCAL_HOSTS || BuildConfig.DEBUG

    fun <T> post(path: String, body: Any, responseClass: Class<T>): Result<T> {
        val client = client ?: return Result.failure(IllegalStateException("Client not initialized"))
        val url = "$baseUrl$path".toHttpUrlOrNull()
            ?: return Result.failure(IllegalArgumentException("服务器地址无效：$baseUrl"))
        val requestBody = gson.toJson(body).toRequestBody(JSON)
        Log.d(TAG, "POST ${url.encodedPath}")
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()
        return executeRequest(client, request, responseClass)
    }

    fun <T> get(path: String, responseClass: Class<T>): Result<T> {
        val client = client ?: return Result.failure(IllegalStateException("Client not initialized"))
        val url = "$baseUrl$path".toHttpUrlOrNull()
            ?: return Result.failure(IllegalArgumentException("服务器地址无效：$baseUrl"))
        val request = Request.Builder()
            .url(url)
            .get()
            .build()
        return executeRequest(client, request, responseClass)
    }

    fun login(username: String, password: String): Result<AuthResponse> {
        insecureCredentialError()?.let { return Result.failure(it) }
        return post("/api/v1/auth/login", LoginRequest(username, password), AuthResponse::class.java)
    }

    fun register(username: String, email: String, password: String): Result<AuthResponse> {
        insecureCredentialError()?.let { return Result.failure(it) }
        return post("/api/v1/auth/register", RegisterRequest(username, email, password), AuthResponse::class.java)
    }

    /** 凭据只走 https（本机/模拟器地址与 debug 构建除外）。 */
    private fun insecureCredentialError(): Exception? {
        val url = baseUrl.toHttpUrlOrNull()
            ?: return IllegalArgumentException("服务器地址无效：$baseUrl")
        if (isCredentialTransportAllowed(url)) return null
        return SecurityException("服务器地址不是 https，已拒绝发送账号密码：请改用 https:// 地址")
    }

    /** Gson 反射绕过 Kotlin 非空约束：缺字段时这些值实际为 null。 */
    private fun isValidAuth(auth: AuthResponse): Boolean {
        val token: String? = auth.token
        val userId: String? = auth.userId
        val username: String? = auth.username
        return !token.isNullOrBlank() && userId != null && username != null
    }

    private fun <T> executeRequest(client: OkHttpClient, request: Request, responseClass: Class<T>): Result<T> {
        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                // 不打印响应体：可能含 token
                Log.d(TAG, "Response ${response.code} ${request.url.encodedPath} (${body.length} chars)")
                if (response.isSuccessful) {
                    val parsed = runCatching { gson.fromJson(body, responseClass) }.getOrNull()
                        ?: return Result.failure(ApiException(response.code, "服务器响应格式异常（不是预期的 JSON）"))
                    if (parsed is AuthResponse && !isValidAuth(parsed)) {
                        return Result.failure(ApiException(response.code, "服务器响应缺少登录令牌"))
                    }
                    Result.success(parsed)
                } else {
                    val error = runCatching { gson.fromJson(body, ApiErrorResponse::class.java) }.getOrNull()
                    // Gson 反射可能把非空字段置为 null，按可空处理
                    val rawMessage: String? = error?.message
                    val message = rawMessage?.takeIf { it.isNotBlank() } ?: "HTTP ${response.code}"
                    Result.failure(ApiException(response.code, message, error))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Request failed: ${request.url.encodedPath}: ${e.javaClass.simpleName}")
            Result.failure(e)
        }
    }
}

class ApiException(
    val statusCode: Int,
    override val message: String,
    val errorResponse: ApiErrorResponse? = null,
) : Exception(message)
