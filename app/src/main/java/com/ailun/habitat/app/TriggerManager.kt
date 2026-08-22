package com.ailun.habitat.app

import android.content.Context
import com.ailun.habitat.HabitatExecutionService
import com.ailun.habitat.HabitatStateStore
import com.ailun.habitat.NodeHandlerFactory
import com.ailun.habitat.TriggerConfig
import com.ailun.habitat.api.ITriggerSource
import com.ailun.habitat.api.TriggerEvent
import com.ailun.habitat.app.bridge.AppAccessibilityProvider
import com.ailun.habitat.app.bridge.ShizukuShellExecutor
import com.ailun.habitat.app.bridge.applyAppHandlers
import com.ailun.habitat.app.triggers.*

/**
 * 触发器调度中心 + 工作流全局启用/禁用状态管理。
 *
 * 每个工作流一个开关：关闭时禁止一切执行（手动 + 触发）。
 * 开关状态持久化到 SharedPreferences，默认关闭。
 */
object TriggerManager {
    private const val PREFS_NAME = "habitat_trigger_prefs"

    private data class TriggerEntry(
        val workflowId: String,
        val config: TriggerConfig,
        val jsonContent: String,
    )

    private val entries = java.util.concurrent.ConcurrentHashMap<String, TriggerEntry>()
    private val sourceInstances = java.util.concurrent.ConcurrentHashMap<String, ITriggerSource>()
    @Volatile
    private var factory: NodeHandlerFactory? = null

    /** on_busy=queue 的待补跑集合：旧实例完成后补跑一次 */
    private val pendingRuns = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** timer 触发间隔门控：key=workflowId, value=上次触发时间戳（保证 interval_ms 生效） */
    private val timerLastFired = java.util.concurrent.ConcurrentHashMap<String, Long>()

    // ── Global workflow enable/disable ──

    /** 工作流是否允许执行（手动或触发），默认 false。 */
    fun isWorkflowEnabled(context: Context, workflowId: String): Boolean {
        return prefs(context).getBoolean(workflowId, false)
    }

    /**
     * 设置工作流启用状态。
     * - 启用且带有 trigger：注册触发器开始监听
     * - 禁用：取消触发器注册 + 停止正在运行的任务
     */
    fun setWorkflowEnabled(
        context: Context,
        workflowId: String,
        enabled: Boolean,
        config: TriggerConfig? = null,
        jsonContent: String? = null,
    ) {
        if (enabled) {
            prefs(context).edit().putBoolean(workflowId, true).apply()
            if (config != null && jsonContent != null) {
                // Triggered workflow: register trigger listener
                register(workflowId, config, jsonContent, context)
            } else if (jsonContent != null) {
                // Non-triggered: start execution. Auto-disable when it finishes.
                val f = factory ?: buildFactory(context).also { factory = it }
                HabitatExecutionService.start(
                    workflowId = workflowId,
                    jsonContent = jsonContent,
                    androidContext = context,
                    factory = f,
                    onComplete = {
                        prefs(context).edit().remove(workflowId).apply()
                        notifyStateChange()
                        HabitatLogger.habitat("Workflow '$workflowId' finished, auto-disabled")
                    },
                )
            }
        } else {
            prefs(context).edit().remove(workflowId).apply()
            unregister(workflowId, context)
            HabitatExecutionService.stop(workflowId)
        }
        notifyStateChange()
        HabitatLogger.habitat("Workflow '$workflowId' enabled=$enabled")
    }

    // ── Trigger registration ──

    fun register(
        workflowId: String,
        config: TriggerConfig,
        jsonContent: String,
        context: Context,
    ) {
        if (entries.containsKey(workflowId)) return
        entries[workflowId] = TriggerEntry(workflowId, config, jsonContent)

        val source = sourceInstances.getOrPut(config.type) { createSource(config.type) }
        if (entries.values.count { it.config.type == config.type } == 1) {
            source.start(context) { event -> onTriggerEvent(event, context) }
        }

        HabitatLogger.habitat("Trigger active: '$workflowId' type=${config.type}")
    }

    fun unregister(workflowId: String, context: Context) {
        val entry = entries.remove(workflowId) ?: return
        val type = entry.config.type
        timerLastFired.remove(workflowId)

        if (entries.values.none { it.config.type == type }) {
            sourceInstances[type]?.stop(context)
            sourceInstances.remove(type)
        }

        HabitatLogger.habitat("Trigger inactive: '$workflowId' type=$type")
    }

    fun unregisterAll(context: Context) {
        sourceInstances.values.forEach { it.stop(context) }
        sourceInstances.clear()
        entries.clear()
        HabitatStateStore.notifyLibraryChanged()
        HabitatLogger.habitat("All triggers unregistered")
    }

    /** 重置所有工作流状态：清除启用标记、停止执行、取消触发器。应用启动时调用。 */
    fun resetAll(context: Context) {
        unregisterAll(context)
        // Stop all running workflows
        HabitatExecutionService.activeWorkflowIds().forEach { HabitatExecutionService.stop(it) }
        // Clear all per-workflow enabled flags
        prefs(context).edit().clear().apply()
        factory = null
        HabitatLogger.habitat("All workflow states reset")
    }

    private fun notifyStateChange() {
        HabitatStateStore.notifyLibraryChanged()
    }

    // ── Queries ──

    fun isRegistered(workflowId: String): Boolean = entries.containsKey(workflowId)
    fun registeredWorkflowIds(): Set<String> = entries.keys.toSet()
    fun triggerConfigFor(workflowId: String): TriggerConfig? = entries[workflowId]?.config

    // ── Internals ──

    private fun createSource(type: String): ITriggerSource = when (type) {
        TriggerConfig.TYPE_NOTIFICATION -> NotificationTriggerSource()
        TriggerConfig.TYPE_SMS -> SmsTriggerSource()
        TriggerConfig.TYPE_TIMER -> TimerTriggerSource()
        TriggerConfig.TYPE_CLIPBOARD -> ClipboardTriggerSource()
        TriggerConfig.TYPE_KEY -> KeyEventTriggerSource()
        else -> throw IllegalArgumentException("Unknown trigger type: $type")
    }

    private fun onTriggerEvent(event: TriggerEvent, context: Context) {
        val matched = entries.values.filter { entry ->
            isWorkflowEnabled(context, entry.workflowId) && matchEvent(entry.config, event)
        }

        for (entry in matched) {
            // timer 触发间隔门控：source 以 5s 最小粒度 tick，这里按每个 entry 的 interval_ms 决定是否真正触发
            if (entry.config.type == TriggerConfig.TYPE_TIMER) {
                val now = System.currentTimeMillis()
                val last = timerLastFired[entry.workflowId] ?: 0L
                if (now - last < entry.config.intervalMs) continue // 间隔未到，跳过本次 tick
                timerLastFired[entry.workflowId] = now
            }

            HabitatLogger.habitat("Trigger fired: '${entry.workflowId}' type=${entry.config.type}")
            val f = factory ?: buildFactory(context).also { factory = it }

            val vars = mutableMapOf<String, Any>("trigger_type" to event.type)
            event.params.forEach { (k, v) -> vars["trigger_$k"] = v }

            val workflowId = entry.workflowId
            val running = HabitatExecutionService.isRunning(workflowId)

            if (running && entry.config.repeat) {
                // repeat=true：始终启动新实例（带时间戳 id，永不碰撞）
                startEntry(entry, workflowId + "_" + System.currentTimeMillis(), f, vars, context)
            } else if (running) {
                // 碰撞策略
                when (entry.config.onBusy) {
                    "abort_existing" -> {
                        HabitatExecutionService.stop(workflowId)
                        startEntry(entry, workflowId, f, vars, context)
                    }
                    "queue" -> {
                        pendingRuns.add(workflowId)
                        HabitatLogger.habitat("Trigger '${workflowId}' busy → queued")
                    }
                    else -> HabitatLogger.habitat("Trigger '${workflowId}' busy → ignored")
                }
            } else {
                startEntry(entry, workflowId, f, vars, context)
            }
        }
    }

    private fun startEntry(
        entry: TriggerEntry,
        runId: String,
        f: NodeHandlerFactory,
        vars: Map<String, Any>,
        context: Context,
    ) {
        HabitatExecutionService.start(
            workflowId = runId,
            jsonContent = entry.jsonContent,
            androidContext = context,
            factory = f,
            initialVars = vars,
            onComplete = {
                // on_busy=queue：旧实例结束且有待补跑 → 补跑一次
                if (pendingRuns.remove(entry.workflowId)) {
                    HabitatLogger.habitat("Trigger '${entry.workflowId}' queued run starting")
                    HabitatExecutionService.start(
                        workflowId = entry.workflowId,
                        jsonContent = entry.jsonContent,
                        androidContext = context,
                        factory = f,
                        initialVars = vars,
                    )
                }
            },
        )
    }

    private fun matchEvent(config: TriggerConfig, event: TriggerEvent): Boolean {
        if (config.type != event.type) return false
        return when (config.type) {
            TriggerConfig.TYPE_NOTIFICATION, TriggerConfig.TYPE_SMS -> {
                val filterPkg = config.packageFilter
                filterPkg == null || filterPkg == event.packageName
            }
            TriggerConfig.TYPE_KEY -> {
                val keycode = config.keycode
                keycode == null || keycode == event.keyCode
            }
            else -> true
        }
    }

    private fun buildFactory(context: Context): NodeHandlerFactory {
        return NodeHandlerFactory(
            AppAccessibilityProvider,
            ShizukuShellExecutor(context.applicationContext),
        ).apply { applyAppHandlers(context.applicationContext) }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
