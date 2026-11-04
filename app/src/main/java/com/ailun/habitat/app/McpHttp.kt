package com.ailun.habitat.app

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonParser

/**
 * MCP Streamable HTTP 传输（`POST /mcp`）的纯逻辑：协议版本协商、Origin 检查、单条/批量拆分。
 * 让 Claude Code / Claude Desktop（经 stdio 桥）/ Cursor 等标准 MCP 客户端直接接入；
 * 原来的 WebSocket `/mcp` 保留给现有脚本。
 */
object McpHttp {

    /** 支持的 MCP 协议版本（只用到 tools，这几个版本对我们没有差别）。 */
    val SUPPORTED_VERSIONS = listOf("2025-06-18", "2025-03-26", "2024-11-05")
    val LATEST: String = SUPPORTED_VERSIONS.first()

    /** 客户端要的版本我们支持就用它，否则回最新版（规范：由客户端决定是否继续）。 */
    fun negotiate(requested: String?): String = requested?.takeIf { it in SUPPORTED_VERSIONS } ?: LATEST

    /**
     * 防 DNS 重绑定：浏览器发来的请求带 Origin，只接受本机来源；命令行客户端不带 Origin，放行。
     * 令牌仍然是必须的，这里是第二道。
     */
    fun originAllowed(origin: String?): Boolean {
        if (origin.isNullOrBlank()) return true
        val host = runCatching { java.net.URI(origin).host }.getOrNull()?.lowercase() ?: return false
        return host == "localhost" || host == "127.0.0.1" || host == "[::1]" || host == "::1"
    }

    sealed class Body {
        data class Single(val message: String) : Body()
        data class Batch(val messages: List<String>) : Body()
        data class Invalid(val reason: String) : Body()
    }

    /** POST 体：一条 JSON-RPC 消息，或（旧版客户端的）批量数组。 */
    fun split(raw: String): Body {
        val el: JsonElement = runCatching { JsonParser.parseString(raw) }.getOrNull() ?: return Body.Invalid("Parse error")
        return when {
            el.isJsonObject -> Body.Single(el.toString())
            el.isJsonArray && el.asJsonArray.size() > 0 && el.asJsonArray.all { it.isJsonObject } ->
                Body.Batch(el.asJsonArray.map { it.toString() })
            else -> Body.Invalid("Invalid Request")
        }
    }

    /** 批量回复：只收有内容的（通知没有回复）；全是通知时返回 null（HTTP 202）。 */
    fun joinReplies(replies: List<String?>): String? {
        val present = replies.filterNotNull()
        if (present.isEmpty()) return null
        val arr = JsonArray()
        present.forEach { arr.add(JsonParser.parseString(it)) }
        return arr.toString()
    }
}
