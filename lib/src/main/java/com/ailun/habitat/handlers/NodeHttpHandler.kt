package com.ailun.habitat.handlers

import android.util.Log
import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode
import com.ailun.habitat.paramInt
import com.ailun.habitat.paramString
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * [ACTION_HTTP_REQUEST]：发送 HTTP 请求。
 *
 * params：
 * - `url`（必填）：请求 URL
 * - `method`（可选）：GET / POST / PUT / DELETE / PATCH，默认 GET
 *   （PATCH 以 POST + `X-HTTP-Method-Override: PATCH` 发送，HttpURLConnection 不支持原生 PATCH）
 * - `headers`（可选）：JSON 对象字符串或分号分隔的 "key:value" 对（支持 ${var}）
 * - `body`（可选）：请求体
 * - `output_var`（可选）：存储响应体的变量名
 * - `timeout`（可选）：超时时间（毫秒），默认 30000
 * - `max_response_bytes`（可选）：响应体上限，默认 5MB，超出截断并置 http_truncated=true
 */
class NodeHttpHandler : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        val params = node.params ?: return node.next
        context.putVariable("http_success", false)

        val rawUrl = params["url"]?.toString()?.trim() ?: run {
            Log.w(TAG, "No URL provided")
            context.putVariable("http_error", "No URL provided")
            return node.next
        }

        val url = context.interpolate(rawUrl)
        val method = paramString(node, context, "method")?.trim()?.uppercase()?.ifEmpty { null } ?: "GET"
        val rawHeaders = paramString(node, context, "headers")?.trim()
        val body = params["body"]?.toString()?.let { context.interpolate(it) }
        val outputVar = params["output_var"]?.toString()?.trim()?.ifEmpty { null }
        val timeout = paramInt(node, context, "timeout", 30_000).coerceAtLeast(1000)
        val maxBytes = paramInt(node, context, "max_response_bytes", HttpUtil.DEFAULT_MAX_BODY_BYTES).coerceAtLeast(1)

        try {
            Log.d(TAG, "$method ${url.substringBefore('?')}")
            val connection = URL(url).openConnection() as HttpURLConnection
            val result = HttpUtil.withConnection(connection) { conn ->
                val effective = HttpUtil.setMethod(conn, method)
                if (effective != method) Log.i(TAG, "HTTP $method 以 $effective 发送")
                conn.connectTimeout = timeout
                conn.readTimeout = timeout
                conn.instanceFollowRedirects = true

                val headers = parseHeaders(rawHeaders)
                for ((key, value) in headers) {
                    conn.setRequestProperty(key, value)
                }
                if (headers.keys.none { it.equals("Accept", true) }) {
                    conn.setRequestProperty("Accept", "*/*")
                }

                if (body != null && method in setOf("POST", "PUT", "PATCH")) {
                    if (headers.keys.none { it.equals("Content-Type", true) }) {
                        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    }
                    conn.doOutput = true
                    conn.outputStream.use { os: OutputStream ->
                        os.write(body.toByteArray(Charsets.UTF_8))
                        os.flush()
                    }
                }

                val statusCode = conn.responseCode
                val stream = if (statusCode >= 400) conn.errorStream else runCatching { conn.inputStream }.getOrElse { conn.errorStream }
                statusCode to HttpUtil.readLimited(stream, maxBytes)
            }
            val (statusCode, bodyResult) = result
            val (responseBody, truncated) = bodyResult

            context.putVariable("http_status_code", statusCode)
            context.putVariable("http_response", responseBody)
            context.putVariable("http_truncated", truncated)
            context.putVariable("http_success", statusCode in 200..399)

            if (outputVar != null) {
                context.putVariable(outputVar, responseBody)
            }

            Log.i(TAG, "HTTP $method -> $statusCode (${responseBody.length} chars${if (truncated) ", truncated" else ""})")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "HTTP request failed: ${e.message}", e)
            context.putVariable("http_success", false)
            context.putVariable("http_error", e.message ?: e.javaClass.simpleName)
            context.putVariable("http_status_code", 0)
            context.putVariable("http_response", "")
        }

        return node.next
    }

    /**
     * Parse headers from either JSON object string or semicolon-separated "key:value" pairs.
     */
    private fun parseHeaders(raw: String?): Map<String, String> {
        val result = mutableMapOf<String, String>()
        if (raw.isNullOrBlank()) return result

        // Try JSON first
        val trimmed = raw.trim()
        if (trimmed.startsWith("{")) {
            try {
                val json = JSONObject(trimmed)
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    result[key] = json.get(key).toString()
                }
                return result
            } catch (_: Exception) {
                // Fall through to semicolon parsing
            }
        }

        // Semicolon-separated "key:value" pairs
        val pairs = trimmed.split(";")
        for (pair in pairs) {
            val colonIndex = pair.indexOf(":")
            if (colonIndex > 0) {
                val key = pair.substring(0, colonIndex).trim()
                val value = pair.substring(colonIndex + 1).trim()
                if (key.isNotEmpty()) {
                    result[key] = value
                }
            }
        }

        return result
    }

    companion object {
        private const val TAG = "HabitatHttp"
    }
}
