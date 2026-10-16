package com.ailun.habitat

import android.content.Context
import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 工作流执行上下文。
 * 每个工作流实例拥有独立的 WorkflowContext。
 * variables 使用 ConcurrentHashMap 保证线程安全。
 * log() 输出时会附带当前工作流实例的唯一 ID (workflowId)，以便区分并发执行。
 */
class WorkflowContext internal constructor(
    private val boundAppContext: Context?,
    /**
     * 当前工作流实例的唯一标识，由 HabitatExecutor 在创建时分配。
     * 用于日志隔离，区分不同并发工作流的输出。
     */
    val workflowId: String,
    @Suppress("UNUSED_PARAMETER") detached: Boolean,
) {
    constructor(
        context: Context,
        workflowId: String = UUID.randomUUID().toString(),
    ) : this(context.applicationContext, workflowId, false)

    companion object {
        /** `${name}`：name 允许 \w 与 `.`（整体作为变量名查找，不做路径解析）。 */
        private val VAR_PATTERN = Regex("""\$\{([\w.]+)\}""")

        const val ORIGIN_AGENT = "agent"
        const val ORIGIN_MCP = "mcp"
        const val ORIGIN_WORKFLOW_PREFIX = "workflow:"

        fun workflowOrigin(name: String): String = ORIGIN_WORKFLOW_PREFIX + name.trim().take(40)

        /** 不绑定 android Context 的上下文（仅 JVM 单测 / 纯逻辑节点用；访问 appContext 会抛异常）。 */
        internal fun detached(workflowId: String = UUID.randomUUID().toString()): WorkflowContext =
            WorkflowContext(null, workflowId, true)
    }

    val appContext: Context
        get() = boundAppContext ?: throw IllegalStateException("WorkflowContext 未绑定 android Context")

    /**
     * 谁发起的这次执行：`workflow:<名字>`、`agent`、`mcp`，空表示未知。
     * 由调用方（执行服务 / Agent / MCP 服务）设置；它是 Kotlin 字段而不是变量，工作流节点改不了，
     * 小屏、工作区用它标注内容来源，远程写入不能冒充本机。
     */
    @Volatile
    var origin: String = ""

    /** 同一次运行的标识（Agent 一轮任务里多个节点共用）；空时用 [workflowId]。 */
    @Volatile
    var runKey: String = ""

    val effectiveRunKey: String get() = runKey.ifEmpty { workflowId }

    /** 线程安全的变量存储 */
    val variables: MutableMap<String, Any?> = ConcurrentHashMap()

    /**
     * 整棵树终止标志：on_error=stop 时由 executor 置 true，
     * 所有分支（含 FORK 分支）在每次循环迭代检查此标志，实现对整个工作流树的级联停止。
     */
    @Volatile
    var stopRequested: Boolean = false

    // 日志回调，由 Executor 设置
    var onLog: ((String) -> Unit)? = null

    fun putVariable(key: String, value: Any?) {
        // ConcurrentHashMap 不允许 null value：null 视为"删除该变量"
        if (value == null) variables.remove(key) else variables[key] = value
    }

    fun getVariable(key: String): Any? = variables[key]

    /**
     * 将字符串中的 ${var_name} 替换为变量的实际值；未定义变量替换为 "null"。
     * 只做**单次**替换，替换进来的值不再扫描——变量内容（短信/通知/剪贴板等外部文本）
     * 中的 `${xxx}` 原样保留，不会被二次展开而泄露其他变量。
     */
    fun interpolate(text: String): String {
        if (!text.contains("\${")) return text
        return VAR_PATTERN.replace(text) { match ->
            getVariable(match.groupValues[1])?.toString() ?: "null"
        }
    }

    /**
     * 打印工作流日志，会同时输出到 Logcat 和通过回调输出（如展示在 UI 或 DebugLogger）。
     * 日志前缀附带当前工作流实例的短 ID（前8位），便于区分并发执行的工作流。
     */
    fun log(message: String) {
        val shortId = workflowId.take(8)
        val taggedMessage = "[$shortId] $message"
        Log.i("Habitat", taggedMessage)
        onLog?.invoke(taggedMessage)
    }
}
