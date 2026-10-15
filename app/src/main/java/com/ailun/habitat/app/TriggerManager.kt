package com.ailun.habitat.app

import android.content.Context
import com.ailun.habitat.HabitatExecutionService
import com.ailun.habitat.HabitatJson
import com.ailun.habitat.HabitatStateStore
import com.ailun.habitat.NodeHandlerFactory
import com.ailun.habitat.TriggerConfig
import com.ailun.habitat.WorkflowRepository
import com.ailun.habitat.api.ITriggerSource
import com.ailun.habitat.api.TriggerEvent
import com.ailun.habitat.app.bridge.AppAccessibilityProvider
import com.ailun.habitat.app.bridge.ShizukuShellExecutor
import com.ailun.habitat.app.bridge.applyAppHandlers
import com.ailun.habitat.app.triggers.*
import com.ailun.habitat.app.workspace.WorkflowRunRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 触发器调度中心 + 工作流全局启用/禁用状态管理。
 *
 * 每个工作流一个开关：关闭时禁止一切执行（手动 + 触发）。
 * 开关状态持久化到 SharedPreferences，默认关闭；进程重启后由 [restoreOnProcessStart]
 * （HabitatApp.onCreate 调用）恢复带触发器工作流的注册。
 */
object TriggerManager {
    private const val PREFS_NAME = "habitat_trigger_prefs"

    /** repeat=true 时每个工作流默认最多并行的实例数（trigger 参数 max_concurrent 覆盖）。 */
    private const val DEFAULT_MAX_CONCURRENT = 3

    private data class TriggerEntry(
        val workflowId: String,
        val config: TriggerConfig,
        val jsonContent: String,
    )

    private val entries = ConcurrentHashMap<String, TriggerEntry>()
    private val sourceInstances = ConcurrentHashMap<String, ITriggerSource>()
    @Volatile
    private var factory: NodeHandlerFactory? = null

    /**
     * on_busy=queue 的待补跑：key=workflowId, value=最新一次排队事件的变量。
     * 采用"合并为一次、保留最新"语义：排队期间多次触发只补跑一次，使用最后一次事件的变量。
     */
    private val pendingRuns = ConcurrentHashMap<String, Map<String, Any>>()

    /** 每个工作流由触发器启动、尚未结束的 run id（repeat=true 时可能多个）。 */
    private val activeRuns = ConcurrentHashMap<String, MutableSet<String>>()

    /** runId → 本次启动的令牌，保证 onComplete 只对自己那次启动生效一次（防重复回调/旧回调误清新实例）。 */
    private val runTokens = ConcurrentHashMap<String, Long>()
    private val tokenSeq = AtomicLong(0)
    private val lock = Any()

    private val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var libraryObserver: Job? = null

    private val _enabledFlow = MutableStateFlow<Set<String>>(emptySet())
    /** 已启用工作流 id 集合（UI 实时观察开关状态）。 */
    val enabledFlow: StateFlow<Set<String>> = _enabledFlow.asStateFlow()

    // ── Global workflow enable/disable ──

    /** 工作流是否允许执行（手动或触发），默认 false。 */
    fun isWorkflowEnabled(context: Context, workflowId: String): Boolean {
        return prefs(context).getBoolean(workflowId, false)
    }

    /**
     * 设置工作流启用状态。
     * - 启用且带有 trigger：注册触发器开始监听
     * - 禁用：取消触发器注册 + 停止该工作流的所有运行实例
     */
    fun setWorkflowEnabled(
        context: Context,
        workflowId: String,
        enabled: Boolean,
        config: TriggerConfig? = null,
        jsonContent: String? = null,
    ) {
        val app = context.applicationContext
        if (enabled) {
            prefs(app).edit().putBoolean(workflowId, true).apply()
            if (config != null && jsonContent != null) {
                // Triggered workflow: register trigger listener
                register(workflowId, config, jsonContent, app)
            } else if (jsonContent != null) {
                // Non-triggered: start execution. Auto-disable when it finishes.
                val recorder = WorkflowRunRecorder.create(app, workflowId, WorkflowRepository.nameOf(app, workflowId))
                HabitatExecutionService.start(
                    workflowId = workflowId,
                    jsonContent = jsonContent,
                    androidContext = app,
                    factory = factory(app),
                    onLog = recorder?.let { r -> r::onLog },
                    onResult = recorder?.let { r -> r::onResult },
                    onComplete = {
                        recorder?.onComplete()
                        prefs(app).edit().remove(workflowId).apply()
                        refreshEnabledFlow(app)
                        notifyStateChange()
                        HabitatLogger.habitat("Workflow '$workflowId' finished, auto-disabled")
                    },
                    origin = originOf(app, workflowId),
                )
            }
        } else {
            prefs(app).edit().remove(workflowId).apply()
            unregister(workflowId, app)
            stopAllRuns(workflowId)
        }
        refreshEnabledFlow(app)
        notifyStateChange()
        HabitatLogger.habitat("Workflow '$workflowId' enabled=$enabled")
    }

    /**
     * 进程级初始化（HabitatApp.onCreate 调用一次）：
     * - 启用标记对应的工作流带 trigger → 重新注册；
     * - 无 trigger（"启用=运行一次"）或工作流已删除 → 上一进程遗留的运行中标记，清掉。
     */
    fun restoreOnProcessStart(context: Context) {
        val app = context.applicationContext
        val p = prefs(app)
        val enabledIds = p.all.filter { it.value == true }.keys
        if (enabledIds.isNotEmpty()) {
            // 读库失败时什么都不清：否则一次异常就会把所有启用标记当成"工作流已删除"抹掉
            val workflows = try {
                WorkflowRepository.getAll(app)
            } catch (e: Exception) {
                HabitatLogger.e("TriggerManager", "restore: 读取工作流库失败，保留启用标记", e)
                refreshEnabledFlow(app)
                startLibraryObserver(app)
                return
            }
            val editor = p.edit()
            for (id in enabledIds) {
                val wf = workflows.find { it.id == id }
                val config = wf?.let {
                    try { HabitatJson.fromJson(it.jsonContent).triggerConfig() } catch (_: Exception) { null }
                }
                if (wf != null && config != null) {
                    try {
                        register(id, config, wf.jsonContent, app)
                    } catch (e: Exception) {
                        HabitatLogger.e("TriggerManager", "restore '$id' failed", e)
                        editor.remove(id)
                    }
                } else {
                    editor.remove(id)
                }
            }
            editor.apply()
        }
        refreshEnabledFlow(app)
        startLibraryObserver(app)
        HabitatLogger.habitat("Triggers restored: ${entries.keys}")
    }

    /**
     * 工作流库变化（保存/编辑/删除都会 notifyLibraryChanged）时，把已注册触发器的 JSON/config
     * 同步为最新内容，调用方无需关心"已注册就不更新"的问题。
     */
    private fun startLibraryObserver(app: Context) {
        if (libraryObserver?.isActive == true) return
        libraryObserver = observerScope.launch {
            HabitatStateStore.libraryVersion.collect {
                if (entries.isEmpty()) return@collect
                val workflows = try { WorkflowRepository.getAll(app) } catch (_: Exception) { return@collect }
                for (id in entries.keys.toList()) {
                    val old = entries[id] ?: continue
                    val wf = workflows.find { it.id == id }
                    if (wf == null) {
                        unregister(id, app)
                        continue
                    }
                    if (wf.jsonContent == old.jsonContent) continue
                    val config = try { HabitatJson.fromJson(wf.jsonContent).triggerConfig() } catch (_: Exception) { null }
                    if (config == null) unregister(id, app) else register(id, config, wf.jsonContent, app)
                }
            }
        }
    }

    // ── Trigger registration ──

    /** 注册触发器。已注册且 config/json 相同 → 无操作；有变化 → 替换（编辑后的工作流立即生效）。 */
    fun register(
        workflowId: String,
        config: TriggerConfig,
        jsonContent: String,
        context: Context,
    ) {
        val app = context.applicationContext
        synchronized(lock) {
            val old = entries[workflowId]
            if (old != null) {
                if (old.config == config && old.jsonContent == jsonContent) return
                if (old.config == config) {
                    // 仅 JSON 变化：原地替换，后续触发使用新内容
                    entries[workflowId] = TriggerEntry(workflowId, config, jsonContent)
                    HabitatLogger.habitat("Trigger updated: '$workflowId'")
                    return
                }
                unregister(workflowId, app)
            }
            val entry = TriggerEntry(workflowId, config, jsonContent)
            entries[workflowId] = entry

            val source = sourceInstances.getOrPut(config.type) { createSource(config.type) }
            if (entries.values.count { it.config.type == config.type } == 1) {
                source.start(app) { event -> onTriggerEvent(event, app) }
            }
            if (source is TimerTriggerSource) {
                source.schedule(
                    workflowId,
                    intervalMs = config.intervalMs,
                    fireImmediately = config.boolParam("fire_immediately", false),
                )
            }
        }
        HabitatLogger.habitat("Trigger active: '$workflowId' type=${config.type}")
    }

    fun unregister(workflowId: String, context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            pendingRuns.remove(workflowId)
            val entry = entries.remove(workflowId) ?: return
            val type = entry.config.type
            (sourceInstances[type] as? TimerTriggerSource)?.cancel(workflowId)

            if (entries.values.none { it.config.type == type }) {
                sourceInstances[type]?.stop(app)
                sourceInstances.remove(type)
            }
            HabitatLogger.habitat("Trigger inactive: '$workflowId' type=$type")
        }
    }

    fun unregisterAll(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            sourceInstances.values.forEach { it.stop(app) }
            sourceInstances.clear()
            entries.clear()
            pendingRuns.clear()
        }
        HabitatStateStore.notifyLibraryChanged()
        HabitatLogger.habitat("All triggers unregistered")
    }

    /** 重置所有工作流状态：清除启用标记、停止执行、取消触发器。（仅供显式"全部重置"使用，启动时不再调用） */
    fun resetAll(context: Context) {
        val app = context.applicationContext
        unregisterAll(app)
        HabitatExecutionService.activeWorkflowIds().forEach { HabitatExecutionService.stop(it) }
        activeRuns.clear()
        runTokens.clear()
        prefs(app).edit().clear().apply()
        refreshEnabledFlow(app)
        factory = null
        HabitatLogger.habitat("All workflow states reset")
    }

    private fun notifyStateChange() {
        HabitatStateStore.notifyLibraryChanged()
    }

    private fun refreshEnabledFlow(context: Context) {
        _enabledFlow.value = prefs(context).all.filter { it.value == true }.keys.toSet()
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
            isWorkflowEnabled(context, entry.workflowId) && matchEvent(entry, event, context)
        }

        for (entry in matched) {
            HabitatLogger.habitat("Trigger fired: '${entry.workflowId}' type=${entry.config.type}")

            val vars = mutableMapOf<String, Any>("trigger_type" to event.type)
            event.params.forEach { (k, v) -> vars["trigger_$k"] = v }

            val workflowId = entry.workflowId
            synchronized(lock) {
                val running = isAnyRunning(workflowId)
                if (running && entry.config.repeat) {
                    // repeat=true：并行启动新实例，受 max_concurrent 限制
                    val max = entry.config.intParam("max_concurrent", DEFAULT_MAX_CONCURRENT).coerceAtLeast(1)
                    val count = runningCount(workflowId)
                    if (count >= max) {
                        HabitatLogger.habitat("Trigger '$workflowId' busy ($count/$max) → skipped")
                    } else {
                        startEntry(entry, workflowId + "_" + tokenSeq.incrementAndGet(), vars, context)
                    }
                } else if (running) {
                    // 碰撞策略
                    when (entry.config.onBusy) {
                        "abort_existing" -> {
                            stopAllRuns(workflowId)
                            startEntry(entry, workflowId, vars, context)
                        }
                        "queue" -> {
                            pendingRuns[workflowId] = vars
                            HabitatLogger.habitat("Trigger '$workflowId' busy → queued")
                        }
                        else -> HabitatLogger.habitat("Trigger '$workflowId' busy → ignored")
                    }
                } else {
                    startEntry(entry, workflowId, vars, context)
                }
            }
        }
    }

    private fun originOf(context: Context, workflowId: String): String =
        com.ailun.habitat.WorkflowContext.workflowOrigin(WorkflowRepository.nameOf(context, workflowId) ?: "工作流")

    private fun startEntry(
        entry: TriggerEntry,
        runId: String,
        vars: Map<String, Any>,
        context: Context,
    ) {
        val token = tokenSeq.incrementAndGet()
        runTokens[runId] = token
        activeRuns.getOrPut(entry.workflowId) { ConcurrentHashMap.newKeySet() }.add(runId)
        val recorder = WorkflowRunRecorder.create(context, entry.workflowId, WorkflowRepository.nameOf(context, entry.workflowId))
        val ok = HabitatExecutionService.start(
            workflowId = runId,
            jsonContent = entry.jsonContent,
            androidContext = context,
            factory = factory(context),
            initialVars = vars,
            onLog = recorder?.let { r -> r::onLog },
            onResult = recorder?.let { r -> r::onResult },
            onComplete = {
                recorder?.onComplete()
                onRunFinished(entry.workflowId, runId, token, context)
            },
            origin = originOf(context, entry.workflowId),
        )
        if (!ok) {
            runTokens.remove(runId, token)
            activeRuns[entry.workflowId]?.remove(runId)
        }
    }

    private fun onRunFinished(workflowId: String, runId: String, token: Long, context: Context) {
        synchronized(lock) {
            // 令牌不匹配 = 重复回调或已被新一次启动取代，忽略
            if (!runTokens.remove(runId, token)) return
            activeRuns[workflowId]?.remove(runId)

            // on_busy=queue：旧实例结束且有待补跑 → 用最新排队变量补跑一次（走同一 startEntry 路径，保证后续排队仍能继续）
            val vars = pendingRuns.remove(workflowId) ?: return
            val entry = entries[workflowId] ?: return
            if (!isWorkflowEnabled(context, workflowId)) return
            if (isAnyRunning(workflowId)) {
                pendingRuns[workflowId] = vars
                return
            }
            HabitatLogger.habitat("Trigger '$workflowId' queued run starting")
            startEntry(entry, workflowId, vars, context)
        }
    }

    private fun isAnyRunning(workflowId: String): Boolean =
        HabitatExecutionService.isRunning(workflowId) || runningCount(workflowId) > 0

    private fun runningCount(workflowId: String): Int =
        activeRuns[workflowId]?.count { HabitatExecutionService.isRunning(it) } ?: 0

    /** 停止该工作流的所有实例（基础 id + repeat 产生的并行实例）。 */
    private fun stopAllRuns(workflowId: String) {
        val ids = activeRuns.remove(workflowId)?.toSet().orEmpty() + workflowId
        ids.forEach {
            runTokens.remove(it)
            HabitatExecutionService.stop(it)
        }
    }

    private fun matchEvent(entry: TriggerEntry, event: TriggerEvent, context: Context): Boolean {
        val config = entry.config
        if (config.type != event.type) return false
        return when (config.type) {
            TriggerConfig.TYPE_NOTIFICATION -> {
                // 默认排除本 App 自己的通知与常驻通知，避免前台服务通知更新/ACTION_SEND_NOTIFICATION 自触发死循环
                if (!config.boolParam("include_self", false) && event.packageName == context.packageName) return false
                if (!config.boolParam("include_ongoing", false) && event.params["ongoing"] == true) return false
                val filterPkg = config.packageFilter
                filterPkg == null || filterPkg == event.packageName
            }
            TriggerConfig.TYPE_SMS -> {
                val filterPkg = config.packageFilter
                filterPkg == null || filterPkg == event.packageName
            }
            TriggerConfig.TYPE_KEY -> {
                val keycode = config.keycode
                keycode == null || keycode == event.keyCode
            }
            // 每个定时器独立调度，事件带 workflow_id，只命中自己
            TriggerConfig.TYPE_TIMER -> event.params[TimerTriggerSource.PARAM_WORKFLOW_ID] == entry.workflowId
            else -> true
        }
    }

    private fun TriggerConfig.boolParam(key: String, default: Boolean): Boolean =
        when (val v = params[key]) {
            is Boolean -> v
            null -> default
            else -> v.toString().trim().toBooleanStrictOrNull() ?: default
        }

    private fun TriggerConfig.intParam(key: String, default: Int): Int =
        when (val v = params[key]) {
            is Number -> v.toInt()
            null -> default
            else -> v.toString().trim().toDoubleOrNull()?.toInt() ?: default
        }

    private fun factory(context: Context): NodeHandlerFactory =
        factory ?: synchronized(this) { factory ?: buildFactory(context).also { factory = it } }

    private fun buildFactory(context: Context): NodeHandlerFactory {
        return NodeHandlerFactory(
            AppAccessibilityProvider,
            ShizukuShellExecutor(context.applicationContext),
        ).apply { applyAppHandlers(context.applicationContext) }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
