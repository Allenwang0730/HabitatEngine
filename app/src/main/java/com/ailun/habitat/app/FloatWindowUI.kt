package com.ailun.habitat.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ailun.habitat.HabitatJson
import com.ailun.habitat.HabitatStateStore
import com.ailun.habitat.HabitatWorkflow
import com.ailun.habitat.app.ui.components.LogConsole
import com.ailun.habitat.app.ui.components.PillStatus
import com.ailun.habitat.app.ui.components.StatusDot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 悬浮窗界面。自身包一层 [HabitatTheme]（悬浮窗不在 Activity 里，保证令牌一致）；
 * 运行/启用状态直接订阅 [HabitatStateStore.running] 与 [TriggerManager.enabledFlow]，与主界面实时同步。
 * 参数 activeJobIds / enabledWorkflowIds 保留兼容，与实时状态取并集。
 */
@Composable
internal fun FloatWindowUI(
    isExpanded: Boolean,
    inCloseZone: Boolean,
    workflows: List<HabitatWorkflow>,
    activeJobIds: Set<String>,
    enabledWorkflowIds: Set<String> = emptySet(),
    executionLogs: List<String> = emptyList(),
    onDismiss: () -> Unit,
    onWorkflowSelected: (HabitatWorkflow) -> Unit,
    onWorkflowStop: (HabitatWorkflow) -> Unit,
    onWorkflowToggle: ((HabitatWorkflow, Boolean) -> Unit)? = null,
) {
    HabitatTheme {
        val running by HabitatStateStore.running.collectAsState()
        val enabled by TriggerManager.enabledFlow.collectAsState()
        val liveRunning = running + activeJobIds
        val liveEnabled = enabled.filterTo(HashSet()) { id -> workflows.any { it.id == id } } + enabledWorkflowIds

        Box(Modifier.fillMaxSize()) {
            if (isExpanded) {
                WorkflowSelectionPanel(
                    workflows, liveRunning, liveEnabled, executionLogs,
                    onWorkflowSelected, onWorkflowStop, onWorkflowToggle, onDismiss,
                )
            } else {
                FloatBallContent(inCloseZone, anyRunning = workflows.any { wf -> isRunningId(wf.id, liveRunning) })
            }
        }
    }
}

private fun isRunningId(id: String, running: Set<String>) = id in running || running.any { it.startsWith(id + "_") }

@Composable
private fun FloatBallContent(inCloseZone: Boolean, anyRunning: Boolean) {
    val cs = MaterialTheme.colorScheme
    val base = if (inCloseZone) cs.error else cs.primary
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(2.dp)
                .clip(CircleShape)
                .background(Brush.verticalGradient(listOf(lerp(base, Color.White, 0.12f), lerp(base, Color.Black, 0.18f))))
                .semantics { contentDescription = if (inCloseZone) "松手关闭悬浮窗" else "Habitat 悬浮球" },
            contentAlignment = Alignment.Center,
        ) {
            if (inCloseZone) {
                Icon(Icons.Outlined.Close, contentDescription = null, tint = cs.onError)
            } else {
                Text("H", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = cs.onPrimary)
            }
        }
        if (anyRunning && !inCloseZone) {
            Box(Modifier.align(Alignment.TopEnd).padding(2.dp)) { StatusDot(PillStatus.Running, size = 10) }
        }
    }
}

@Composable
internal fun WorkflowSelectionPanel(
    workflows: List<HabitatWorkflow>,
    activeJobIds: Set<String>,
    enabledWorkflowIds: Set<String> = emptySet(),
    executionLogs: List<String> = emptyList(),
    onWorkflowSelected: (HabitatWorkflow) -> Unit,
    onWorkflowStop: (HabitatWorkflow) -> Unit,
    onWorkflowToggle: ((HabitatWorkflow, Boolean) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    // 哪些工作流带触发器（带触发器的显示开关，手动的显示运行按钮）；解析放后台
    val triggered by produceState(emptySet<String>(), workflows) {
        value = withContext(Dispatchers.Default) {
            workflows.filter { wf ->
                runCatching { HabitatJson.fromJson(wf.jsonContent).triggerConfig() != null }.getOrDefault(false)
            }.mapTo(HashSet()) { it.id }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        shape = MaterialTheme.shapes.extraLarge,
        color = cs.surfaceContainer,
        tonalElevation = 3.dp,
    ) {
        Column(Modifier.fillMaxSize()) {
            // 标题栏（高 48dp：FloatWindowManager 以此区域作为拖动把手）
            Row(
                Modifier.fillMaxWidth().height(48.dp).padding(start = Spacing.lg, end = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(22.dp).clip(MaterialTheme.shapes.extraSmall).background(cs.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("H", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = cs.onPrimary)
                }
                Spacer(Modifier.width(Spacing.sm))
                Text("Habitat", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                val runningCount = workflows.count { isRunningId(it.id, activeJobIds) }
                if (runningCount > 0) {
                    Text(
                        "$runningCount 运行中",
                        style = MaterialTheme.typography.labelMedium.tabular(),
                        color = HabitatTheme.extendedColors.success,
                    )
                    Spacer(Modifier.width(Spacing.xs))
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = "收起悬浮窗") }
            }
            HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.5f))

            if (workflows.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f).padding(Spacing.xl), contentAlignment = Alignment.Center) {
                    Text(
                        "暂无挂载的工作流\n在主界面工作流卡片上点「图钉」挂载",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(0.55f),
                    contentPadding = PaddingValues(Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    items(workflows, key = { it.id }) { wf ->
                        FloatWorkflowRow(
                            workflow = wf,
                            isRunning = isRunningId(wf.id, activeJobIds),
                            isEnabled = wf.id in enabledWorkflowIds,
                            hasTrigger = wf.id in triggered,
                            onRun = { onWorkflowSelected(wf) },
                            onStop = { onWorkflowStop(wf) },
                            onToggle = onWorkflowToggle?.let { cb -> { on: Boolean -> cb(wf, on) } },
                        )
                    }
                }
            }

            if (executionLogs.isNotEmpty()) {
                LogConsole(
                    lines = executionLogs,
                    modifier = Modifier.fillMaxWidth().weight(0.45f).padding(start = Spacing.sm, end = Spacing.sm, bottom = Spacing.sm),
                    title = "执行日志",
                )
            }
        }
    }
}

@Composable
private fun FloatWorkflowRow(
    workflow: HabitatWorkflow,
    isRunning: Boolean,
    isEnabled: Boolean,
    hasTrigger: Boolean,
    onRun: () -> Unit,
    onStop: () -> Unit,
    onToggle: ((Boolean) -> Unit)?,
) {
    val cs = MaterialTheme.colorScheme
    val status = when {
        isRunning -> PillStatus.Running
        hasTrigger && isEnabled -> PillStatus.Enabled
        else -> PillStatus.Idle
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (isRunning) HabitatTheme.extendedColors.successContainer.copy(alpha = 0.45f) else cs.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = Spacing.md, end = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(status)
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Text(
                    workflow.name.ifEmpty { "未命名" },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when {
                        isRunning -> "运行中"
                        hasTrigger && isEnabled -> "监听中"
                        hasTrigger -> "未启用"
                        else -> "手动运行"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                )
            }
            when {
                isRunning -> FilledTonalIconButton(
                    onClick = onStop,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = cs.errorContainer, contentColor = cs.onErrorContainer),
                ) { Icon(Icons.Outlined.Stop, contentDescription = "停止 ${workflow.name}") }
                hasTrigger && onToggle != null -> Switch(
                    checked = isEnabled,
                    onCheckedChange = onToggle,
                    modifier = Modifier.padding(end = Spacing.xs).semantics { contentDescription = "启用 ${workflow.name}" },
                )
                else -> FilledTonalIconButton(onClick = onRun) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = "运行 ${workflow.name}")
                }
            }
        }
    }
}
