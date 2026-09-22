package com.ailun.habitat.app.agent

import android.content.Context
import com.ailun.habitat.app.ai.WorkflowAIClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 自主模式的开关、密钥和步骤日志。密钥只进 SharedPreferences，不进日志。 */
object AgentLoopController {
    const val PREFS = "agent_loop_prefs"
    const val PROVIDER_JEV = "jev"
    const val PROVIDER_DEEPSEEK = "deepseek"
    const val PROVIDER_LOCAL = "local"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lines = ArrayDeque<String>()
    private var job: Job? = null
    private var loop: AgentLoop? = null

    @Volatile var running: Boolean = false
    @Volatile var phase: String = "idle"
    @Volatile var detail: String = ""

    fun getProvider(context: Context): String =
        context.getSharedPreferences(PREFS, 0).getString("provider", PROVIDER_LOCAL) ?: PROVIDER_LOCAL

    fun setProvider(context: Context, provider: String) {
        context.getSharedPreferences(PREFS, 0).edit().putString("provider", provider).apply()
    }

    fun getJevKey(context: Context): String =
        context.getSharedPreferences(PREFS, 0).getString("jev_api_key", "") ?: ""

    fun setJevKey(context: Context, key: String) {
        context.getSharedPreferences(PREFS, 0).edit().putString("jev_api_key", key.trim()).apply()
    }

    fun getJevModel(context: Context): String =
        context.getSharedPreferences(PREFS, 0).getString("jev_model", "jev-latest") ?: "jev-latest"

    fun setJevModel(context: Context, model: String) {
        context.getSharedPreferences(PREFS, 0).edit().putString("jev_model", model.trim().ifBlank { "jev-latest" }).apply()
    }

    @Synchronized fun logText(): String = lines.joinToString("\n")

    fun statusMap(): Map<String, Any> = mapOf(
        "running" to running,
        "phase" to phase,
        "detail" to detail,
        "log" to logText(),
    )

    /** 已在跑或参数不合法时返回错误文案，否则启动并返回 null。 */
    fun start(context: Context, goal: String, providerId: String? = null, maxSteps: Int? = null): String? {
        val text = goal.trim()
        if (text.isEmpty()) return "目标为空"
        if (running) return "自主模式已在运行"
        val providerName = providerId ?: getProvider(context)
        val provider = when (providerName) {
            PROVIDER_JEV -> {
                val key = getJevKey(context)
                if (key.isBlank()) return "未配置 Jev API key"
                JevDecisionProvider(key, getJevModel(context))
            }
            PROVIDER_DEEPSEEK -> {
                val key = WorkflowAIClient.getApiKey(context)
                if (key.isNullOrBlank()) return "未配置 DeepSeek API key"
                DeepSeekDecisionProvider(key, WorkflowAIClient.getBaseUrl(context))
            }
            PROVIDER_LOCAL -> LocalDecisionProvider()
            else -> return "未知决策器: $providerName"
        }
        val steps = (maxSteps ?: 12).coerceIn(1, 30)
        val app = context.applicationContext
        val agent = AgentLoop(app, provider, steps)
        synchronized(this) {
            lines.clear()
            lines.add("开始：$providerName · $text")
        }
        running = true
        phase = "running"
        detail = ""
        loop = agent
        job = scope.launch {
            try {
                val done = agent.run(text) { append(it) }
                phase = done.status
                detail = done.detail
                append("结束 ${done.status}：${done.detail}（${done.steps} 步）")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                phase = "error"
                detail = e.message ?: "循环异常"
                append("结束 error：${e.message ?: "循环异常"}")
            } finally {
                running = false
                loop = null
            }
        }
        return null
    }

    fun stop() {
        loop?.stopRequested = true
        job?.cancel()
        if (running) {
            phase = "stopped"
            detail = "已停止"
            append("已停止")
        }
        running = false
    }

    @Synchronized private fun append(line: String) {
        lines.addLast(line.take(180))
        while (lines.size > 40) lines.removeFirst()
    }
}
