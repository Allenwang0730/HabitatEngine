package com.ailun.habitat.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.ailun.habitat.NodeHandlerFactory
import com.ailun.habitat.NodeModule
import com.ailun.habitat.R
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode
import com.ailun.habitat.app.agent.AgentLoopController
import com.ailun.habitat.app.agent.Catalogs
import com.ailun.habitat.app.agent.ScreenObserver
import com.ailun.habitat.app.auth.SecretStore
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Agent 服务（前台服务）：把 Habitat 引擎的节点暴露为工具（AI 控制手机的通道）。
 *
 * 端点：
 * - GET /health  → "habitat-agent-ok"（不鉴权）
 * - GET /tools   → 可用节点清单（JSON，按模块分组）
 * - WS  /ws      → 自定义工具调用协议（JSON：tool/params → 执行节点 → 变量快照）
 * - WS  /mcp     → MCP（Model Context Protocol）JSON-RPC 2.0：initialize / tools/list / tools/call
 *
 * 安全：
 * - 除 /health 外都要 token（`Authorization: Bearer <token>` 或 `?token=`），常量时间比较；token 首次随机生成。
 * - 默认只绑 127.0.0.1（adb forward 可用），局域网访问需在控制页显式开启。
 * - [REMOTE_DANGEROUS_TOOLS] 默认拒绝远程调用，需显式开启。
 */
class AgentService : Service() {

    companion object {
        private const val TAG = "HabitatAgent"
        private const val PREFS = "agent_prefs"
        const val DEFAULT_PORT = 8931
        private const val TOOL_TIMEOUT_MS = 60_000L
        private const val NOTIFICATION_ID = 31043
        private const val CHANNEL_ID = "habitat_agent_service"
        private const val LEGACY_DEFAULT_TOKEN = "habitat-dev-token"
        private const val MIN_TOKEN_LENGTH = 16

        /** 服务运行标志（同进程内由 startAgentServer/onDestroy 维护，控制页直接读取）。 */
        @Volatile
        var serverRunning: Boolean = false

        /** 前台启动服务（调用方需处于前台）。 */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, AgentService::class.java))
        }

        /** 当前服务端口（可配置，存 SharedPreferences）。 */
        fun getPort(context: Context): Int =
            context.getSharedPreferences(PREFS, 0).getInt("port", DEFAULT_PORT)

        fun setPort(context: Context, port: Int) {
            context.getSharedPreferences(PREFS, 0).edit().putInt("port", port.coerceIn(1024, 65535)).apply()
        }

        /** 访问令牌（加密存储）。不存在或仍是旧默认值时随机生成。 */
        fun getToken(context: Context): String {
            val current = SecretStore.get(context, SecretStore.AGENT_TOKEN, legacyPrefs = PREFS, legacyKey = "token")
            if (current != null && current != LEGACY_DEFAULT_TOKEN && current.length >= MIN_TOKEN_LENGTH) return current
            return regenerateToken(context)
        }

        fun regenerateToken(context: Context): String {
            val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val token = bytes.joinToString("") { "%02x".format(it) }
            SecretStore.put(context, SecretStore.AGENT_TOKEN, token)
            return token
        }

        /** 设置自定义令牌；长度不足 16 或为旧默认值时拒绝。 */
        fun setToken(context: Context, token: String): Boolean {
            val t = token.trim()
            if (t.length < MIN_TOKEN_LENGTH || t == LEGACY_DEFAULT_TOKEN) return false
            SecretStore.put(context, SecretStore.AGENT_TOKEN, t)
            return true
        }

        /** 是否监听局域网（0.0.0.0）。默认只绑 127.0.0.1；修改后重启服务生效。 */
        fun isLanEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, 0).getBoolean("lan_enabled", false)

        fun setLanEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, 0).edit().putBoolean("lan_enabled", enabled).apply()
        }

        /** 是否允许远程调用 [REMOTE_DANGEROUS_TOOLS]。默认 false。 */
        fun isRemoteDangerousAllowed(context: Context): Boolean =
            context.getSharedPreferences(PREFS, 0).getBoolean("remote_dangerous", false)

        fun setRemoteDangerousAllowed(context: Context, allowed: Boolean) {
            context.getSharedPreferences(PREFS, 0).edit().putBoolean("remote_dangerous", allowed).apply()
        }

        /** 远程（/ws、/mcp）默认拒绝的高危节点：任意命令、文件/数据库、通话、短信/通话记录、录音拍照等。 */
        val REMOTE_DANGEROUS_TOOLS: Set<String> = setOf(
            NodeHandlerFactory.ACTION_SHELL,
            NodeHandlerFactory.ACTION_FILE_OPERATION,
            NodeHandlerFactory.ACTION_SQLITE_QUERY,
            NodeHandlerFactory.ACTION_ZIP,
            NodeHandlerFactory.ACTION_CRYPTO,
            NodeHandlerFactory.ACTION_CALL_PHONE,
            NodeHandlerFactory.ACTION_CALL_DIRECT,
            NodeHandlerFactory.ACTION_READ_SMS,
            NodeHandlerFactory.ACTION_READ_CALL_LOG,
            NodeHandlerFactory.ACTION_FORCE_STOP_APP,
            NodeHandlerFactory.ACTION_SEND_BROADCAST,
            NodeHandlerFactory.ACTION_SET_WALLPAPER,
            NodeHandlerFactory.ACTION_RECORD_AUDIO,
            NodeHandlerFactory.ACTION_CAPTURE_PHOTO,
            NodeHandlerFactory.ACTION_SAY_TO_FILE,
        )

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

    /** 工具执行专用作用域：超时后请求立即回包，阻塞中的 handler 留在这里跑完，不占用会话接收循环。 */
    private val toolScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundSafely()
        try {
            startAgentServer()
        } catch (t: Throwable) {
            Log.e(TAG, "Agent server start failed: ${t.javaClass.simpleName}: ${t.message}")
            serverRunning = false
            server = null
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        serverRunning = false
        val s = server
        server = null
        toolScope.cancel()
        if (s != null) {
            // Netty 停机会阻塞，放后台线程
            Thread({ runCatching { s.stop(100, 500) } }, "habitat-agent-stop").start()
        }
        super.onDestroy()
    }

    private fun startForegroundSafely() {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Agent 服务", NotificationManager.IMPORTANCE_LOW),
            )
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "startForeground failed: ${t.javaClass.simpleName}")
        }
    }

    private fun createNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: Intent()
        val pending = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val scope = if (isLanEnabled(this)) "局域网" else "仅本机"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_workflows)
            .setContentTitle("Habitat Agent 服务运行中")
            .setContentText("端口 ${getPort(this)} · $scope")
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    private fun startAgentServer() {
        val factory = NodeHandlerFactory(AppAccessibilityProvider, ShizukuShellExecutor(applicationContext))
            .apply { applyAppHandlers(applicationContext) }
        val port = getPort(this)
        val host = if (isLanEnabled(this)) "0.0.0.0" else "127.0.0.1"

        val server = embeddedServer(Netty, port = port, host = host) {
            install(WebSockets)
            routing {
                get("/health") {
                    call.respondText("habitat-agent-ok")
                }
                get("/tools") {
                    if (!authorized(call)) {
                        call.respondText("unauthorized", status = HttpStatusCode.Unauthorized)
                        return@get
                    }
                    call.respondText(buildToolsJson(factory), ContentType.Application.Json)
                }
                // 鉴权：/ws 和 /mcp 都校验 token，不匹配则拒绝连接
                webSocket("/ws") {
                    if (!authorized(call)) {
                        Log.w(TAG, "WS denied: bad token")
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
                        return@webSocket
                    }
                    Log.i(TAG, "WS connected")
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        val text = frame.readText()
                        // 每个请求独立协程，慢工具不阻塞接收循环
                        launch {
                            val reply = try {
                                executeTool(factory, text)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                buildReplyJson("0", ok = false, error = e.message ?: e.javaClass.simpleName)
                            }
                            runCatching { send(Frame.Text(reply)) }
                        }
                    }
                }
                // MCP（Model Context Protocol）端点：JSON-RPC 2.0 over WebSocket
                webSocket("/mcp") {
                    if (!authorized(call)) {
                        Log.w(TAG, "MCP denied: bad token")
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
                        return@webSocket
                    }
                    Log.i(TAG, "MCP client connected")
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        val text = frame.readText()
                        launch {
                            val reply = handleMcpMessageSafely(factory, text)
                            if (reply != null) runCatching { send(Frame.Text(reply)) }
                        }
                    }
                }
            }
        }
        server.start(wait = false)
        this.server = server
        serverRunning = true
        Log.i(TAG, "Agent server started on $host:$port")
    }

    /** 远程调用策略：高危节点需显式开启。返回拒绝原因，null 表示放行。 */
    private fun remotePolicyError(tool: String, params: JsonObject): String? {
        val action = runCatching { params.get("action")?.asString }.getOrNull()
        return RemoteToolPolicy.check(tool, action, REMOTE_DANGEROUS_TOOLS, isRemoteDangerousAllowed(this))
    }

    /**
     * 在 [toolScope] 执行 handler 并限时等待。超时即返回 null；阻塞型 handler 无法被打断，会在后台自行结束。
     */
    private suspend fun runHandler(handler: com.ailun.habitat.INodeHandler, node: WorkflowNode, ctx: WorkflowContext): Result<String?>? {
        val deferred = toolScope.async { runCatching { handler.handle(node, ctx) } }
        val result = withTimeoutOrNull(TOOL_TIMEOUT_MS) { deferred.await() }
        if (result == null) deferred.cancel()
        return result
    }

    /** 执行一个工具调用（节点），返回 JSON 响应。 */
    private suspend fun executeTool(factory: NodeHandlerFactory, requestJson: String): String {
        val req = runCatching { gson.fromJson(requestJson, JsonObject::class.java) }.getOrNull()
            ?: return buildReplyJson("0", ok = false, error = "请求不是合法 JSON")

        val id = runCatching { req.get("id")?.asString }.getOrNull() ?: "0"
        val tool = runCatching { req.get("tool")?.asString }.getOrNull()
            ?: return buildReplyJson(id, ok = false, error = "缺少 tool 字段")

        if (tool == "ping") return buildReplyJson(id, ok = true, outputs = mapOf("pong" to true))
        if (tool == "screen") return buildReplyJson(id, ok = true, outputs = screenSnapshot())
        if (tool == "mini_screen_status") return buildReplyJson(id, ok = true, outputs = com.ailun.habitat.app.miniscreen.MiniScreen.statusMap())
        if (tool == "debug_screen") {
            val params = req.get("params")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
            val goal = runCatching { params.get("goal")?.asString }.getOrNull()?.trim().takeUnless { it.isNullOrEmpty() }
                ?: "打开设置"
            return buildReplyJson(id, ok = true, outputs = mapOf("result" to debugScreen(goal)))
        }
        if (tool.startsWith("agent_")) {
            val params = req.get("params")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
            val (ok, text) = handleAgentControl(tool, params)
            return if (ok) buildReplyJson(id, ok = true, outputs = mapOf("result" to text))
            else buildReplyJson(id, ok = false, error = text)
        }

        val params = req.get("params")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        return executeNodeReply(factory, id, tool, params)
    }

    /** 执行节点并组装自定义协议响应。 */
    private suspend fun executeNodeReply(factory: NodeHandlerFactory, id: String, tool: String, params: JsonObject): String {
        remotePolicyError(tool, params)?.let { return buildReplyJson(id, ok = false, error = it) }
        val handler = factory.get(tool)
            ?: return buildReplyJson(id, ok = false, error = "未知工具: $tool（/tools 查看可用列表）")

        val node = WorkflowNode().apply {
            this.id = "agent_tool"
            this.type = tool
            this.params = toParamMap(params)
        }
        val ctx = WorkflowContext(applicationContext)
        ctx.origin = WorkflowContext.ORIGIN_MCP
        val result = runHandler(handler, node, ctx)
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

    private fun toParamMap(params: JsonObject): MutableMap<String, Any> {
        val paramMap = mutableMapOf<String, Any>()
        params.entrySet().forEach { (k, v) ->
            gson.fromJson(v, Any::class.java)?.let { paramMap[k] = it }
        }
        return paramMap
    }

    // ── MCP（JSON-RPC 2.0 over WebSocket）──

    /** 包一层异常处理：内部异常回 -32603（有 id 时），通知类不回包。 */
    private suspend fun handleMcpMessageSafely(factory: NodeHandlerFactory, requestJson: String): String? {
        val req = runCatching { gson.fromJson(requestJson, JsonObject::class.java) }.getOrNull()
            ?: return mcpError(null, -32700, "Parse error")
        val id = if (req.has("id")) req.get("id") else null
        return try {
            handleMcpMessage(factory, req, id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "MCP internal error: ${e.javaClass.simpleName}")
            if (id != null) mcpError(id, -32603, "Internal error: ${e.message ?: e.javaClass.simpleName}") else null
        }
    }

    /** 处理一条 MCP 消息；通知类返回 null 不回包。 */
    private suspend fun handleMcpMessage(factory: NodeHandlerFactory, req: JsonObject, id: JsonElement?): String? {
        val method = runCatching { req.get("method")?.asString }.getOrNull()
            ?: return mcpError(id, -32600, "Invalid Request")
        val params = req.get("params")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()

        return when (method) {
            "initialize" -> mcpResult(id, JsonObject().apply {
                addProperty("protocolVersion", "2025-06-18")
                add("capabilities", JsonObject().apply { add("tools", JsonObject()) })
                add("serverInfo", JsonObject().apply {
                    addProperty("name", "habitat-agent")
                    addProperty("version", com.ailun.habitat.BuildConfig.VERSION_NAME)
                })
            })

            "notifications/initialized", "notifications/cancelled" -> null

            "ping" -> mcpResult(id, JsonObject())

            "tools/list" -> mcpResult(id, JsonObject().apply { add("tools", buildMcpTools(factory)) })

            "tools/call" -> {
                val name = runCatching { params.get("name")?.asString }.getOrNull()
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
        if (tool == "mini_screen_status") return true to gson.toJson(com.ailun.habitat.app.miniscreen.MiniScreen.statusMap())
        if (tool == "debug_screen") {
            val goal = runCatching { args.get("goal")?.asString }.getOrNull()?.trim().takeUnless { it.isNullOrEmpty() }
                ?: "打开设置"
            return true to debugScreen(goal)
        }
        if (tool.startsWith("agent_")) return handleAgentControl(tool, args)
        remotePolicyError(tool, args)?.let { return false to it }

        val handler = factory.get(tool)
            ?: return false to "未知工具: $tool"
        val node = WorkflowNode().apply {
            this.id = "mcp_tool"
            this.type = tool
            this.params = toParamMap(args)
        }
        val ctx = WorkflowContext(applicationContext)
        ctx.origin = WorkflowContext.ORIGIN_MCP
        val result = runHandler(handler, node, ctx)
        return when {
            result == null -> false to "工具执行超时（${TOOL_TIMEOUT_MS}ms）"
            result.isFailure -> false to (result.exceptionOrNull()?.message ?: "工具执行失败")
            else -> true to gson.toJson(ctx.variables.toMap())
        }
    }

    /** 生成 MCP tools 清单（节点 + 内置 screen/ping/agent_*）。未开启时不列出高危节点。 */
    private fun buildMcpTools(factory: NodeHandlerFactory): JsonArray {
        val arr = JsonArray()
        arr.add(mcpToolSchema("screen", "获取当前屏幕无障碍树文本 + 前台应用包名（AI 的眼睛）"))
        arr.add(mcpToolSchema("debug_screen", "排查用：当前观测快照 + 候选动作清单。arguments: goal（可空，默认「打开设置」）"))
        arr.add(mcpToolSchema("ping", "连通性测试"))
        arr.add(mcpToolSchema("mini_screen_status", "悬浮小屏当前内容（是否显示、标题、来源、文字、进度），只读"))
        arr.add(mcpToolSchema("agent_start", "启动自主循环。arguments: goal（必填）, provider（jev/deepseek/local，可空）, max_steps（1-40，默认 20，可空）, planner（true/false，可空，覆盖界面开关）。配了 DeepSeek key 且开启规划时，先由 DeepSeek 拆步骤、最后复核"))
        arr.add(mcpToolSchema("agent_stop", "停止自主循环"))
        arr.add(mcpToolSchema("agent_status", "自主循环状态与最近步骤"))
        val allowDangerous = isRemoteDangerousAllowed(this)
        factory.availableTypes().sorted().forEach { type ->
            if (!allowDangerous && type in REMOTE_DANGEROUS_TOOLS) return@forEach
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

    /** 自主循环控制（远程来源）。不在这里阻塞等待循环结束。 */
    private fun handleAgentControl(tool: String, args: JsonObject): Pair<Boolean, String> {
        return when (tool) {
            "agent_status" -> true to gson.toJson(AgentLoopController.statusMap())
            "agent_stop" -> {
                AgentLoopController.stop()
                true to gson.toJson(AgentLoopController.statusMap())
            }
            "agent_start" -> {
                val goal = runCatching { args.get("goal")?.asString }.getOrNull()?.trim().orEmpty()
                val provider = runCatching { args.get("provider")?.asString }.getOrNull()
                val maxSteps = args.get("max_steps")?.let { runCatching { it.asInt }.getOrNull() }
                val usePlanner = args.get("planner")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
                val err = AgentLoopController.start(
                    applicationContext, goal, provider, maxSteps,
                    origin = AgentLoopController.ORIGIN_REMOTE,
                    usePlanner = usePlanner,
                )
                if (err != null) false to err else true to gson.toJson(AgentLoopController.statusMap())
            }
            else -> false to "未知工具: $tool"
        }
    }

    /** 鉴权：`Authorization: Bearer <token>` 或 `?token=`，常量时间比较。 */
    private fun authorized(call: ApplicationCall): Boolean {
        val header = call.request.headers[HttpHeaders.Authorization]
        val given = header?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)?.trim()
            ?: call.request.queryParameters["token"]
            ?: return false
        val expected = getToken(this@AgentService)
        return MessageDigest.isEqual(given.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))
    }

    /**
     * 排查用快照：当前观测（含窗口/元素细节）+ 用 [goal] 生成的候选动作。
     * 只读，不执行任何节点，所以不进 [REMOTE_DANGEROUS_TOOLS]。
     */
    private suspend fun debugScreen(goal: String): String {
        val (width, height) = screenMetrics()
        if (ScreenObserver.screenState(this).locked) {
            return "屏幕已锁定：观测为空，不能决策。请先解锁手机再调用 debug_screen。\n" +
                "size=${width}x${height}"
        }
        val observation = ScreenObserver.observe(this)
        val catalog = Catalogs.build(goal, observation, ScreenObserver.launcherApps(this))
        return buildString {
            append("size=").append(observation.screenWidth).append('x').append(observation.screenHeight)
            append(" goal=").append(goal)
            append('\n')
            append(ScreenObserver.describe(observation)).append('\n')
            append("candidates: total=").append(catalog.actions.size)
            append(" TAP=").append(catalog.tapTargets.size)
            append(" TYPE_TEXT=").append(catalog.textValues.size)
            append(" OPEN_APP=").append(catalog.apps.size)
            append(" operations=").append(catalog.operations.keys.joinToString(","))
            if (catalog.guard != null) append(" guard=").append(catalog.guard)
            append('\n')
            catalog.actions.forEach { a ->
                append("- ").append(a.id).append(" [").append(a.operation).append("] ")
                append(a.label)
                if (a.centerX != null && a.centerY != null) append(" @").append(a.centerX).append(',').append(a.centerY)
                append('\n')
            }
        }
    }

    /** 与读屏同一来源的屏幕像素尺寸（无障碍坐标 / 截图 / 滑动都用这个）。 */
    private fun screenMetrics(): Pair<Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm?.maximumWindowMetrics?.bounds
            if (bounds != null && bounds.width() > 0 && bounds.height() > 0) return bounds.width() to bounds.height()
        }
        val dm = resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }

    /** 屏幕快照（AI 的"眼睛"）：返回屏幕状态 + 所有窗口文本（无障碍树，替代截图省 token）。 */
    private fun screenSnapshot(): Map<String, Any> {
        val service = AppAccessibilityProvider.getService()
        val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        val screenOn = pm?.isInteractive ?: false
        val locked = km?.isKeyguardLocked ?: false

        // 遍历所有窗口（对齐 NodeReadScreenHandler）：锁屏/悬浮层盖住时也能读到应用窗口
        val windows = mutableListOf<Map<String, Any>>()
        if (service != null && screenOn) {
            try {
                service.windows.forEach { w ->
                    if (w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION ||
                        w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM ||
                        w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
                    ) {
                        val root = w.root ?: return@forEach
                        try {
                            val pkg = root.packageName?.toString() ?: ""
                            val texts = collectText(root)
                            if (texts.isNotEmpty() || pkg.isNotEmpty()) {
                                windows.add(
                                    mapOf(
                                        "package" to pkg,
                                        "type" to windowTypeName(w.type),
                                        "type_id" to w.type,
                                        "active" to w.isActive,
                                        "focused" to w.isFocused,
                                        "text" to texts.joinToString("\n"),
                                    ),
                                )
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
            "window_count" to windows.size,
        )
    }

    private fun windowTypeName(type: Int): String = when (type) {
        android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION -> "application"
        android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "ime"
        android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
        android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "a11y_overlay"
        android.view.accessibility.AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "split_divider"
        else -> "other($type)"
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
        return gson.toJson(
            mapOf(
                "count" to factory.size,
                "modules" to modules,
                "builtin_tools" to listOf("ping", "screen", "debug_screen", "mini_screen_status", "agent_start", "agent_stop", "agent_status"),
                "debug_screen" to "排查用：当前观测快照 + 候选动作清单；params.goal（默认「打开设置」）",
                "remote_dangerous_allowed" to isRemoteDangerousAllowed(this),
                "remote_dangerous_tools" to REMOTE_DANGEROUS_TOOLS.sorted(),
            ),
        )
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
