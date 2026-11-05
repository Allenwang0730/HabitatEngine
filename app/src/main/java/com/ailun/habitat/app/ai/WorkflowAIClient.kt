package com.ailun.habitat.app.ai

import android.content.Context
import com.ailun.habitat.app.auth.SecretStore
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * DeepSeek / OpenAI 兼容 Chat Completions 客户端（App 内直接调用，不依赖电脑）。
 */
object WorkflowAIClient {

    private const val TAG = "WorkflowAI"
    private const val DEFAULT_URL = "https://api.deepseek.com/chat/completions"
    private const val DEFAULT_MODEL = "deepseek-chat"

    private val gson = Gson()
    // 规划/复核可以用带思考的模型（deepseek-flash 实测单次约 11 秒）：读超时放宽到 120 秒
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    /** 用户消息的 content：没图是纯字符串（和以前完全一样）；有图是 [文字, image_url] 数组。 */
    internal fun userContent(text: String, imageJpegBase64: String?): com.google.gson.JsonElement {
        if (imageJpegBase64.isNullOrEmpty()) return com.google.gson.JsonPrimitive(text)
        return com.google.gson.JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", "text")
                addProperty("text", text)
            })
            add(JsonObject().apply {
                addProperty("type", "image_url")
                add("image_url", JsonObject().apply { addProperty("url", "data:image/jpeg;base64,$imageJpegBase64") })
            })
        }
    }

    /**
     * 调 DeepSeek（或 OpenAI 兼容网关）；返回 LLM 原始文本。[model] 为空用默认模型（deepseek-chat，不思考、快）。
     * [imageJpegBase64] 非空时把截图按 OpenAI 的 image_url（data URL）随用户消息发出——只有多模态模型能用，DeepSeek 原生接口不收图。
     */
    suspend fun chat(
        apiKey: String, systemPrompt: String, userPrompt: String, baseUrl: String = DEFAULT_URL, model: String? = null,
        imageJpegBase64: String? = null,
    ): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val body = JsonObject().apply {
                    addProperty("model", model?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL)
                    addProperty("temperature", 0.3)
                    add("messages", com.google.gson.JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("role", "system")
                            addProperty("content", systemPrompt)
                        })
                        add(JsonObject().apply {
                            addProperty("role", "user")
                            add("content", userContent(userPrompt, imageJpegBase64))
                        })
                    })
                }
                val request = Request.Builder()
                    .url(baseUrl)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .post(body.toString().toRequestBody(jsonType))
                    .build()
                repeat(MAX_RETRIES) { attempt ->
                    val outcome = callOnce(request)
                    if (!shouldRetry(outcome)) return@withContext outcome
                    kotlinx.coroutines.delay(RETRY_DELAY_MS * (attempt + 1))
                }
                callOnce(request)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // 连不上 / 网关忙（429、503）时再试两次：连接失败时请求还没发出去，重试不会让模型多算一遍。
    // 502/504 不在这里重试：可能是网关那头读超时，再发一遍要再等两分钟（评测转发服务自己会重试）。
    // 评测里实测：模拟器录音/录像时 10.0.2.2 偶尔连不上，一次失败就让整步卡住、重新规划（20 个任务里 3 个）
    private const val MAX_RETRIES = 2
    private const val RETRY_DELAY_MS = 1500L

    private class BusyException(message: String) : Exception(message)

    private fun callOnce(request: Request): Result<String> = try {
        client.newCall(request).execute().use { resp -> parse(resp.code, resp.isSuccessful, resp.body?.string()) }
    } catch (e: java.io.IOException) {
        Result.failure(e)
    }

    private fun shouldRetry(outcome: Result<String>): Boolean = when (val e = outcome.exceptionOrNull()) {
        null -> false
        is BusyException -> true
        is java.io.IOException -> retryable(e)
        else -> false
    }

    /** 连接阶段的失败（还没发出请求）才重试；读超时说明对方已经在算了，不重发。 */
    internal fun retryable(e: java.io.IOException): Boolean =
        e is java.net.ConnectException || e is java.net.NoRouteToHostException || e is java.net.UnknownHostException ||
            (e is java.net.SocketTimeoutException && e.message.orEmpty().contains("connect", ignoreCase = true))

    private fun parse(code: Int, ok: Boolean, text: String?): Result<String> {
        if (!ok) {
            val msg = "API $code: ${text?.take(200)}"
            return Result.failure(if (code == 429 || code == 503) BusyException(msg) else Exception(msg))
        }
        text ?: return Result.failure(Exception("空响应"))
        val obj = gson.fromJson(text, JsonObject::class.java)
        val content = obj.getAsJsonArray("choices")
            ?.firstOrNull()?.asJsonObject
            ?.getAsJsonObject("message")
            ?.get("content")?.asString
            ?: return Result.failure(Exception("响应缺少 choices[0].message.content"))
        return Result.success(content)
    }

    // key 加密存储（SecretStore，自动从旧明文 prefs 迁移）——不硬编码，避免泄露
    private const val PREFS = "workflow_ai_prefs"
    private const val KEY_API = "deepseek_api_key"
    private const val KEY_BASE = "base_url"

    /** 读取配置的 API key；未配置则返回 null（调用方应引导用户配置，不 fallback 到硬编码）。 */
    fun getApiKey(context: Context): String? =
        SecretStore.get(context, SecretStore.DEEPSEEK_API_KEY, legacyPrefs = PREFS, legacyKey = KEY_API)

    fun setApiKey(context: Context, key: String) {
        SecretStore.put(context, SecretStore.DEEPSEEK_API_KEY, key)
    }

    fun getBaseUrl(context: Context): String =
        context.getSharedPreferences(PREFS, 0).getString(KEY_BASE, null) ?: DEFAULT_URL

    fun setBaseUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS, 0).edit().putString(KEY_BASE, url).apply()
    }
}
