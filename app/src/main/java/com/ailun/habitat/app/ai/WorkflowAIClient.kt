package com.ailun.habitat.app.ai

import android.content.Context
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
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    /** 调 DeepSeek 生成工作流；返回 LLM 原始文本。 */
    suspend fun chat(apiKey: String, systemPrompt: String, userPrompt: String, baseUrl: String = DEFAULT_URL): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val body = JsonObject().apply {
                    addProperty("model", DEFAULT_MODEL)
                    addProperty("temperature", 0.3)
                    add("messages", com.google.gson.JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("role", "system")
                            addProperty("content", systemPrompt)
                        })
                        add(JsonObject().apply {
                            addProperty("role", "user")
                            addProperty("content", userPrompt)
                        })
                    })
                }
                val request = Request.Builder()
                    .url(baseUrl)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .post(body.toString().toRequestBody(jsonType))
                    .build()
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return@withContext Result.failure(
                            Exception("API ${resp.code}: ${resp.body?.string()?.take(200)}"),
                        )
                    }
                    val text = resp.body?.string() ?: return@withContext Result.failure(Exception("空响应"))
                    val obj = gson.fromJson(text, JsonObject::class.java)
                    val content = obj.getAsJsonArray("choices")
                        ?.firstOrNull()?.asJsonObject
                        ?.getAsJsonObject("message")
                        ?.get("content")?.asString
                        ?: return@withContext Result.failure(Exception("响应缺少 choices[0].message.content"))
                    Result.success(content)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // key 持久化（SharedPreferences，设置页/界面可改）——不硬编码，避免泄露
    private const val PREFS = "workflow_ai_prefs"
    private const val KEY_API = "deepseek_api_key"
    private const val KEY_BASE = "base_url"

    /** 读取配置的 API key；未配置则返回 null（调用方应引导用户配置，不 fallback 到硬编码）。 */
    fun getApiKey(context: Context): String? =
        context.getSharedPreferences(PREFS, 0).getString(KEY_API, null)?.takeIf { it.isNotBlank() }

    fun setApiKey(context: Context, key: String) {
        context.getSharedPreferences(PREFS, 0).edit().putString(KEY_API, key.trim()).apply()
    }

    fun getBaseUrl(context: Context): String =
        context.getSharedPreferences(PREFS, 0).getString(KEY_BASE, null) ?: DEFAULT_URL

    fun setBaseUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS, 0).edit().putString(KEY_BASE, url).apply()
    }
}
