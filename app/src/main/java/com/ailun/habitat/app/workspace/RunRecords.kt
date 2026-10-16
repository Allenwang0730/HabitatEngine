package com.ailun.habitat.app.workspace

import android.content.Context
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.app.HabitatLogger
import com.ailun.habitat.workspace.Workspace
import com.ailun.habitat.workspace.WorkspaceStore
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一次运行的记录（Agent 任务 / 工作流），存进工作区，界面按时间线展示。
 * 只有 App 自己写（工作流节点不能写 record 类型），所以记录里的来源可信。
 */
data class RunRecord(
    /** `agent` / `workflow` */
    val type: String,
    val title: String,
    /** done / blocked / error / stopped / completed / failed */
    val status: String,
    val result: String,
    val startedAt: Long,
    val endedAt: Long,
    val lines: List<String>,
    /** Agent：goal / provider / planner / origin；工作流：workflow_id。 */
    val meta: Map<String, String> = emptyMap(),
    /** 工作流的输出变量（已过滤敏感项、截断）。 */
    val outputs: Map<String, String> = emptyMap(),
) {
    val durationMs: Long get() = (endedAt - startedAt).coerceAtLeast(0)
    val ok: Boolean get() = status == "done" || status == "completed"
}

object RunRecords {
    const val MAX_LINES = 300
    const val MAX_LINE = 300
    const val MAX_OUTPUT_VALUE = 2000
    const val MAX_OUTPUTS = 60
    const val WORKFLOW_LOG_TAIL = 50

    private const val PREFS = "workspace_prefs"
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()

    /** 名字里含这些词的变量不存（密钥、令牌、大块 base64）。 */
    private val SENSITIVE = listOf("token", "key", "password", "passwd", "secret", "credential", "cookie", "auth", "base64")

    // ── 开关 ──

    fun isAgentRecordEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, 0).getBoolean("agent_records", true)

    fun setAgentRecordEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, 0).edit().putBoolean("agent_records", enabled).apply()
    }

    /** 每个工作流单独开关，默认关。 */
    fun isWorkflowRecordEnabled(context: Context, workflowId: String): Boolean =
        workflowId in context.getSharedPreferences(PREFS, 0).getStringSet("workflow_records", emptySet()).orEmpty()

    fun setWorkflowRecordEnabled(context: Context, workflowId: String, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, 0)
        val ids = prefs.getStringSet("workflow_records", emptySet()).orEmpty().toMutableSet()
        if (enabled) ids += workflowId else ids -= workflowId
        prefs.edit().putStringSet("workflow_records", ids).apply()
    }

    // ── 纯逻辑（单测）──

    /** 过滤敏感变量、`__` 开头的内部变量、`*_success` 这类标志位之外的保留；值截断。 */
    fun filterOutputs(vars: Map<String, Any?>): Map<String, String> = vars.entries
        .asSequence()
        .filter { (k, v) -> v != null && !k.startsWith("__") }
        .filter { (k, _) -> SENSITIVE.none { k.lowercase().contains(it) } }
        .sortedBy { it.key }
        .take(MAX_OUTPUTS)
        .associate { (k, v) ->
            val s = v.toString()
            k to if (s.length <= MAX_OUTPUT_VALUE) s else s.take(MAX_OUTPUT_VALUE - 1) + "…"
        }

    fun toJson(record: RunRecord): String = gson.toJson(record.copy(lines = record.lines.takeLast(MAX_LINES).map { it.take(MAX_LINE) }))

    /** 宽松解析：缺字段（Gson 会给 null）当作空。 */
    @Suppress("SENSELESS_COMPARISON", "UselessCallOnNotNull")
    fun parse(json: String): RunRecord? = runCatching {
        val r = gson.fromJson(json, RunRecord::class.java) ?: return@runCatching null
        if (r.type == null || r.title == null) return@runCatching null
        r.copy(
            status = r.status.orEmpty(),
            result = r.result.orEmpty(),
            lines = r.lines.orEmpty(),
            meta = r.meta.orEmpty(),
            outputs = r.outputs.orEmpty(),
        )
    }.getOrNull()

    /** 条目名：「标题 · 10-14 18:30」——每次运行一条，不覆盖。 */
    fun entryName(record: RunRecord): String =
        "${record.title.take(40)} · ${SimpleDateFormat("MM-dd HH:mm", Locale.ROOT).format(Date(record.endedAt))}"

    // ── 落盘 ──

    /** 写一条运行记录；失败只记日志，不影响运行本身。在 IO 线程调用。 */
    fun save(context: Context, record: RunRecord, source: String, tags: List<String>) {
        try {
            Workspace.get(context).write(
                entryName(record), WorkspaceStore.Kind.RECORD, toJson(record).toByteArray(Charsets.UTF_8),
                source, tags, overwrite = false,
            )
        } catch (e: Exception) {
            HabitatLogger.e("RunRecords", "运行记录写入失败", e)
        }
    }

    fun saveAgent(
        context: Context, goal: String, provider: String, planner: Boolean, remote: Boolean,
        status: String, result: String, startedAt: Long, lines: List<String>,
    ) {
        if (!isAgentRecordEnabled(context)) return
        val record = RunRecord(
            type = "agent",
            title = goal.replace(Regex("\\s+"), " ").trim().take(60).ifEmpty { "Agent 任务" },
            status = status,
            result = result,
            startedAt = startedAt,
            endedAt = System.currentTimeMillis(),
            lines = lines,
            meta = mapOf(
                "goal" to goal.take(500),
                "provider" to provider,
                "planner" to planner.toString(),
                "origin" to if (remote) "remote" else "local",
            ),
        )
        save(
            context, record,
            source = if (remote) "agent:remote" else WorkflowContext.ORIGIN_AGENT,
            tags = listOf("agent", status),
        )
    }
}

/**
 * 一次工作流运行的记录器：包住 [com.ailun.habitat.HabitatExecutionService.start] 的回调。
 * 没开「运行结果存到工作区」时 [create] 返回 null，调用方原样传回调。
 */
class WorkflowRunRecorder private constructor(
    private val context: Context,
    private val workflowId: String,
    private val name: String,
) {
    private val startedAt = System.currentTimeMillis()
    private val tail = ArrayDeque<String>()
    @Volatile private var vars: Map<String, Any?>? = null
    @Volatile private var saved = false

    fun onLog(line: String) {
        synchronized(tail) {
            tail.addLast(line.take(RunRecords.MAX_LINE))
            while (tail.size > RunRecords.WORKFLOW_LOG_TAIL) tail.removeFirst()
        }
    }

    fun onResult(result: Map<String, Any?>) {
        vars = result
    }

    /** 在执行服务的 onComplete 里调用（工作流线程，可以做文件 IO）。 */
    fun onComplete() {
        if (saved) return
        saved = true
        val snapshot = vars
        val errors = (snapshot?.get("run_errors") as? Number)?.toInt() ?: 0
        val status = when {
            snapshot == null -> "stopped"
            errors > 0 -> "failed"
            else -> "completed"
        }
        val result = when (status) {
            "stopped" -> "已停止或执行异常"
            "failed" -> "完成，但有 $errors 个节点出错：${snapshot?.get("last_error_msg") ?: ""}".trim()
            else -> "完成"
        }
        val record = RunRecord(
            type = "workflow",
            title = name.ifBlank { "工作流" },
            status = status,
            result = result,
            startedAt = startedAt,
            endedAt = System.currentTimeMillis(),
            lines = synchronized(tail) { tail.toList() },
            meta = mapOf("workflow_id" to workflowId),
            outputs = snapshot?.let(RunRecords::filterOutputs).orEmpty(),
        )
        RunRecords.save(context, record, WorkflowContext.workflowOrigin(name), listOf("workflow", status))
    }

    companion object {
        /** [workflowId] 是工作流本身的 id（触发型的运行 id 带后缀，调用方传原 id）。 */
        fun create(context: Context, workflowId: String, name: String?): WorkflowRunRecorder? {
            val app = context.applicationContext
            if (!RunRecords.isWorkflowRecordEnabled(app, workflowId)) return null
            return WorkflowRunRecorder(app, workflowId, name ?: "工作流")
        }
    }
}
