package com.ailun.habitat

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Job
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 工作流执行上下文。
 * 每个工作流实例拥有独立的 WorkflowContext。
 * variables 使用 ConcurrentHashMap 保证线程安全。
 * log() 输出时会附带当前工作流实例的唯一 ID (workflowId)，以便区分并发执行。
 */
class WorkflowContext(
    context: Context,
    /**
     * 当前工作流实例的唯一标识，由 HabitatExecutor 在创建时分配。
     * 用于日志隔离，区分不同并发工作流的输出。
     */
    val workflowId: String = UUID.randomUUID().toString(),
) {
    val appContext: Context = context.applicationContext

    /** 线程安全的变量存储 */
    val variables: MutableMap<String, Any?> = ConcurrentHashMap()

    /**
     * ACTION_FORK 产生的分支 Job 列表，ACTION_JOIN 等待其完成。
     * 由 HabitatExecutor 内建处理（不经过 handler）。
     */
    @Volatile
    var forkJobs: List<Job>? = null

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
     * 将字符串中的 ${var_name} 替换为变量的实际值
     */
    fun interpolate(text: String): String {
        if (!text.contains("\${")) return text

        var result = text
        val regex = """\$\{(\w+)\}""".toRegex()
        var pass = 0

        // Loop until no more ${var} patterns remain (handles nested references)
        while (result.contains("\${") && pass < 5) {
            pass++
            result = regex.replace(result) { match ->
                val varName = match.groupValues[1]
                getVariable(varName)?.toString() ?: "null"
            }
        }

        return result
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
