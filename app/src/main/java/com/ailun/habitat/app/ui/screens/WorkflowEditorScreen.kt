package com.ailun.habitat.app.ui.screens

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.material3.Switch
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.automirrored.outlined.FormatAlignLeft
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ailun.habitat.HabitatStateStore
import com.ailun.habitat.app.HabitatTextStyles
import com.ailun.habitat.app.HabitatTheme
import com.ailun.habitat.app.Spacing
import com.ailun.habitat.app.workspace.RunRecords
import com.ailun.habitat.app.dag.DagFocusRequester
import com.ailun.habitat.app.dag.WorkflowDagPanel
import com.ailun.habitat.app.tabular
import com.ailun.habitat.app.ui.components.IconTile
import com.ailun.habitat.app.ui.components.LogConsole
import com.ailun.habitat.app.ui.components.copyToClipboard
import com.ailun.habitat.app.ui.viewmodel.DashboardViewModel
import com.ailun.habitat.app.ui.viewmodel.EditorProblem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class EditorMode { Json, Dag }
private enum class PanelTab { Problems, Logs }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowEditorScreen(
    vm: DashboardViewModel,
    onClose: () -> Unit,
    onTestRun: (String) -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val session = vm.session ?: return

    var mode by rememberSaveable { mutableStateOf(EditorMode.Json) }
    var panelExpanded by rememberSaveable { mutableStateOf(false) }
    var panelTab by rememberSaveable { mutableStateOf(PanelTab.Problems) }
    var headerExpanded by rememberSaveable { mutableStateOf(session.isNew) }
    var showExitPrompt by remember { mutableStateOf(false) }
    var confirmSaveBroken by remember { mutableStateOf(false) }
    val focus = remember { DagFocusRequester() }
    val codeScroll = rememberScrollState()
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var jumpSeq by remember { mutableIntStateOf(0) }
    var jumpOffset by remember { mutableIntStateOf(0) }

    val logs by vm.editorLogs.collectAsStateWithLifecycle()
    val running by HabitatStateStore.running.collectAsStateWithLifecycle()
    val testRunning = DashboardViewModel.EDITOR_RUN_ID in running

    val jsonText = vm.editJson.text
    var problems by remember { mutableStateOf<List<EditorProblem>>(emptyList()) }
    LaunchedEffect(jsonText) {
        delay(300)
        problems = withContext(Dispatchers.Default) { DashboardViewModel.analyze(jsonText, vm.knownTypes) }
    }
    val errorCount = problems.count { it.isError }
    val warningCount = problems.size - errorCount
    val parseBroken = problems.any { it.isError && it.nodeId == null && (it.line != null || it.message.contains("JSON")) }

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty() && testRunning) { panelTab = PanelTab.Logs; panelExpanded = true }
    }

    // 跳转：等 JSON 文本布局就绪后，把目标行滚到视口中部
    LaunchedEffect(jumpSeq) {
        if (jumpSeq == 0) return@LaunchedEffect
        val l = snapshotFlow { layout }.filterNotNull().first()
        val off = jumpOffset.coerceIn(0, l.layoutInput.text.length)
        val top = l.getLineTop(l.getLineForOffset(off))
        codeScroll.animateScrollTo((top - 120f).toInt().coerceAtLeast(0))
    }

    fun jumpTo(offset: Int, length: Int) {
        mode = EditorMode.Json
        vm.editJson = vm.editJson.copy(selection = TextRange(offset, (offset + length).coerceAtMost(vm.editJson.text.length)))
        jumpOffset = offset
        jumpSeq++
    }

    fun onProblemClick(p: EditorProblem) {
        val id = p.nodeId
        if (mode == EditorMode.Dag && id != null) {
            focus.focus(id)
            return
        }
        when {
            id != null -> DashboardViewModel.offsetOfNode(jsonText, id)?.let { jumpTo(it, id.length + 2) }
            p.line != null -> jumpTo(DashboardViewModel.offsetOfLine(jsonText, p.line, p.column ?: 1), 1)
        }
    }

    fun doSave(thenClose: Boolean = false) {
        scope.launch {
            vm.saveEditor()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            } else {
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
            if (thenClose) onClose()
            else snackbar.showSnackbar(if (errorCount > 0) "已保存（仍有 $errorCount 个错误）" else "已保存")
        }
    }

    fun requestSave(thenClose: Boolean = false) {
        if (parseBroken) confirmSaveBroken = true else doSave(thenClose)
    }

    fun requestExit() {
        if (vm.isDirty) showExitPrompt = true else onClose()
    }

    BackHandler(enabled = true) { requestExit() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = ::requestExit) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (session.isNew) "新建工作流" else "编辑工作流")
                        if (vm.isDirty) {
                            Spacer(Modifier.width(Spacing.sm))
                            Box(
                                Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)
                                    .semantics { contentDescription = "有未保存的更改" },
                            )
                        }
                    }
                },
                actions = {
                    if (mode == EditorMode.Json) {
                        IconButton(onClick = {
                            try {
                                vm.formatJson()
                            } catch (e: Exception) {
                                scope.launch { snackbar.showSnackbar("无法格式化：JSON 语法有误") }
                            }
                        }) {
                            Icon(Icons.AutoMirrored.Outlined.FormatAlignLeft, contentDescription = "格式化 JSON")
                        }
                    }
                    IconButton(onClick = { copyToClipboard(context, vm.editJson.text, "工作流 JSON") }) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = "复制 JSON")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .padding(horizontal = Spacing.page),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            EditorHeader(
                name = vm.editName,
                description = vm.editDescription,
                expanded = headerExpanded,
                onExpandedChange = { headerExpanded = it },
                onNameChange = { vm.editName = it },
                onDescriptionChange = { vm.editDescription = it },
                workflowId = vm.session?.base?.id,
            )

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = mode == EditorMode.Json,
                    onClick = { mode = EditorMode.Json },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                    icon = { Icon(Icons.Outlined.Code, contentDescription = null, modifier = Modifier.size(18.dp)) },
                ) { Text("JSON") }
                SegmentedButton(
                    selected = mode == EditorMode.Dag,
                    onClick = { mode = EditorMode.Dag },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                    icon = { Icon(Icons.Outlined.AccountTree, contentDescription = null, modifier = Modifier.size(18.dp)) },
                ) { Text("结构图") }
            }

            AnimatedContent(
                targetState = mode,
                modifier = Modifier.fillMaxWidth().weight(1f),
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "editorMode",
            ) { m ->
                when (m) {
                    EditorMode.Json -> JsonCodeEditor(
                        vm = vm,
                        scroll = codeScroll,
                        onLayout = { layout = it },
                        modifier = Modifier.fillMaxSize(),
                    )
                    EditorMode.Dag -> WorkflowDagPanel(
                        json = jsonText,
                        focusRequester = focus,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            EditorBottomPanel(
                problems = problems,
                errorCount = errorCount,
                warningCount = warningCount,
                logs = logs,
                expanded = panelExpanded,
                tab = panelTab,
                onExpandedChange = { panelExpanded = it },
                onTabChange = { panelTab = it; panelExpanded = true },
                onProblemClick = ::onProblemClick,
                onClearLogs = vm::clearEditorLogs,
            )

            Row(
                Modifier.fillMaxWidth().padding(bottom = Spacing.md),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                FilledTonalButton(
                    onClick = { if (testRunning) vm.stopTestRun() else onTestRun(vm.editJson.text) },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) {
                    if (testRunning) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(Spacing.sm))
                        Icon(Icons.Outlined.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(Spacing.xs))
                        Text("停止")
                    } else {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(Spacing.xs))
                        Text("测试运行")
                    }
                }
                Button(
                    onClick = { requestSave() },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(Spacing.xs))
                    Text("保存")
                }
            }
        }
    }

    if (showExitPrompt) {
        AlertDialog(
            onDismissRequest = { showExitPrompt = false },
            title = { Text("有未保存的更改") },
            text = { Text("离开前要保存这些修改吗？") },
            confirmButton = {
                TextButton(onClick = { showExitPrompt = false; requestSave(thenClose = true) }) { Text("保存并退出") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showExitPrompt = false; onClose() }) {
                        Text("放弃更改", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { showExitPrompt = false }) { Text("继续编辑") }
                }
            },
        )
    }

    if (confirmSaveBroken) {
        AlertDialog(
            onDismissRequest = { confirmSaveBroken = false },
            icon = { Icon(Icons.Outlined.WarningAmber, contentDescription = null) },
            title = { Text("JSON 无法解析") },
            text = { Text("当前内容有语法错误，保存后该工作流无法运行。仍要保存吗？") },
            confirmButton = { TextButton(onClick = { confirmSaveBroken = false; doSave() }) { Text("仍然保存") } },
            dismissButton = { TextButton(onClick = { confirmSaveBroken = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun EditorHeader(
    name: String,
    description: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onNameChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    workflowId: String?,
) {
    val context = LocalContext.current
    var record by remember(workflowId) {
        mutableStateOf(workflowId != null && RunRecords.isWorkflowRecordEnabled(context, workflowId))
    }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        if (expanded) {
            Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedTextField(
                    value = name, onValueChange = onNameChange,
                    label = { Text("名称") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description, onValueChange = onDescriptionChange,
                    label = { Text("描述") }, maxLines = 3, modifier = Modifier.fillMaxWidth(),
                )
                if (workflowId != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("运行结果存到工作区", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "每次运行结束存一份：状态、耗时、日志末尾、输出变量（不含密钥类变量）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = record,
                            onCheckedChange = {
                                record = it
                                RunRecords.setWorkflowRecordEnabled(context, workflowId, it)
                            },
                            modifier = Modifier.semantics { contentDescription = "运行结果存到工作区" },
                        )
                    }
                }
                TextButton(onClick = { onExpandedChange(false) }, modifier = Modifier.align(Alignment.End)) {
                    Icon(Icons.Outlined.ExpandLess, contentDescription = null)
                    Spacer(Modifier.width(Spacing.xs))
                    Text("收起")
                }
            }
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onExpandedChange(true) }
                    .heightIn(min = 56.dp)
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconTile(Icons.Outlined.DataObject, size = 36)
                Spacer(Modifier.width(Spacing.md))
                Column(Modifier.weight(1f)) {
                    Text(name.ifBlank { "未命名" }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        description.ifBlank { "添加描述" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(Icons.Outlined.Edit, contentDescription = "编辑名称和描述", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 等宽 JSON 编辑区：行号 + 不换行横向滚动。 */
@Composable
private fun JsonCodeEditor(
    vm: DashboardViewModel,
    scroll: androidx.compose.foundation.ScrollState,
    onLayout: (TextLayoutResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ext = HabitatTheme.extendedColors
    val value = vm.editJson
    val lineCount = remember(value.text) { value.text.count { it == '\n' } + 1 }
    val numbers = remember(lineCount) { (1..lineCount).joinToString("\n") }
    val codeStyle = HabitatTextStyles.code.copy(color = ext.codeForeground)

    Surface(modifier = modifier, shape = MaterialTheme.shapes.large, color = ext.codeBackground) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val viewportW = maxWidth
            val viewportH = maxHeight
            Row(Modifier.fillMaxSize().verticalScroll(scroll)) {
                Text(
                    numbers,
                    style = codeStyle.copy(color = MaterialTheme.colorScheme.outline, textAlign = TextAlign.End).tabular(),
                    modifier = Modifier
                        .padding(start = Spacing.sm, end = Spacing.sm, top = Spacing.md, bottom = Spacing.md)
                        .widthIn(min = 28.dp),
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .horizontalScroll(rememberScrollState()),
                ) {
                    BasicTextField(
                        value = value,
                        onValueChange = { vm.editJson = it },
                        textStyle = codeStyle,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                        onTextLayout = onLayout,
                        modifier = Modifier
                            .widthIn(min = (viewportW - 48.dp).coerceAtLeast(0.dp))
                            .heightIn(min = viewportH)
                            .padding(end = Spacing.lg, top = Spacing.md, bottom = Spacing.md)
                            .semantics { contentDescription = "工作流 JSON 编辑区" },
                    )
                }
            }
        }
    }
}

@Composable
private fun EditorBottomPanel(
    problems: List<EditorProblem>,
    errorCount: Int,
    warningCount: Int,
    logs: List<String>,
    expanded: Boolean,
    tab: PanelTab,
    onExpandedChange: (Boolean) -> Unit,
    onTabChange: (PanelTab) -> Unit,
    onProblemClick: (EditorProblem) -> Unit,
    onClearLogs: () -> Unit,
) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val height by animateDpAsState(if (expanded) 196.dp else 48.dp, label = "panelH")
    Surface(
        modifier = Modifier.fillMaxWidth().height(height),
        shape = MaterialTheme.shapes.large,
        color = cs.surfaceContainerLow,
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().height(48.dp).padding(horizontal = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PanelTabButton("问题", tab == PanelTab.Problems, if (problems.isEmpty()) null else problems.size, errorCount > 0) {
                    onTabChange(PanelTab.Problems)
                }
                PanelTabButton("日志", tab == PanelTab.Logs, if (logs.isEmpty()) null else logs.size, false) {
                    onTabChange(PanelTab.Logs)
                }
                Spacer(Modifier.weight(1f))
                if (tab == PanelTab.Problems && !expanded) {
                    Text(
                        if (problems.isEmpty()) "无问题" else "$errorCount 错误 · $warningCount 警告",
                        style = MaterialTheme.typography.labelMedium.tabular(),
                        color = if (errorCount > 0) cs.error else cs.onSurfaceVariant,
                    )
                }
                if (tab == PanelTab.Logs && expanded) {
                    IconButton(onClick = { copyToClipboard(context, logs.joinToString("\n"), "日志") }, enabled = logs.isNotEmpty()) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = "复制日志", modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = onClearLogs, enabled = logs.isNotEmpty()) {
                        Icon(Icons.Outlined.DeleteSweep, contentDescription = "清空日志", modifier = Modifier.size(18.dp))
                    }
                }
                IconButton(onClick = { onExpandedChange(!expanded) }) {
                    Icon(
                        if (expanded) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess,
                        contentDescription = if (expanded) "收起面板" else "展开面板",
                    )
                }
            }
            if (expanded) {
                HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.5f))
                when (tab) {
                    PanelTab.Problems -> if (problems.isEmpty()) {
                        Row(
                            Modifier.fillMaxSize().padding(Spacing.lg),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = HabitatTheme.extendedColors.success)
                            Spacer(Modifier.width(Spacing.sm))
                            Text("结构检查通过", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                        }
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(problems) { p -> ProblemRow(p) { onProblemClick(p) } }
                        }
                    }
                    PanelTab.Logs -> LogConsole(
                        lines = logs,
                        modifier = Modifier.fillMaxSize(),
                        showHeader = false,
                        emptyText = "点「测试运行」后日志显示在这里",
                    )
                }
            }
        }
    }
}

@Composable
private fun PanelTabButton(label: String, selected: Boolean, count: Int?, alert: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = Spacing.md),
        modifier = Modifier.semantics { contentDescription = "$label${count?.let { "，$it 项" } ?: ""}" },
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) cs.primary else cs.onSurfaceVariant,
        )
        if (count != null) {
            Spacer(Modifier.width(Spacing.xs))
            Badge(
                containerColor = if (alert) cs.error else cs.surfaceContainerHighest,
                contentColor = if (alert) cs.onError else cs.onSurfaceVariant,
            ) { Text("$count", style = MaterialTheme.typography.labelSmall.tabular()) }
        }
    }
}

@Composable
private fun ProblemRow(p: EditorProblem, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val clickable = p.nodeId != null || p.line != null
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (clickable) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 44.dp)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            if (p.isError) Icons.Outlined.ErrorOutline else Icons.Outlined.WarningAmber,
            contentDescription = if (p.isError) "错误" else "警告",
            tint = if (p.isError) cs.error else HabitatTheme.extendedColors.warning,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(Spacing.sm))
        Column(Modifier.weight(1f)) {
            Text(p.message, style = MaterialTheme.typography.bodySmall)
            val where = p.nodeId ?: p.line?.let { "第 $it 行" }
            if (where != null) {
                Text(where, style = HabitatTextStyles.codeSmall, color = cs.onSurfaceVariant)
            }
        }
    }
}
