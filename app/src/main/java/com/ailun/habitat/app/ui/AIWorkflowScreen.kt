package com.ailun.habitat.app.ui

import android.app.Application
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ailun.habitat.HabitatStateStore
import com.ailun.habitat.HabitatWorkflow
import com.ailun.habitat.NodeHandlerFactory
import com.ailun.habitat.WorkflowRepository
import com.ailun.habitat.app.HabitatTextStyles
import com.ailun.habitat.app.HabitatTheme
import com.ailun.habitat.app.Spacing
import com.ailun.habitat.app.ai.GeneratedWorkflow
import com.ailun.habitat.app.ai.WorkflowAIClient
import com.ailun.habitat.app.ai.WorkflowAIGenerator
import com.ailun.habitat.app.bridge.applyAppHandlers
import com.ailun.habitat.app.dag.DagPreview
import com.ailun.habitat.app.ui.components.CodeBlock
import com.ailun.habitat.app.ui.components.EmptyState
import com.ailun.habitat.app.ui.components.IconTile
import com.ailun.habitat.app.ui.components.SecretField
import com.ailun.habitat.app.ui.components.SkeletonBox
import com.ailun.habitat.app.ui.viewmodel.DashboardViewModel
import com.ailun.habitat.app.ui.viewmodel.EditorProblem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** 一条聊天消息（不可变；保存状态变化时整体替换，保证界面可观察）。 */
data class ChatMsg(
    val isUser: Boolean,
    val text: String,
    val generated: GeneratedWorkflow? = null,
    val saved: Boolean = false,
    val savedId: String? = null, // 保存后的工作流 id（复用同 id 更新，避免重复新增）
    val key: String = UUID.randomUUID().toString(),
)

class AIWorkflowViewModel(app: Application) : AndroidViewModel(app) {
    val messages: SnapshotStateList<ChatMsg> = mutableStateListOf()
    val busy = mutableStateOf(false)
    private var job: Job? = null

    /** 最近一次成功生成的工作流（多轮对话时作为"在现有基础上修改"的基线）。 */
    private var lastGenerated: GeneratedWorkflow? = null

    /** 会话记忆：记录 (用户需求 → 生成结果描述)，供下一轮生成连贯上下文。 */
    private val history = mutableListOf<Pair<String, String>>()

    val knownTypes: Set<String> by lazy {
        NodeHandlerFactory().apply { applyAppHandlers(getApplication()) }.availableTypes() +
            setOf(NodeHandlerFactory.ACTION_FORK, NodeHandlerFactory.ACTION_JOIN)
    }

    fun send(request: String) {
        val req = request.trim()
        if (req.isEmpty() || busy.value) return
        messages.add(ChatMsg(isUser = true, text = req))
        busy.value = true
        job = viewModelScope.launch {
            try {
                val result = WorkflowAIGenerator.generate(getApplication(), req, lastGenerated?.workflowJson, history)
                result.onSuccess { gen ->
                    lastGenerated = gen
                    history.add(req to gen.description)
                    messages.add(ChatMsg(isUser = false, text = "", generated = gen))
                }.onFailure {
                    messages.add(ChatMsg(isUser = false, text = "生成失败：${it.message}"))
                }
            } catch (e: CancellationException) {
                messages.add(ChatMsg(isUser = false, text = "已取消生成"))
                throw e
            } finally {
                busy.value = false
            }
        }
    }

    fun cancel() { job?.cancel() }

    fun latest(key: String): ChatMsg? = messages.firstOrNull { it.key == key }

    private fun replace(msg: ChatMsg, updated: ChatMsg) {
        val idx = messages.indexOfFirst { it.key == msg.key }
        if (idx >= 0) messages[idx] = updated
    }

    /** 编辑后重新验证并保存（编辑态的 JSON 可能不合法）。 */
    fun saveEdited(msg: ChatMsg, editedJson: String): String? {
        val gen = WorkflowAIGenerator.parseAndValidate(editedJson).getOrElse { return null }
        val current = latest(msg.key) ?: msg
        val id = current.savedId ?: UUID.randomUUID().toString()
        WorkflowRepository.upsert(getApplication(), HabitatWorkflow(id = id, name = gen.name, description = gen.description, jsonContent = gen.workflowJson))
        replace(msg, current.copy(generated = gen, saved = true, savedId = id))
        lastGenerated = gen
        HabitatStateStore.notifyLibraryChanged()
        return id
    }

    fun save(msg: ChatMsg): String? {
        val current = latest(msg.key) ?: msg
        val gen = current.generated ?: return null
        val id = current.savedId ?: UUID.randomUUID().toString()
        WorkflowRepository.upsert(getApplication(), HabitatWorkflow(id = id, name = gen.name, description = gen.description, jsonContent = gen.workflowJson))
        replace(msg, current.copy(saved = true, savedId = id))
        lastGenerated = gen
        HabitatStateStore.notifyLibraryChanged()
        return id
    }

    /** 保存并挂载到悬浮窗。 */
    fun saveAndMount(msg: ChatMsg): String? {
        val id = save(msg) ?: return null
        WorkflowRepository.addFloatMountedWorkflowId(getApplication(), id)
        HabitatStateStore.notifyLibraryChanged()
        return id
    }
}

private val EXAMPLE_PROMPTS = listOf(
    "每天早上 8 点提醒我喝水并震动一下",
    "收到含验证码的短信时，把验证码复制到剪贴板",
    "打开微信并进入扫一扫",
    "电量低于 20% 时发一条通知",
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AIWorkflowScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    // Activity 级 ViewModel：切换 tab 后保留对话记忆
    val vm = remember {
        ViewModelProvider(context as androidx.activity.ComponentActivity)[AIWorkflowViewModel::class.java]
    }
    var input by rememberSaveable { mutableStateOf("") }
    var hasKey by remember { mutableStateOf(!WorkflowAIClient.getApiKey(context).isNullOrBlank()) }
    var keyExpanded by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val busy = vm.busy.value

    LaunchedEffect(vm.messages.size, busy) {
        val count = vm.messages.size + if (busy) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }
    BackHandler(onBack = onBack)

    fun submit() {
        if (input.isNotBlank() && !busy) { vm.send(input); input = "" }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("AI 生成")
                        Text("用一句话描述，生成可运行的工作流", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
        ) {
            ApiKeyCard(
                hasKey = hasKey,
                expanded = keyExpanded || !hasKey,
                onExpandedChange = { keyExpanded = it },
                onSaved = { hasKey = it; keyExpanded = false },
                modifier = Modifier.padding(horizontal = Spacing.page, vertical = Spacing.sm),
            )

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = Spacing.page, vertical = Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                if (vm.messages.isEmpty() && !busy) {
                    item(key = "empty") {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                            EmptyState(
                                icon = Icons.Outlined.AutoAwesome,
                                title = "描述你想自动化的事",
                                message = "支持多轮修改：生成后继续说「改成每 2 小时一次」即可在原基础上调整。",
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.CenterHorizontally),
                                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                EXAMPLE_PROMPTS.forEach { p ->
                                    AssistChip(onClick = { input = p }, label = { Text(p, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                                }
                            }
                        }
                    }
                }
                items(vm.messages, key = { it.key }) { msg ->
                    when {
                        msg.isUser -> UserBubble(msg.text)
                        msg.generated != null -> GeneratedCard(msg, vm)
                        else -> AssistantNote(msg.text)
                    }
                }
                if (busy) {
                    item(key = "busy") { GeneratingCard(onCancel = vm::cancel) }
                }
            }

            Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(horizontal = Spacing.page, vertical = Spacing.sm),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(if (vm.messages.isEmpty()) "描述你的需求…" else "继续修改，例如「再加一个震动」") },
                        maxLines = 5,
                        shape = MaterialTheme.shapes.extraLarge,
                        enabled = hasKey,
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    if (busy) {
                        FilledIconButton(onClick = vm::cancel, modifier = Modifier.size(56.dp)) {
                            Icon(Icons.Outlined.Stop, contentDescription = "取消生成")
                        }
                    } else {
                        FilledIconButton(onClick = ::submit, enabled = input.isNotBlank() && hasKey, modifier = Modifier.size(56.dp)) {
                            Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "生成")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ApiKeyCard(
    hasKey: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSaved: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var key by remember { mutableStateOf(WorkflowAIClient.getApiKey(context).orEmpty()) }
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth().animateContentSize(),
        shape = MaterialTheme.shapes.large,
        color = if (hasKey) cs.surfaceContainerLow else HabitatTheme.extendedColors.warningContainer,
    ) {
        Column(Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (hasKey) Icons.Outlined.CheckCircle else Icons.Outlined.Key,
                    contentDescription = null,
                    tint = if (hasKey) HabitatTheme.extendedColors.success else HabitatTheme.extendedColors.onWarningContainer,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    if (hasKey) "DeepSeek 已配置" else "需要 DeepSeek API Key 才能生成",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (hasKey) cs.onSurface else HabitatTheme.extendedColors.onWarningContainer,
                    modifier = Modifier.weight(1f),
                )
                if (hasKey) {
                    IconButton(onClick = { onExpandedChange(!expanded) }) {
                        Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = if (expanded) "收起密钥设置" else "修改 API Key")
                    }
                }
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(bottom = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    SecretField(
                        value = key,
                        onValueChange = { key = it },
                        label = "DeepSeek API Key",
                        placeholder = "sk-…",
                        modifier = Modifier.fillMaxWidth(),
                        supportingText = "密钥加密保存在本机，仅用于请求 DeepSeek",
                    )
                    Button(
                        onClick = {
                            WorkflowAIClient.setApiKey(context, key.trim())
                            onSaved(key.isNotBlank())
                            Toast.makeText(context, "已保存 API Key", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.align(Alignment.End),
                    ) { Text("保存") }
                }
            }
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Text(
                text,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun AssistantNote(text: String) {
    val isError = text.startsWith("生成失败")
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
            if (isError) {
                Icon(Icons.Outlined.ErrorOutline, contentDescription = "错误", tint = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(Modifier.width(Spacing.sm))
            }
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GeneratingCard(onCancel: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "正在生成" },
    ) {
        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(Spacing.sm))
                Text("正在生成工作流…", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onCancel) { Text("取消") }
            }
            SkeletonBox(width = 200.dp, height = 16.dp)
            SkeletonBox(height = 12.dp)
            SkeletonBox(width = 240.dp, height = 12.dp)
            SkeletonBox(height = 120.dp, shape = MaterialTheme.shapes.medium)
        }
    }
}

@Composable
private fun GeneratedCard(msg: ChatMsg, vm: AIWorkflowViewModel) {
    val context = LocalContext.current
    val gen = msg.generated ?: return
    var editing by rememberSaveable(msg.key) { mutableStateOf(false) }
    var editJson by rememberSaveable(msg.key) { mutableStateOf("") }
    var editError by remember { mutableStateOf<String?>(null) }
    var showJson by rememberSaveable(msg.key) { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme

    val problems by produceState<List<EditorProblem>?>(null, gen.workflowJson) {
        value = withContext(Dispatchers.Default) { DashboardViewModel.analyze(gen.workflowJson, vm.knownTypes) }
    }

    Surface(shape = MaterialTheme.shapes.large, color = cs.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(Icons.Outlined.AutoAwesome, size = 36)
                Spacer(Modifier.width(Spacing.md))
                Column(Modifier.weight(1f)) {
                    Text(gen.name, style = MaterialTheme.typography.titleMedium)
                    if (msg.saved) {
                        Text("已保存到工作流库", style = MaterialTheme.typography.labelMedium, color = HabitatTheme.extendedColors.success)
                    }
                }
            }
            if (gen.description.isNotBlank()) {
                Text(gen.description, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            }

            LintSummary(problems)

            if (editing) {
                OutlinedTextField(
                    value = editJson,
                    onValueChange = { editJson = it; editError = null },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 320.dp),
                    textStyle = HabitatTextStyles.codeSmall,
                    label = { Text("编辑 JSON（保存前重新校验）") },
                    isError = editError != null,
                    supportingText = editError?.let { { Text(it) } },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.align(Alignment.End)) {
                    TextButton(onClick = { editing = false }) { Text("取消") }
                    Button(onClick = {
                        vm.saveEdited(msg, editJson)?.let {
                            Toast.makeText(context, "已保存编辑", Toast.LENGTH_SHORT).show()
                            editing = false
                        } ?: run { editError = "JSON 校验失败，请检查格式" }
                    }) { Text("保存编辑") }
                }
            } else {
                DagPreview(gen.workflowJson, Modifier.fillMaxWidth().height(220.dp))
                TextButton(onClick = { showJson = !showJson }) {
                    Icon(if (showJson) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null)
                    Spacer(Modifier.width(Spacing.xs))
                    Text(if (showJson) "收起 JSON" else "查看 JSON")
                }
                AnimatedVisibility(showJson) { CodeBlock(gen.workflowJson, title = "workflow.json", maxHeight = 280.dp) }

                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { vm.save(msg)?.let { Toast.makeText(context, "已保存到工作流库", Toast.LENGTH_SHORT).show() } },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.LibraryAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(Spacing.xs))
                        Text(if (msg.saved) "更新" else "保存到库")
                    }
                    FilledTonalButton(
                        onClick = { vm.saveAndMount(msg)?.let { Toast.makeText(context, "已保存并挂载到悬浮窗", Toast.LENGTH_SHORT).show() } },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.PushPin, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(Spacing.xs))
                        Text("挂载悬浮窗")
                    }
                }
                OutlinedButton(onClick = { editing = true; editJson = gen.workflowJson }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(Spacing.xs))
                    Text("编辑 JSON")
                }
            }
        }
    }
}

@Composable
private fun LintSummary(problems: List<EditorProblem>?) {
    val cs = MaterialTheme.colorScheme
    val ext = HabitatTheme.extendedColors
    if (problems == null) return
    val errors = problems.count { it.isError }
    val warnings = problems.size - errors
    val (bg, fg, icon, text) = when {
        errors > 0 -> Quad(cs.errorContainer, cs.onErrorContainer, Icons.Outlined.ErrorOutline, "$errors 个错误 · $warnings 个警告")
        warnings > 0 -> Quad(ext.warningContainer, ext.onWarningContainer, Icons.Outlined.WarningAmber, "$warnings 个警告")
        else -> Quad(ext.successContainer, ext.onSuccessContainer, Icons.Outlined.CheckCircle, "结构检查通过")
    }
    Surface(shape = MaterialTheme.shapes.medium, color = bg, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.sm))
                Text(text, style = MaterialTheme.typography.labelLarge, color = fg)
            }
            problems.take(4).forEach { p ->
                Text("· ${p.nodeId?.let { "[$it] " } ?: ""}${p.message}", style = MaterialTheme.typography.bodySmall, color = fg)
            }
            if (problems.size > 4) Text("… 还有 ${problems.size - 4} 项", style = MaterialTheme.typography.bodySmall, color = fg)
        }
    }
}

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
