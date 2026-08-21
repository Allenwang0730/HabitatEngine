package com.ailun.habitat.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ailun.habitat.HabitatWorkflow

// ── Habitat teal palette for float window ──
private val TealGradient = listOf(Color(0xFF0D9488), Color(0xFF14B8A6))
private val Teal600 = Color(0xFF0D9488)
private val SurfaceDark = Color(0xFF0F172A)
private val SurfaceCard = Color(0xFF1E293B)
private val Green = Color(0xFF22C55E)
private val White = Color.White

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
    Box(modifier = Modifier.fillMaxSize()) {
        if (isExpanded) {
            WorkflowSelectionPanel(
                workflows, activeJobIds, enabledWorkflowIds, executionLogs,
                onWorkflowSelected, onWorkflowStop, onWorkflowToggle, onDismiss,
            )
        } else {
            FloatBallContent(inCloseZone)
        }
    }
}

@Composable
private fun FloatBallContent(inCloseZone: Boolean) {
    val gradient = if (inCloseZone) listOf(Color(0xFFEF4444), Color(0xFFDC2626)) else TealGradient

    Box(
        modifier = Modifier
            .fillMaxSize()
            .shadow(8.dp, CircleShape)
            .clip(CircleShape)
            .background(Brush.linearGradient(gradient)),
        contentAlignment = Alignment.Center,
    ) {
        if (inCloseZone) {
            Text("✕", color = White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        } else {
            Text(
                text = "H",
                color = White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
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
    Card(
        modifier = Modifier
            .fillMaxSize()
            .shadow(16.dp, RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Title bar
            Row(
                modifier = Modifier.fillMaxWidth().height(48.dp).padding(start = 16.dp, end = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Brush.linearGradient(TealGradient)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("H", color = White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("Habitat", color = White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                    Text("✕", color = White.copy(alpha = 0.4f), fontSize = 16.sp)
                }
            }

            HorizontalDivider(thickness = 0.5.dp, color = Color.White.copy(alpha = 0.1f))

            if (workflows.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text("暂无工作流", color = Color.White.copy(alpha = 0.3f), fontSize = 14.sp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(0.55f).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(workflows, key = { it.id }) { wf ->
                        FloatWorkflowCard(
                            wf,
                            isRunning = activeJobIds.contains(wf.id),
                            isEnabled = enabledWorkflowIds.contains(wf.id),
                            onRun = { onWorkflowSelected(wf) },
                            onStop = { onWorkflowStop(wf) },
                            onToggle = if (onWorkflowToggle != null) {
                                { enabled -> onWorkflowToggle(wf, enabled) }
                            } else null,
                        )
                    }
                }
            }

            // Execution log panel
            if (executionLogs.isNotEmpty()) {
                HorizontalDivider(thickness = 0.5.dp, color = Color.White.copy(alpha = 0.1f))
                Column(modifier = Modifier.fillMaxWidth().weight(0.45f).padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(
                        "执行日志",
                        color = White.copy(alpha = 0.5f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(executionLogs) { log ->
                            Text(
                                log,
                                color = White.copy(alpha = 0.7f),
                                fontSize = 10.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FloatWorkflowCard(
    workflow: HabitatWorkflow,
    isRunning: Boolean,
    isEnabled: Boolean,
    onRun: () -> Unit,
    onStop: () -> Unit,
    onToggle: ((Boolean) -> Unit)? = null,
) {
    val borderColor = when {
        isRunning -> Green.copy(alpha = 0.4f)
        isEnabled -> Color(0xFFF59E0B).copy(alpha = 0.3f)
        else -> Color.Transparent
    }

    Card(
        modifier = Modifier.fillMaxWidth().border(1.dp, borderColor, RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Status dot
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            isRunning -> Green
                            isEnabled -> Color(0xFFF59E0B)
                            else -> Color.Gray.copy(alpha = 0.4f)
                        }
                    )
            )
            Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                Text(
                    workflow.name.ifEmpty { "未命名" },
                    color = if (isEnabled) White else White.copy(alpha = 0.4f),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    when {
                        isRunning -> "● 运行中"
                        isEnabled -> "○ 等待触发"
                        else -> "已禁用"
                    },
                    color = when {
                        isRunning -> Green.copy(alpha = 0.8f)
                        isEnabled -> Color(0xFFF59E0B).copy(alpha = 0.8f)
                        else -> Color.White.copy(alpha = 0.2f)
                    },
                    fontSize = 11.sp
                )
            }
            if (onToggle != null) {
                Switch(
                    checked = isEnabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = White,
                        checkedTrackColor = Green,
                        uncheckedThumbColor = White.copy(alpha = 0.3f),
                        uncheckedTrackColor = Color.White.copy(alpha = 0.08f)
                    ),
                )
            }
        }
    }
}
