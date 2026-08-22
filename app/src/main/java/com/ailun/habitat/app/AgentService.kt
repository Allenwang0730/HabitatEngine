package com.ailun.habitat.app

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.ailun.habitat.NodeHandlerFactory
import com.ailun.habitat.NodeModule
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode
import com.ailun.habitat.app.bridge.AppAccessibilityProvider
import com.ailun.habitat.app.bridge.ShizukuShellExecutor
import com.ailun.habitat.app.bridge.applyAppHandlers
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Agent 服务：把 Habitat 引擎的节点暴露为工具（AI 控制手机的通道）。
 *
 * 端点：
 * - GET /health  → "habitat-agent-ok"
 * - GET /tools   → 可用节点清单（JSON，按模块分组）
 * - WS  /ws      → 自定义工具调用协议（JSON：tool/params → 执行节点 → 变量快照）
 * - WS  /mcp     → MCP（Model Context Protocol）JSON-RPC 2.0：initialize / tools/list / tools/call
 *
 * 安全：当前无鉴权（局域网演示用）。生产必须加 token。
 */
class AgentService : Service() {

    companion object {
        private const val TAG = "HabitatAgent"
        private const val PREFS = "agent_prefs"
        const val DEFAULT_PORT = 8931
        private const val TOOL_TIMEOUT_MS = 60_000L

        /** 服务运行标志（同进程内由 startAgentServer/onDestroy 维护，控制页直接读取）。 */
        @Volatile
        var serverRunning: Boolean = false

        /** 当前服务端口（可配置，存 SharedPreferences）。 */
        fun getPort(context: android.content.Context): Int =
            context.getSharedPreferences(PREFS, 0).getInt("port", DEFAULT_PORT)

        fun setPort(context: android.content.Context, port: Int) {
            context.getSharedPreferences(PREFS, 0).edit().putInt("port", port.coerceIn(1024, 65535)).apply()
        }

        /** 模块 → 节点类型（用于 /tools 分组展示）。 */
        val ALL_NODE_TYPES_BY_MODULE: Map<NodeModule, Set<String>> = run {
            val map = mutableMapOf<NodeModule, MutableSet<String>>()
            com.ailun.habitat.ALL_NODE_MODULES.forEach { (module, entries) ->
                map.getOrPut(module) { mutableSetOf() }.addAll(entries.map { it.first })
            }
            map
        }
    }

    private val gson = Gson()
    private var server: io.ktor.server.engine.EmbeddedServer<*, *>? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAgentServer()
    }

    override fun onDestroy() {
        serverRunning = false
        server?.stop(100, 500)
        server = null
        super.onDestroy()
    }

    private fun startAgentServer() {
        val factory = NodeHandlerFactory(AppAccessibilityProvider, ShizukuShellExecutor(applicationContext))
            .apply { applyAppHandlers(applicationContext) }

        val server = embeddedServer(Netty, port = getPort(this@AgentService), host = "0.0.0.0") {
            install(WebSockets)
            routing {
                get("/health") {
                    call.respondText("habitat-agent-ok")
                }
                get("/tools") {
                    call.respondText(buildToolsJson(factory), ContentType.Application.Json)
                }
                webSocket("/ws") {
                    Log.i(TAG, "WS connected")
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            val text = frame.readText()
                            val reply = runCatching { executeTool(factory, text) }
                                .getOrElse { buildReplyJson("0", ok = false, error = it.message) }
                            send(Frame.Text(reply))
                        }
                    }
                }
                // MCP（Model Context Protocol）端点：JSON-RPC 2.0 over WebSocket
                webSocket("/mcp") {
                    Log.i(TAG, "MCP client connected")
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            val reply = runCatching { handleMcpMessage(factory, frame.readText()) }.getOrNull()
                            if (reply != null) send(Frame.Text(reply))
                        }
                    }
                }
            }
        }
        this.server = server
        server.start(wait = false)
        serverRunning = true
        Log.i(TAG, "Agent server started on :${getPort(this@AgentService)}")
    }

    /** 执行一个工具调用（节点），返回 JSON 响应。 */
    private suspend fun executeTool(factory: NodeHandlerFactory, requestJson: String): String {
        val req = runCatching { gson.fromJson(requestJson, JsonObject::class.java) }.getOrNull()
            ?: return buildReplyJson("0", ok = false, error = "请求不是合法 JSON")

        val id = req.get("id")?.asString ?: "0"
        val tool = req.get("tool")?.asString ?: return buildReplyJson(id, ok = false, error = "缺少 tool 字段")

        if (tool == "ping") return buildReplyJson(id, ok = true, outputs = mapOf("pong" to true))
        if (tool == "screen") return buildReplyJson(id, ok = true, outputs = screenSnapshot())

        val params = req.get("params")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        return executeNodeReply(factory, id, tool, params)
    }

    /** 执行节点并组装自定义协议响应。 */
    private suspend fun executeNodeReply(factory: NodeHandlerFactory, id: String, tool: String, params: JsonObject): String {
        val handler = factory.get(tool)
            ?: return buildReplyJson(id, ok = false, error = "未知工具: $tool（/tools 查看可用列表）")

        val paramMap = mutableMapOf<String, Any>()
        params.entrySet().forEach { (k, v) ->
            paramMap[k] = gson.fromJson(v, Any::class.java)
        }
        val node = WorkflowNode().apply {
            this.id = "agent_tool"
            this.type = tool
            this.params = paramMap
        }
        val ctx = WorkflowContext(applicationContext)
        val result = withContext(Dispatchers.Default) {
            withTimeoutOrNull(TOOL_TIMEOUT_MS) { runCatching { handler.handle(node, ctx) } }
        }
        val error = when {
            result == null -> "工具执行超时（${TOOL_TIMEOUT_MS}ms）"
            result.isFailure -> result.exceptionOrNull()?.message ?: "工具执行失败"
            else -> null
        }
        return if (error != null) {
            buildReplyJson(id, ok = false, error = error)
        } else {
            buildReplyJson(id, ok = true, outputs = ctx.variables.toMap())
        }
    }

    // ── MCP（JSON-RPC 2.0 over WebSocket）──

    /** 处理一条 MCP 消息；通知类返回 null 不回包。 */
    private suspend fun handleMcpMessage(factory: NodeHandlerFactory, requestJson: String): String? {
        val req = runCatching { gson.fromJson(requestJson, JsonObject::class.java) }.getOrNull()
            ?: return mcpError(null, -32700, "Parse error")
        val id = if (req.has("id")) req.get("id") else null
        val method = req.get("method")?.asString ?: return mcpError(id, -32600, "Invalid Request")
        val params = req.get("params")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()

        return when (method) {
            "initialize" -> mcpResult(id, JsonObject().apply {
                addProperty("protocolVersion", "2025-06-18")
                add("capabilities", JsonObject().apply { add("tools", JsonObject()) })
                add("serverInfo", JsonObject().apply {
                    addProperty("name", "habitat-agent")
                    addProperty("version", "1.5.0")
                })
            })

            "notifications/initialized", "notifications/cancelled" -> null

            "ping" -> mcpResult(id, JsonObject())

            "tools/list" -> mcpResult(id, JsonObject().apply { add("tools", buildMcpTools(factory)) })

            "tools/call" -> {
                val name = params.get("name")?.asString
                    ?: return mcpError(id, -32602, "Missing 'name'")
                val args = params.get("arguments")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
                val (ok, text) = executeNodeAsText(factory, name, args)
                mcpResult(id, JsonObject().apply {
                    add("content", JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("type", "text")
                            addProperty("text", text)
                        })
                    })
                    addProperty("isError", !ok)
                })
            }

            else -> mcpError(id, -32601, "Method not found: $method")
        }
    }

    /** 执行工具/节点并返回 (是否成功, 文本结果)。 */
    private suspend fun executeNodeAsText(factory: NodeHandlerFactory, tool: String, args: JsonObject): Pair<Boolean, String> {
        if (tool == "ping") return true to """{"pong":true}"""
        if (tool == "screen") return true to gson.toJson(screenSnapshot())

        val paramMap = mutableMapOf<String, Any>()
        args.entrySet().forEach { (k, v) -> paramMap[k] = gson.fromJson(v, Any::class.java) }

        val handler = factory.get(tool)
            ?: return false to "未知工具: $tool"
        val node = WorkflowNode().apply {
            this.id = "mcp_tool"
            this.type = tool
            this.params = paramMap
        }
        val ctx = WorkflowContext(applicationContext)
        val result = withContext(Dispatchers.Default) {
            withTimeoutOrNull(TOOL_TIMEOUT_MS) { runCatching { handler.handle(node, ctx) } }
        }
        return when {
            result == null -> false to "工具执行超时（${TOOL_TIMEOUT_MS}ms）"
            result.isFailure -> false to (result.exceptionOrNull()?.message ?: "工具执行失败")
            else -> true to gson.toJson(ctx.variables.toMap())
        }
    }

    /** 生成 MCP tools 清单（80 节点 + 内置 screen/ping）。 */
    private fun buildMcpTools(factory: NodeHandlerFactory): JsonArray {
        val arr = JsonArray()
        arr.add(mcpToolSchema("screen", "获取当前屏幕无障碍树文本 + 前台应用包名（AI 的眼睛）"))
        arr.add(mcpToolSchema("ping", "连通性测试"))
        factory.availableTypes().sorted().forEach { type ->
            val module = ALL_NODE_TYPES_BY_MODULE.entries.firstOrNull { type in it.value }?.key?.name ?: "?"
            arr.add(
                mcpToolSchema(
                    type,
                    "执行 Habitat 节点（模块 $module）。arguments 为该节点的 params 键值对，值支持 \${var} 插值；" +
                        "执行结果（变量快照）以 JSON 返回。失败时 isError=true。",
                ),
            )
        }
        return arr
    }

    private fun mcpToolSchema(name: String, description: String): JsonObject = JsonObject().apply {
        addProperty("name", name)
        addProperty("description", description)
        add("inputSchema", JsonObject().apply {
            addProperty("type", "object")
            add("properties", JsonObject())
            addProperty("additionalProperties", true)
        })
    }

    private fun mcpResult(id: JsonElement?, result: JsonObject): String = JsonObject().apply {
        addProperty("jsonrpc", "2.0")
        if (id != null) add("id", id)
        add("result", result)
    }.toString()

    private fun mcpError(id: JsonElement?, code: Int, message: String): String = JsonObject().apply {
        addProperty("jsonrpc", "2.0")
        if (id != null) add("id", id)
        add("error", JsonObject().apply {
            addProperty("code", code)
            addProperty("message", message)
        })
    }.toString()

    /** 屏幕快照（AI 的"眼睛"）：返回屏幕状态 + 所有窗口文本（无障碍树，替代截图省 token）。 */
    private fun screenSnapshot(): Map<String, Any> {
        val service = AppAccessibilityProvider.getService()
        val pm = getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
        val km = getSystemService(android.content.Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        val screenOn = pm?.isInteractive ?: false
        val locked = km?.isKeyguardLocked ?: false

        // 遍历所有窗口（对齐 NodeReadScreenHandler）：锁屏/悬浮层盖住时也能读到应用窗口
        val windows = mutableListOf<Map<String, Any>>()
        if (service != null && screenOn) {
            try {
                service.windows.forEach { w ->
                    if (w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION ||
                        w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM
                    ) {
                        val root = w.root ?: return@forEach
                        try {
                            val pkg = root.packageName?.toString() ?: ""
                            val texts = collectText(root)
                            if (texts.isNotEmpty() || pkg.isNotEmpty()) {
                                windows.add(mapOf("package" to pkg, "text" to texts.joinToString("\n")))
                            }
                        } finally {
                            runCatching { root.recycle() }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        // 主窗口文本：优先非系统悬浮层
        val main = windows.firstOrNull {
            it["package"] != "com.android.systemui" && it["package"] != "com.vivo.systemuiplugin" &&
                it["package"] != "com.vivo.doubleinstance"
        } ?: windows.firstOrNull()

        return mapOf(
            "screen_on" to screenOn,
            "screen_locked" to locked,
            "screen_text" to (main?.get("text") ?: ""),
            "windows" to windows,
            "foreground_package" to (AppAccessibilityProvider.foregroundPackage ?: "unknown"),
        )
    }

    private fun collectText(node: android.view.accessibility.AccessibilityNodeInfo, out: MutableList<String> = mutableListOf()): List<String> {
        if (!node.isVisibleToUser) return out
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                collectText(child, out)
            } finally {
                runCatching { child.recycle() }
            }
        }
        return out
    }

    private fun buildToolsJson(factory: NodeHandlerFactory): String {
        val modules = NodeModule.entries.mapNotNull { module ->
            val types = factory.availableTypes().filter { t ->
                ALL_NODE_TYPES_BY_MODULE[module]?.contains(t) == true
            }
            if (types.isEmpty()) null else mapOf("module" to module.name, "nodes" to types)
        }
        return gson.toJson(mapOf("count" to factory.size, "modules" to modules))
    }

    private fun buildReplyJson(
        id: String,
        ok: Boolean,
        outputs: Map<String, Any?> = emptyMap(),
        error: String? = null,
    ): String {
        val obj = JsonObject()
        obj.addProperty("id", id)
        obj.addProperty("ok", ok)
        obj.add("outputs", gson.toJsonTree(outputs))
        if (error != null) obj.addProperty("error", error)
        return obj.toString()
    }
}
