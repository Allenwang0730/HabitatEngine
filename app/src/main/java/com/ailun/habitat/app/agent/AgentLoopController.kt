package com.ailun.habitat.app.agent

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.ailun.habitat.app.ai.WorkflowAIClient
import com.ailun.habitat.app.auth.SecretStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AgentLoopState(
    val running: Boolean = false,
    val phase: String = "idle",
    val detail: String = "",
    val lines: List<String> = emptyList(),
)

/** 自主模式的开关、密钥和步骤日志。密钥进加密存储，不进日志。 */
object AgentLoopController {
    const val PREFS = "agent_loop_prefs"
    const val PROVIDER_JEV = "jev"
    const val PROVIDER_DEEPSEEK = "deepseek"
    const val PROVIDER_LOCAL = "local"

    /** 本机界面发起：目标原文里写了敏感词，才允许敏感按钮进候选。 */
    const val ORIGIN_LOCAL = "local"
    /** 远程（MCP/WS）发起：目标由远端提供，不允许靠目标原文解锁敏感按钮。 */
    const val ORIGIN_REMOTE = "remote"

    private const val MAX_LINES = 40

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var loop: AgentLoop? = null

    private val _state = MutableStateFlow(AgentLoopState())
    val state: StateFlow<AgentLoopState> = _state.asStateFlow()

    val running: Boolean get() = _state.value.running
    val phase: String get() = _state.value.phase
    val detail: String get() = _state.value.detail

    fun getProvider(context: Context): String =
        context.getSharedPreferences(PREFS, 0).getString("provider", PROVIDER_LOCAL) ?: PROVIDER_LOCAL

    fun setProvider(context: Context, provider: String) {
        context.getSharedPreferences(PREFS, 0).edit().putString("provider", provider).apply()
    }

    fun getJevKey(context: Context): String =
        SecretStore.get(context, SecretStore.JEV_API_KEY, legacyPrefs = PREFS, legacyKey = "jev_api_key").orEmpty()

    fun setJevKey(context: Context, key: String) {
        SecretStore.put(context, SecretStore.JEV_API_KEY, key.trim())
    }

    /** 规划/复核（System 2）用 DeepSeek。开着且配了 DeepSeek key 才生效；执行器选谁都可以。 */
    fun isPlannerEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, 0).getBoolean("planner_enabled", true)

    fun setPlannerEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, 0).edit().putBoolean("planner_enabled", enabled).apply()
    }

    /** 当前会不会用规划器：开关打开且 DeepSeek key 已配置。 */
    fun plannerActive(context: Context): Boolean =
        isPlannerEnabled(context) && !WorkflowAIClient.getApiKey(context).isNullOrBlank()

    private fun plannerFor(context: Context, force: Boolean = false): Planner? {
        if (!force && !isPlannerEnabled(context)) return null
        val key = WorkflowAIClient.getApiKey(context)?.takeIf { it.isNotBlank() } ?: return null
        return DeepSeekPlanner(key, WorkflowAIClient.getBaseUrl(context))
    }

    /** Jev 请求的 HTTP 代理（host:port），只存本机，不能远程设置。 */
    fun getJevProxy(context: Context): String =
        context.getSharedPreferences(PREFS, 0).getString("jev_proxy", "") ?: ""

    fun setJevProxy(context: Context, proxy: String) {
        val value = proxy.trim()
        val ok = value.isEmpty() || JevDecisionProvider.parseProxy(value) != null
        if (ok) context.getSharedPreferences(PREFS, 0).edit().putString("jev_proxy", value).apply()
    }

    const val DEFAULT_STEPS = 20
    const val MAX_STEPS = 40

    fun getJevModel(context: Context): String =
        context.getSharedPreferences(PREFS, 0).getString("jev_model", "jev-latest") ?: "jev-latest"

    fun setJevModel(context: Context, model: String) {
        context.getSharedPreferences(PREFS, 0).edit().putString("jev_model", model.trim().ifBlank { "jev-latest" }).apply()
    }

    fun logText(): String = _state.value.lines.joinToString("\n")

    /**
     * 这个目标还缺哪些运行时权限：打电话要 CALL_PHONE；对象不是号码也不在客服表里时要 READ_CONTACTS。
     * 只在本机界面发起前申请；远程发起不弹框（没人看得到）。
     */
    fun permissionsFor(context: Context, goal: String): List<String> {
        val intent = IntentParser.parse(goal)
        if (intent.kind == IntentKind.GENERIC) return emptyList()
        val needed = mutableListOf<String>()
        val contact = intent.contact.orEmpty()
        if (ServiceNumbers.asNumber(contact) == null && ServiceNumbers.lookup(contact) == null) {
            // 发 QQ/微信消息按名字在应用里搜，不需要通讯录
            if (intent.kind == IntentKind.CALL) needed += Manifest.permission.READ_CONTACTS
        }
        if (intent.kind == IntentKind.CALL) needed += Manifest.permission.CALL_PHONE
        return needed.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    }

    fun statusMap(): Map<String, Any> {
        val s = _state.value
        return mapOf(
            "running" to s.running,
            "phase" to s.phase,
            "detail" to s.detail,
            "log" to s.lines.joinToString("\n"),
        )
    }

    /** 已在跑或参数不合法时返回错误文案，否则启动并返回 null。 */
    fun start(
        context: Context,
        goal: String,
        providerId: String? = null,
        maxSteps: Int? = null,
        origin: String = ORIGIN_LOCAL,
        /** null 跟随界面开关；true/false 只覆盖这一次（评测 A/B 用）。 */
        usePlanner: Boolean? = null,
    ): String? {
        val text = goal.trim()
        if (text.isEmpty()) return "目标为空"
        val providerName = providerId ?: getProvider(context)
        val provider = when (providerName) {
            PROVIDER_JEV -> {
                val key = getJevKey(context)
                if (key.isBlank()) return "未配置 Jev API key"
                JevDecisionProvider(key, getJevModel(context), getJevProxy(context).ifBlank { null })
            }
            PROVIDER_DEEPSEEK -> {
                val key = WorkflowAIClient.getApiKey(context)
                if (key.isNullOrBlank()) return "未配置 DeepSeek API key"
                DeepSeekDecisionProvider(key, WorkflowAIClient.getBaseUrl(context))
            }
            PROVIDER_LOCAL -> LocalDecisionProvider()
            else -> return "未知决策器: $providerName"
        }
        val steps = (maxSteps ?: DEFAULT_STEPS).coerceIn(1, MAX_STEPS)
        val planner = if (usePlanner == false) null else plannerFor(context, force = usePlanner == true)
        val agent = AgentLoop(
            context.applicationContext,
            provider,
            steps,
            allowGoalUnlock = origin != ORIGIN_REMOTE,
            planner = planner,
        )
        synchronized(this) {
            if (_state.value.running) return "自主模式已在运行"
            val tag = (if (origin == ORIGIN_REMOTE) " · 远程" else "") + (if (planner != null) " · DeepSeek 规划" else "")
            _state.value = AgentLoopState(running = true, phase = "running", lines = listOf("开始：$providerName$tag · $text"))
            loop = agent
            // LAZY：先登记 job 再启动，finally 里按 job 身份清理，旧循环的收尾不会覆盖新一轮
            val started = scope.launch(start = CoroutineStart.LAZY) {
                var status = "error"
                var result = "循环异常"
                try {
                    val done = agent.run(text) { append(it) }
                    status = done.status
                    result = done.detail
                    append("结束 ${done.status}：${done.detail}（${done.steps} 步）")
                } catch (e: CancellationException) {
                    status = "stopped"
                    result = "已停止"
                    throw e
                } catch (e: Exception) {
                    result = e.message ?: "循环异常"
                    append("结束 error：$result")
                } finally {
                    finish(coroutineContext[Job], status, result)
                }
            }
            job = started
            started.start()
        }
        return null
    }

    fun stop() {
        synchronized(this) {
            val current = job ?: return
            loop?.stopRequested = true
            current.cancel()
            if (_state.value.running) append("已停止")
            finish(current, "stopped", "已停止")
        }
    }

    private fun finish(owner: Job?, status: String, result: String) {
        synchronized(this) {
            if (owner == null || owner !== job) return
            job = null
            loop = null
            _state.value = _state.value.copy(running = false, phase = status, detail = result)
        }
    }

    private fun append(line: String) {
        synchronized(this) {
            val lines = (_state.value.lines + line.take(180)).takeLast(MAX_LINES)
            _state.value = _state.value.copy(lines = lines)
        }
    }
}
