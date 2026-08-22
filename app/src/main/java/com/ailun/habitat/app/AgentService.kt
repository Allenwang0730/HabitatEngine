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
import com.google.gson.JsonObject
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.CIO
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
 * Agent 服务：把 Habitat 引擎的节点暴露为 WebSocket 工具（AI 控制手机的通道）。
 *
 * 端点：
 * - GET /health   → "habitat-agent-ok"
 * - GET /tools    → 可用节点清单（JSON，按模块分组）
 * - WS  /ws       → 工具调用协议：
 *     请求: {"id":"1","tool":"ACTION_READ_SCREEN","params":{"keyword":""}}
 *     响应: {"id":"1","ok":true,"outputs":{...变量快照...},"error":null,"duration_ms":12}
 *
 * 安全：当前无鉴权（局域网演示用）。生产必须加 token。
 */
class AgentService : Service() {

    companion object {
        private const val TAG = "HabitatAgent"
        private const val PORT = 8931
        private const val TOOL_TIMEOUT_MS = 60_000L

        // 模块 → 节点类型（用于 /tools 分组展示）
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
        server?.stop(100, 500)
        server = null
        super.onDestroy()
    }

    private fun startAgentServer() {
        val factory = NodeHandlerFactory(AppAccessibilityProvider, ShizukuShellExecutor(applicationContext))
            .apply { applyAppHandlers(applicationContext) }

        val server = embeddedServer(Netty, port = PORT, host = "0.0.0.0") {
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
            }
        }
        this.server = server
        server.start(wait = false)
        Log.i(TAG, "Agent server started on :$PORT")
    }

    /** 执行一个工具调用（节点），返回 JSON 响应。 */
    private suspend fun executeTool(factory: NodeHandlerFactory, requestJson: String): String {
        val req = runCatching { gson.fromJson(requestJson, JsonObject::class.java) }.getOrNull()
            ?: return buildReplyJson("0", ok = false, error = "请求不是合法 JSON")

        val id = req.get("id")?.asString ?: "0"
        val tool = req.get("tool")?.asString ?: return buildReplyJson(id, ok = false, error = "缺少 tool 字段")

        if (tool == "ping") return buildReplyJson(id, ok = true, outputs = mapOf("pong" to true))
        if (tool == "screen") return buildReplyJson(id, ok = true, outputs = screenSnapshot())

        val params = req.get("params")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: JsonObject()

        val handler = factory.get(tool)
            ?: return buildReplyJson(id, ok = false, error = "未知工具: $tool（/tools 查看可用列表）")

        // params → Map<String, Any>（Gson 数字会变 Double，handler 内部有 Number 处理）
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
            withTimeoutOrNull(TOOL_TIMEOUT_MS) {
                runCatching { handler.handle(node, ctx) }
            }
        }

        val error = when {
            result == null -> "工具执行超时（${TOOL_TIMEOUT_MS}ms）"
            result.isFailure -> result.exceptionOrNull()?.message ?: "工具执行失败"
            else -> null
        }
        if (error != null) return buildReplyJson(id, ok = false, error = error)

        return buildReplyJson(id, ok = true, outputs = ctx.variables.toMap())
    }

    /** 无障碍树文本快照（AI 的"眼睛"，替代截图省 token）。 */
    private fun screenSnapshot(): Map<String, Any> {
        val service = AppAccessibilityProvider.getService()
        val text = if (service != null) {
            val root = service.rootInActiveWindow
            if (root != null) {
                try {
                    collectText(root).joinToString("\n")
                } finally {
                    runCatching { root.recycle() }
                }
            } else ""
        } else ""
        return mapOf(
            "screen_text" to text,
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
