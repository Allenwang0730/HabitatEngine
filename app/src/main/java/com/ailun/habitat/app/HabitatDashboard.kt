package com.ailun.habitat.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.ailun.habitat.HabitatExecutionService
import com.ailun.habitat.HabitatJson
import com.ailun.habitat.HabitatSamples
import com.ailun.habitat.HabitatStateStore
import com.ailun.habitat.HabitatWorkflow
import com.ailun.habitat.NodeHandlerFactory
import com.ailun.habitat.TriggerConfig
import com.ailun.habitat.WorkflowRepository
import com.ailun.habitat.ai.ILLMService
import com.ailun.habitat.R
import com.ailun.habitat.app.ai.LLMServiceProvider
import com.ailun.habitat.app.bridge.AppAccessibilityProvider
import com.ailun.habitat.app.bridge.ShizukuShellExecutor
import com.ailun.habitat.app.bridge.applyAppHandlers
import com.ailun.habitat.app.dag.DagGraph
import com.ailun.habitat.app.dag.DagLayoutEngine
import com.ailun.habitat.app.dag.DagView
import com.ailun.habitat.app.ui.ProfileTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ── Bottom navigation tabs ──
private enum class Tab(val label: String) { Workflows("工作流"), Editor("编辑器"), Profile("个人主页") }

@Composable
fun HabitatDashboard(activity: ComponentActivity) {
    MaterialTheme(colorScheme = habitatColorScheme()) {
        var selectedTab by remember { mutableStateOf(Tab.Workflows) }

        // ── Shared state ──
        var workflows by remember { mutableStateOf<List<HabitatWorkflow>>(emptyList()) }
        var editingBase by remember { mutableStateOf<HabitatWorkflow?>(null) }
        var editName by remember { mutableStateOf("") }
        var editDescription by remember { mutableStateOf("") }
        var editJson by remember { mutableStateOf("") }
        val editLogs = remember { mutableStateListOf<String>() }
        val libraryLogs = remember { mutableStateListOf<String>() }
        val runningStates by HabitatStateStore.runningStates.collectAsState()
        val floatActiveFromStore by HabitatStateStore.floatServiceActive.collectAsState()
        var floatServiceRunning by remember { mutableStateOf(HabitatFloatService.isRunning) }

        // Sync float switch state from HabitatStateStore (drag-to-close from float window)
        LaunchedEffect(floatActiveFromStore) {
            floatServiceRunning = floatActiveFromStore
        }

        val overlayPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            if (Settings.canDrawOverlays(activity)) {
                HabitatFloatService.start(activity)
                FloatWindowManager.getInstance(activity.application).showFloatWindow()
                Toast.makeText(activity, "悬浮窗已开启", Toast.LENGTH_SHORT).show()
            }
        }

        fun syncTriggers() {
            val ctx = activity.applicationContext
            val registered = TriggerManager.registeredWorkflowIds()
            val current = workflows.mapNotNull { wf ->
                val graph = try { HabitatJson.fromJson(wf.jsonContent) } catch (_: Exception) { null }
                val config = graph?.triggerConfig()
                if (config != null) wf.id to config else null
            }
            val currentIds = current.map { it.first }.toSet()

            for (oldId in registered) {
                if (oldId !in currentIds || !TriggerManager.isWorkflowEnabled(ctx, oldId)) {
                    TriggerManager.unregister(oldId, ctx)
                }
            }
            for ((id, config) in current) {
                val wf = workflows.find { it.id == id } ?: continue
                if (!TriggerManager.isWorkflowEnabled(ctx, id)) continue
                if (id !in registered) {
                    TriggerManager.register(id, config, wf.jsonContent, ctx)
                } else if (TriggerManager.triggerConfigFor(id) != config) {
                    TriggerManager.unregister(id, ctx)
                    TriggerManager.register(id, config, wf.jsonContent, ctx)
                }
            }
        }

        fun reloadLibrary() {
            workflows = WorkflowRepository.getAll(activity)
            HabitatStateStore.notifyLibraryChanged()
            syncTriggers()
        }

        LaunchedEffect(Unit) { reloadLibrary() }

        val mountedIds = remember { mutableStateOf(WorkflowRepository.getFloatMountedWorkflowIds(activity)) }
        LaunchedEffect(workflows) {
            mountedIds.value = WorkflowRepository.getFloatMountedWorkflowIds(activity)
        }

        fun ensureFloatServiceForMount() {
            if (!Settings.canDrawOverlays(activity)) {
                overlayPermissionLauncher.launch(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${activity.packageName}"))
                )
                return
            }
            HabitatFloatService.start(activity) // service onCreate → showFloatWindow
            floatServiceRunning = true
            HabitatStateStore.setFloatServiceActive(true)
            Toast.makeText(activity, "悬浮窗已开启", Toast.LENGTH_SHORT).show()
        }

        fun turnOffFloatService() {
            HabitatFloatService.stop(activity)
            FloatWindowManager.getInstanceOrNull()?.hideFloatWindow()
            floatServiceRunning = false
            HabitatStateStore.setFloatServiceActive(false)
            Toast.makeText(activity, "悬浮窗已关闭", Toast.LENGTH_SHORT).show()
        }

        fun toggleMountWorkflow(wf: HabitatWorkflow) {
            if (WorkflowRepository.isFloatMounted(activity, wf.id)) {
                WorkflowRepository.removeFloatMountedWorkflowId(activity, wf.id)
            } else {
                WorkflowRepository.addFloatMountedWorkflowId(activity, wf.id)
                ensureFloatServiceForMount()
            }
            mountedIds.value = WorkflowRepository.getFloatMountedWorkflowIds(activity)
            HabitatStateStore.notifyLibraryChanged()
        }

        fun runGraphJson(json: String, targetLogs: MutableList<String>, workflowId: String? = null) {
            val id = workflowId ?: "adhoc_${System.currentTimeMillis()}"
            activity.lifecycleScope.launch(Dispatchers.IO) {
                if (workflowId != null && !TriggerManager.isWorkflowEnabled(activity.applicationContext, workflowId)) {
                    withContext(Dispatchers.Main) { targetLogs.add("工作流已禁用，无法执行") }
                    return@launch
                }
                if (HabitatExecutionService.isRunning(id)) {
                    withContext(Dispatchers.Main) { targetLogs.add("已在运行中，跳过重复启动") }
                    return@launch
                }
                withContext(Dispatchers.Main) { targetLogs.clear(); targetLogs.add("— 开始执行 —") }
                val factory = NodeHandlerFactory(AppAccessibilityProvider, ShizukuShellExecutor(activity.applicationContext)).apply {
                    applyAppHandlers(activity.applicationContext)
                }
                HabitatExecutionService.start(id, json, activity.applicationContext, factory) { line ->
                    activity.lifecycleScope.launch(Dispatchers.Main.immediate) { targetLogs.add(line) }
                }
            }
        }

        // ── Main scaffold with bottom nav ──
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                HabitatBottomBar(selectedTab = selectedTab, onTabSelected = { selectedTab = it })
            }
        ) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding)) {
                when (selectedTab) {
                    Tab.Workflows -> WorkflowsTab(
                        workflows = workflows,
                        libraryLogs = libraryLogs,
                        mountedIds = mountedIds.value,
                        runningStates = runningStates,
                        onToggleWorkflow = { wf, enabled ->
                            val config = try { HabitatJson.fromJson(wf.jsonContent).triggerConfig() } catch (_: Exception) { null }
                            TriggerManager.setWorkflowEnabled(activity.applicationContext, wf.id, enabled, config, wf.jsonContent)
                        },
                        onToggleMount = ::toggleMountWorkflow,
                        onEditWorkflow = { wf ->
                            editingBase = wf; editName = wf.name; editDescription = wf.description
                            editJson = wf.jsonContent; editLogs.clear(); selectedTab = Tab.Editor
                        },
                        onNewWorkflow = {
                            editingBase = HabitatWorkflow(
                                name = "新工作流", description = "",
                                jsonContent = HabitatSamples.MVP_THREE_NODE.trimIndent()
                            )
                            editName = editingBase!!.name; editDescription = editingBase!!.description
                            editJson = editingBase!!.jsonContent; editLogs.clear(); selectedTab = Tab.Editor
                        },
                        onDeleteWorkflow = { wf ->
                            TriggerManager.setWorkflowEnabled(activity.applicationContext, wf.id, false)
                            WorkflowRepository.delete(activity, wf.id)
                            reloadLibrary()
                        },
                        onFloatServiceToggle = { on ->
                            if (on) ensureFloatServiceForMount() else turnOffFloatService()
                        },
                        isFloatServiceRunning = floatServiceRunning,
                    )

                    Tab.Editor -> EditorTab(
                        name = editName, description = editDescription, jsonBody = editJson, logs = editLogs,
                        onNameChange = { editName = it }, onDescriptionChange = { editDescription = it },
                        onJsonChange = { editJson = it },
                        onBack = { selectedTab = Tab.Workflows },
                        onSave = {
                            val base = editingBase ?: return@EditorTab
                            val saved = base.copy(name = editName.trim().ifEmpty { base.name }, description = editDescription.trim(), jsonContent = editJson)
                            WorkflowRepository.upsert(activity, saved); editingBase = saved; reloadLibrary()
                        },
                        onTestRun = {
                            runGraphJson(editJson, editLogs, editingBase?.id)
                        },
                    )

                    Tab.Profile -> {
                        var showLLM by remember { mutableStateOf(false) }
                        if (showLLM) {
                            HabitatLLMConsoleContent(activity = activity, onBack = { showLLM = false })
                        } else {
                            ProfileTab(
                                isFloatServiceRunning = floatServiceRunning,
                                onFloatServiceToggle = { on ->
                                    if (on) ensureFloatServiceForMount() else turnOffFloatService()
                                },
                                onNavigateToLLM = { showLLM = true },
                                onNavigateToPermissions = {
                                    activity.startActivity(Intent(activity, com.ailun.habitat.app.ui.HabitatSettingsActivity::class.java))
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Bottom Navigation Bar ──

@Composable
private fun HabitatBottomBar(selectedTab: Tab, onTabSelected: (Tab) -> Unit) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp
    ) {
        NavigationBarItem(
            selected = selectedTab == Tab.Workflows,
            onClick = { onTabSelected(Tab.Workflows) },
            icon = {
                Icon(
                    if (selectedTab == Tab.Workflows) Icons.Filled.AccountTree else Icons.Outlined.AccountTree,
                    contentDescription = null
                )
            },
            label = { Text("工作流") }
        )
        NavigationBarItem(
            selected = selectedTab == Tab.Editor,
            onClick = { onTabSelected(Tab.Editor) },
            icon = {
                Icon(
                    if (selectedTab == Tab.Editor) Icons.Filled.Edit else Icons.Outlined.Edit,
                    contentDescription = null
                )
            },
            label = { Text("编辑器") }
        )
        NavigationBarItem(
            selected = selectedTab == Tab.Profile,
            onClick = { onTabSelected(Tab.Profile) },
            icon = {
                Icon(
                    if (selectedTab == Tab.Profile) Icons.Filled.Person else Icons.Outlined.Person,
                    contentDescription = null
                )
            },
            label = { Text("个人主页") }
        )
    }
}

// ── Tab 1: Workflows Library ──

@Composable
private fun WorkflowsTab(
    workflows: List<HabitatWorkflow>,
    libraryLogs: MutableList<String>,
    mountedIds: Set<String>,
    runningStates: Map<String, HabitatStateStore.WorkflowRunState>,
    onToggleWorkflow: (HabitatWorkflow, Boolean) -> Unit,
    onToggleMount: (HabitatWorkflow) -> Unit,
    onEditWorkflow: (HabitatWorkflow) -> Unit,
    onNewWorkflow: () -> Unit,
    onDeleteWorkflow: (HabitatWorkflow) -> Unit,
    onFloatServiceToggle: (Boolean) -> Unit,
    isFloatServiceRunning: Boolean,
) {
    val ctx = LocalContext.current.applicationContext
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        // Header
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 1.dp
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Habitat", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("脚本中枢", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = isFloatServiceRunning,
                        onCheckedChange = onFloatServiceToggle,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    Text(
                        "悬浮窗", style = MaterialTheme.typography.bodySmall,
                        color = if (isFloatServiceRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(onClick = onNewWorkflow, contentPadding = PaddingValues(horizontal = 12.dp)) {
                        Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("新建")
                    }
                }
            }
        }

        // Workflow list
        if (workflows.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Outlined.AccountTree, null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("还没有工作流", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text("点击右上角「新建」创建第一个脚本", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(workflows, key = { it.id }) { wf ->
                    val isRunning = runningStates[wf.id]?.isRunning == true
                    val isMounted = mountedIds.contains(wf.id)
                    val triggerConfig = remember(wf.jsonContent) {
                        try { HabitatJson.fromJson(wf.jsonContent).triggerConfig() } catch (_: Exception) { null }
                    }
                    val hasTrigger = triggerConfig != null
                    // Triggered: show enabled state. Non-triggered: show running state.
                    val switchOn = if (hasTrigger)
                        remember(wf.id) { mutableStateOf(TriggerManager.isWorkflowEnabled(ctx, wf.id)) }
                    else
                        remember(wf.id) { mutableStateOf(isRunning) }

                    // Keep non-triggered switch in sync with running state
                    if (!hasTrigger) {
                        LaunchedEffect(isRunning) { switchOn.value = isRunning }
                    }

                    WorkflowCard(
                        wf = wf, isRunning = isRunning, isMounted = isMounted,
                        hasTrigger = hasTrigger, triggerType = triggerConfig?.type,
                        switchOn = switchOn.value,
                        onToggle = { checked ->
                            switchOn.value = checked
                            onToggleWorkflow(wf, checked)
                        },
                        onMountToggle = { onToggleMount(wf) },
                        onEdit = { onEditWorkflow(wf) },
                        onDelete = { onDeleteWorkflow(wf) }
                    )
                }
                // Logs
                if (libraryLogs.isNotEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("最近执行日志", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(6.dp))
                                libraryLogs.takeLast(8).forEach { line ->
                                    Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 16.sp)
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(88.dp)) }
            }
        }
    }
}

@Composable
private fun WorkflowCard(
    wf: HabitatWorkflow, isRunning: Boolean, isMounted: Boolean,
    hasTrigger: Boolean = false, triggerType: String? = null,
    switchOn: Boolean = false,
    onToggle: ((Boolean) -> Unit)? = null,
    onMountToggle: (() -> Unit)? = null,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(8.dp).clip(CircleShape).background(
                        when { isRunning -> Color(0xFF22C55E); switchOn -> Color(0xFFF59E0B); else -> Color.Gray.copy(alpha = 0.3f) }
                    )
                )
                Spacer(Modifier.width(8.dp))
                Text(wf.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (isRunning) {
                    Surface(shape = RoundedCornerShape(6.dp), color = Color(0xFF22C55E).copy(alpha = 0.12f)) {
                        Text("运行中", modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp), fontSize = 11.sp, color = Color(0xFF16A34A), fontWeight = FontWeight.SemiBold)
                    }
                }
                if (hasTrigger && switchOn) {
                    Spacer(Modifier.width(6.dp))
                    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
                        Text(
                            triggerLabel(triggerType),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.tertiary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                if (isMounted) {
                    Spacer(Modifier.width(6.dp))
                    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                        Text("悬浮", modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            if (wf.description.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(wf.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                // Status label
                Text(
                    when { isRunning -> "● 运行中"; switchOn -> if (hasTrigger) "○ 等待触发" else "○ 运行中"; else -> "已停止" },
                    style = MaterialTheme.typography.bodySmall,
                    color = when { isRunning -> Color(0xFF16A34A); switchOn -> Color(0xFFF59E0B); else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f) },
                    modifier = Modifier.weight(1f),
                )
                // Switch as sole control
                if (onToggle != null) {
                    Switch(checked = switchOn, onCheckedChange = onToggle)
                }
                OutlinedButton(onClick = onEdit, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp), modifier = Modifier.height(34.dp)) {
                    Text("编辑", fontSize = 13.sp)
                }
                if (onMountToggle != null) {
                    IconButton(onClick = onMountToggle, modifier = Modifier.size(34.dp)) {
                        Icon(
                            if (isMounted) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = if (isMounted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Outlined.Delete, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

// ── Tab 2: Editor ──

@Composable
private fun EditorTab(
    name: String, description: String, jsonBody: String, logs: MutableList<String>,
    onNameChange: (String) -> Unit, onDescriptionChange: (String) -> Unit,
    onJsonChange: (String) -> Unit, onBack: () -> Unit,
    onSave: () -> Unit, onTestRun: () -> Unit,
) {
    var showDag by remember { mutableStateOf(false) }
    var dagError by remember { mutableStateOf<String?>(null) }
    var dagNeedsRefresh by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val dagViewRef = remember { mutableStateOf<DagView?>(null) }

    fun buildDag(): DagGraph? {
        return try {
            val graph = HabitatJson.fromJson(jsonBody)
            val dagGraph = HabitatDagConverter.convert(graph)
            DagLayoutEngine.layout(dagGraph, density.density)
            dagError = null
            dagGraph
        } catch (e: Exception) {
            dagError = e.message ?: "Unknown error"
            null
        }
    }

    fun refreshDag() {
        val dagGraph = buildDag()
        if (dagGraph != null) {
            dagViewRef.value?.setGraph(dagGraph)
        }
    }

    // Auto-initialize DAG when toggled or when jsonBody changes in DAG mode
    LaunchedEffect(showDag, dagNeedsRefresh) {
        if (showDag) {
            // Small delay to allow AndroidView factory to complete
            kotlinx.coroutines.delay(100)
            val dagGraph = buildDag()
            dagViewRef.value?.let { view ->
                if (dagGraph != null) view.setGraph(dagGraph)
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp)
    ) {
        // Top bar
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "返回") }
            Text("工作流编辑器", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { showDag = !showDag }) {
                Text(if (showDag) "JSON" else "流程图")
            }
        }

        OutlinedTextField(value = name, onValueChange = onNameChange, modifier = Modifier.fillMaxWidth(), label = { Text("名称") }, singleLine = true)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(value = description, onValueChange = onDescriptionChange, modifier = Modifier.fillMaxWidth(), label = { Text("描述") })
        Spacer(Modifier.height(10.dp))

        if (showDag) {
            // Toolbar: refresh + error
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (dagError != null) {
                    Text(
                        dagError!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                IconButton(
                    onClick = { dagNeedsRefresh = !dagNeedsRefresh },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Default.Refresh, "刷新流程图", modifier = Modifier.size(20.dp))
                }
            }
            AndroidView(
                factory = { ctx -> DagView(ctx).also { dagViewRef.value = it } },
                modifier = Modifier.fillMaxWidth().weight(1f).clipToBounds().background(MaterialTheme.colorScheme.surfaceVariant)
            )
        } else {
            OutlinedTextField(
                value = jsonBody, onValueChange = { onJsonChange(it) },
                modifier = Modifier.fillMaxWidth().weight(1f),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            )
        }

        Spacer(Modifier.height(10.dp))

        // Action buttons
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onSave, modifier = Modifier.weight(1f)) { Text("保存") }
            OutlinedButton(onClick = onTestRun, modifier = Modifier.weight(1f)) { Text("测试运行") }
        }

        // Logs
        if (logs.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("执行日志", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(0.3f)) {
                items(logs) { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 16.sp)
                }
            }
        }
    }
}

private fun triggerLabel(type: String?): String = when (type) {
    TriggerConfig.TYPE_NOTIFICATION -> "通知触发"
    TriggerConfig.TYPE_SMS -> "短信触发"
    TriggerConfig.TYPE_TIMER -> "定时触发"
    TriggerConfig.TYPE_CLIPBOARD -> "剪贴板触发"
    TriggerConfig.TYPE_KEY -> "按键触发"
    else -> "触发"
}

// ── LLM Console (extracted for reuse) ──

@Composable
private fun HabitatLLMConsoleContent(activity: ComponentActivity, onBack: () -> Unit) {
    var llmService by remember { mutableStateOf<ILLMService?>(null) }
    var modelPath by remember { mutableStateOf("/storage/emulated/0/Download/llm/gemma4_2b_v09_obfus_fix_all_modalities_thinking.litertlm") }
    var isLoading by remember { mutableStateOf(false) }
    var prompt by remember { mutableStateOf("") }
    var response by remember { mutableStateOf("") }

    fun checkAndRequestStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) return true
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:${activity.packageName}")
            }
            activity.startActivity(intent)
            Toast.makeText(activity, "请开启所有文件管理权限后重试", Toast.LENGTH_LONG).show()
            return false
        }
        return true
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()) {
            val service = LLMServiceProvider.get(activity)
            llmService = service
            service.getModelPath()?.let { modelPath = it }
        }
    }

    val isReady = llmService?.isReady() ?: false

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "返回") }
            Text("LLM 控制台", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("模型配置", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = modelPath, onValueChange = { modelPath = it }, label = { Text("模型路径") }, modifier = Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                    Button(
                        onClick = {
                            activity.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply { data = Uri.parse("package:${activity.packageName}") })
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) { Text("授予文件访问权限") }
                    Spacer(Modifier.height(8.dp))
                }

                Button(
                    onClick = {
                        if (checkAndRequestStoragePermission()) {
                            val service = llmService ?: LLMServiceProvider.get(activity).also { llmService = it }
                            isLoading = true
                            activity.lifecycleScope.launch {
                                val result = service.loadModel(modelPath)
                                isLoading = false
                                if (result.isSuccess) Toast.makeText(activity, "模型加载成功", Toast.LENGTH_SHORT).show()
                                else Toast.makeText(activity, "加载失败: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isReady) "重新加载" else "加载模型")
                }
                if (isReady) {
                    Text("状态: 已就绪", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Card(modifier = Modifier.fillMaxWidth().weight(1f)) {
            Column(Modifier.padding(16.dp)) {
                Text("推理测试", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = prompt, onValueChange = { prompt = it }, label = { Text("输入 Prompt") }, modifier = Modifier.fillMaxWidth().height(80.dp))
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        val service = llmService ?: return@Button
                        isLoading = true
                        activity.lifecycleScope.launch { response = service.chat(prompt); isLoading = false }
                    },
                    enabled = isReady && !isLoading && prompt.isNotBlank(),
                    modifier = Modifier.align(Alignment.End)
                ) { Text("发送") }
                Spacer(Modifier.height(8.dp))
                Text("回答:", style = MaterialTheme.typography.labelMedium)
                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f).padding(4.dp)) {
                    item { Text(response, style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
    }
}
