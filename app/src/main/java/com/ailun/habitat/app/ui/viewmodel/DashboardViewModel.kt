package com.ailun.habitat.app.ui.viewmodel

import android.app.Application
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ailun.habitat.HabitatExecutionService
import com.ailun.habitat.HabitatJson
import com.ailun.habitat.HabitatSamples
import com.ailun.habitat.HabitatStateStore
import com.ailun.habitat.HabitatWorkflow
import com.ailun.habitat.LintIssue
import com.ailun.habitat.NodeHandlerFactory
import com.ailun.habitat.TriggerConfig
import com.ailun.habitat.WorkflowRepository
import com.ailun.habitat.app.TriggerManager
import com.ailun.habitat.app.bridge.AppAccessibilityProvider
import com.ailun.habitat.app.bridge.ShizukuShellExecutor
import com.ailun.habitat.app.bridge.applyAppHandlers
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** 列表项：工作流 + 预解析结果（解析在 Dispatchers.Default，按 JSON 内容缓存）。 */
data class WorkflowItem(
    val workflow: HabitatWorkflow,
    val trigger: TriggerConfig?,
    val nodeCount: Int,
    val parseError: String?,
) {
    val id: String get() = workflow.id
    val hasTrigger: Boolean get() = trigger != null
}

/** 编辑器问题（lint 或 JSON 语法）。 */
data class EditorProblem(
    val nodeId: String?,
    val message: String,
    val isError: Boolean,
    val line: Int? = null,
    val column: Int? = null,
)

data class EditorSession(val base: HabitatWorkflow, val isNew: Boolean)

/** 工作流主页 + 编辑器状态。Activity 级（ViewModelProvider(activity)），切 tab/旋转不丢。 */
class DashboardViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx get() = getApplication<Application>()

    private val _items = MutableStateFlow<List<WorkflowItem>>(emptyList())
    val items: StateFlow<List<WorkflowItem>> = _items.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val _mountedIds = MutableStateFlow<Set<String>>(emptySet())
    val mountedIds: StateFlow<Set<String>> = _mountedIds.asStateFlow()

    /** 已左滑删除、等待「撤销」超时的 id（列表中隐藏）。 */
    private val _pendingDeletes = MutableStateFlow<Set<String>>(emptySet())
    val pendingDeletes: StateFlow<Set<String>> = _pendingDeletes.asStateFlow()

    var query by mutableStateOf("")
    var triggerFilter by mutableStateOf<String?>(null)

    val snackbar = SnackbarHostState()

    private val parseCache = ConcurrentHashMap<String, Triple<TriggerConfig?, Int, String?>>()
    private var triggersSynced = false

    /** 引擎已注册的节点类型（lint 用），只算一次。 */
    val knownTypes: Set<String> by lazy {
        NodeHandlerFactory().apply { applyAppHandlers(ctx) }.availableTypes() +
            setOf(NodeHandlerFactory.ACTION_FORK, NodeHandlerFactory.ACTION_JOIN)
    }

    init {
        viewModelScope.launch {
            HabitatStateStore.libraryVersion.collect { reload() }
        }
    }

    private suspend fun reload() {
        val (list, mounted) = withContext(Dispatchers.IO) {
            WorkflowRepository.getAll(ctx) to WorkflowRepository.getFloatMountedWorkflowIds(ctx)
        }
        val parsed = withContext(Dispatchers.Default) {
            val live = list.mapTo(HashSet()) { it.jsonContent }
            parseCache.keys.retainAll(live)
            list.map { wf ->
                val (trigger, count, err) = parseCache.getOrPut(wf.jsonContent) { parse(wf.jsonContent) }
                WorkflowItem(wf, trigger, count, err)
            }
        }
        _items.value = parsed
        _mountedIds.value = mounted
        _loaded.value = true
        if (!triggersSynced) {
            triggersSynced = true
            syncTriggers(parsed)
        }
    }

    private fun parse(json: String): Triple<TriggerConfig?, Int, String?> = try {
        val g = HabitatJson.fromJson(json)
        Triple(g.triggerConfig(), g.nodes?.size ?: 0, null)
    } catch (e: Exception) {
        Triple(null, 0, e.message ?: "解析失败")
    }

    /** 已启用但未注册的触发器补注册；已禁用或已删除的注销。register 幂等。 */
    private suspend fun syncTriggers(items: List<WorkflowItem>) = withContext(Dispatchers.Default) {
        val registered = TriggerManager.registeredWorkflowIds()
        val byId = items.associateBy { it.id }
        for (id in registered) {
            val item = byId[id]
            if (item?.trigger == null || !TriggerManager.isWorkflowEnabled(ctx, id)) TriggerManager.unregister(id, ctx)
        }
        for (item in items) {
            val cfg = item.trigger ?: continue
            if (TriggerManager.isWorkflowEnabled(ctx, item.id)) {
                TriggerManager.register(item.id, cfg, item.workflow.jsonContent, ctx)
            }
        }
    }

    // ── 列表操作 ──

    fun isRunning(id: String, running: Set<String>): Boolean =
        id in running || running.any { it.startsWith(id + "_") }

    fun setEnabled(item: WorkflowItem, enabled: Boolean) {
        TriggerManager.setWorkflowEnabled(ctx, item.id, enabled, item.trigger, item.workflow.jsonContent)
    }

    /** 手动工作流：启用 = 运行一次（结束后自动禁用），与悬浮窗/旧版行为一致。 */
    fun run(item: WorkflowItem) {
        TriggerManager.setWorkflowEnabled(ctx, item.id, true, null, item.workflow.jsonContent)
    }

    /** 停止运行。触发型只停当前实例，保持监听；手动型等同禁用。 */
    fun stop(item: WorkflowItem) {
        if (item.hasTrigger) {
            HabitatExecutionService.activeWorkflowIds()
                .filter { it == item.id || it.startsWith(item.id + "_") }
                .forEach { HabitatExecutionService.stop(it) }
        } else {
            TriggerManager.setWorkflowEnabled(ctx, item.id, false)
        }
    }

    fun toggleMount(item: WorkflowItem): Boolean {
        val nowMounted = if (WorkflowRepository.isFloatMounted(ctx, item.id)) {
            WorkflowRepository.removeFloatMountedWorkflowId(ctx, item.id); false
        } else {
            WorkflowRepository.addFloatMountedWorkflowId(ctx, item.id); true
        }
        _mountedIds.value = WorkflowRepository.getFloatMountedWorkflowIds(ctx)
        HabitatStateStore.notifyLibraryChanged()
        return nowMounted
    }

    /** 左滑删除：先隐藏，Snackbar「撤销」超时后才真正删除。 */
    fun requestDelete(item: WorkflowItem) {
        _pendingDeletes.value = _pendingDeletes.value + item.id
        viewModelScope.launch {
            val result = snackbar.showSnackbar(
                message = "已删除「${item.workflow.name}」",
                actionLabel = "撤销",
                withDismissAction = true,
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) {
                _pendingDeletes.value = _pendingDeletes.value - item.id
            } else {
                commitDelete(item.id)
            }
        }
    }

    private fun commitDelete(id: String) {
        if (id !in _pendingDeletes.value) return
        TriggerManager.setWorkflowEnabled(ctx, id, false)
        WorkflowRepository.removeFloatMountedWorkflowId(ctx, id)
        WorkflowRepository.delete(ctx, id)
        _pendingDeletes.value = _pendingDeletes.value - id
        HabitatStateStore.notifyLibraryChanged()
    }

    override fun onCleared() {
        // 离开时仍在撤销窗口内的删除直接落盘
        _pendingDeletes.value.toList().forEach { commitDelete(it) }
        super.onCleared()
    }

    // ── 编辑器 ──

    var session by mutableStateOf<EditorSession?>(null)
        private set
    var editName by mutableStateOf("")
    var editDescription by mutableStateOf("")
    var editJson by mutableStateOf(TextFieldValue(""))

    val isDirty: Boolean
        get() {
            val s = session ?: return false
            return s.isNew || editName != s.base.name || editDescription != s.base.description ||
                editJson.text != s.base.jsonContent
        }

    val editorLogs: StateFlow<List<String>> get() = RunLogStore.lines(EDITOR_RUN_ID)

    fun openEditor(wf: HabitatWorkflow) {
        // 内置/AI 生成的工作流多是单行 JSON，打开时展开成多行；基准同步替换，不会被误判为"未保存"
        val readable = if (wf.jsonContent.contains('\n')) wf.jsonContent
        else runCatching { prettyGson.toJson(JsonParser.parseString(wf.jsonContent)) }.getOrDefault(wf.jsonContent)
        val base = wf.copy(jsonContent = readable)
        session = EditorSession(base, isNew = false)
        editName = base.name
        editDescription = base.description
        editJson = TextFieldValue(base.jsonContent)
        RunLogStore.clear(EDITOR_RUN_ID)
    }

    fun newWorkflow() {
        val wf = HabitatWorkflow(name = "新工作流", description = "", jsonContent = HabitatSamples.MVP_THREE_NODE.trimIndent())
        session = EditorSession(wf, isNew = true)
        editName = wf.name
        editDescription = ""
        editJson = TextFieldValue(wf.jsonContent)
        RunLogStore.clear(EDITOR_RUN_ID)
    }

    fun closeEditor() {
        session = null
    }

    /** 保存；返回是否成功。 */
    suspend fun saveEditor(): Boolean {
        val s = session ?: return false
        val saved = s.base.copy(
            name = editName.trim().ifEmpty { s.base.name },
            description = editDescription.trim(),
            jsonContent = editJson.text,
        )
        withContext(Dispatchers.IO) { WorkflowRepository.upsert(ctx, saved) }
        session = EditorSession(saved, isNew = false)
        editName = saved.name
        editDescription = saved.description
        HabitatStateStore.notifyLibraryChanged()
        // 保存后同步触发器（已启用的工作流改了 trigger 立即生效）
        triggersSynced = false
        return true
    }

    /** 格式化 JSON；语法错误时抛出异常由 UI 提示。 */
    fun formatJson() {
        val pretty = prettyGson.toJson(JsonParser.parseString(editJson.text))
        editJson = TextFieldValue(pretty, TextRange(0))
    }

    fun testRun(json: String) {
        val runId = EDITOR_RUN_ID
        if (HabitatExecutionService.isRunning(runId)) {
            RunLogStore.append(runId, "⚠ 已在运行中，跳过重复启动")
            return
        }
        RunLogStore.reset(runId, "— 开始执行 —")
        viewModelScope.launch(Dispatchers.IO) {
            val factory = NodeHandlerFactory(AppAccessibilityProvider, ShizukuShellExecutor(ctx)).apply { applyAppHandlers(ctx) }
            HabitatExecutionService.start(
                runId, json, ctx, factory,
                onComplete = { RunLogStore.append(runId, "— 执行结束 —") },
                onLog = { line -> RunLogStore.append(runId, line) },
            )
        }
    }

    fun stopTestRun() {
        HabitatExecutionService.stop(EDITOR_RUN_ID)
        RunLogStore.append(EDITOR_RUN_ID, "⚠ 已手动停止")
    }

    fun clearEditorLogs() = RunLogStore.clear(EDITOR_RUN_ID)

    companion object {
        const val EDITOR_RUN_ID = "adhoc_editor"
        private val prettyGson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

        private val lineColRegex = Regex("""line (\d+) column (\d+)""")

        /** 解析 + lint，不抛异常。 */
        fun analyze(json: String, knownTypes: Set<String>?): List<EditorProblem> {
            val graph = try {
                HabitatJson.fromJson(json)
            } catch (e: Exception) {
                val msg = e.message ?: "解析失败"
                val m = lineColRegex.find(msg)
                return listOf(
                    EditorProblem(
                        nodeId = null,
                        message = msg,
                        isError = true,
                        line = m?.groupValues?.get(1)?.toIntOrNull(),
                        column = m?.groupValues?.get(2)?.toIntOrNull(),
                    ),
                )
            }
            return HabitatJson.lint(graph, knownTypes).map {
                EditorProblem(it.nodeId, it.message, it.severity == LintIssue.ERROR)
            }
        }

        /** 定位节点在 JSON 文本中的偏移（"nodeId": { 的 key 位置）。 */
        fun offsetOfNode(json: String, nodeId: String): Int? {
            val key = "\"" + nodeId + "\""
            var idx = json.indexOf(key)
            while (idx >= 0) {
                var j = idx + key.length
                while (j < json.length && json[j].isWhitespace()) j++
                if (j < json.length && json[j] == ':') {
                    var k = j + 1
                    while (k < json.length && json[k].isWhitespace()) k++
                    if (k < json.length && json[k] == '{') return idx
                }
                idx = json.indexOf(key, idx + 1)
            }
            return json.indexOf(key).takeIf { it >= 0 }
        }

        fun offsetOfLine(json: String, line: Int, column: Int): Int {
            var cur = 1
            var i = 0
            while (i < json.length && cur < line) { if (json[i] == '\n') cur++; i++ }
            return (i + (column - 1).coerceAtLeast(0)).coerceAtMost(json.length)
        }

        fun needsCallPermission(json: String): Boolean =
            json.contains("ACTION_CALL_DIRECT") || json.contains("ACTION_CALL_PHONE")
    }
}
