package com.ailun.habitat.app.dag

import android.graphics.Path
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.toPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ailun.habitat.HabitatJson
import com.ailun.habitat.WorkflowNode
import com.ailun.habitat.app.HabitatDagConverter
import com.ailun.habitat.app.HabitatTextStyles
import com.ailun.habitat.app.HabitatTheme
import com.ailun.habitat.app.Spacing
import com.ailun.habitat.app.ui.components.CodeBlock
import com.ailun.habitat.app.ui.components.IconTile
import com.ailun.habitat.app.ui.components.copyToClipboard
import com.ailun.habitat.app.ui.theme.NodeCategories
import com.ailun.habitat.app.ui.theme.NodeCategory
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 外部驱动 DAG 聚焦（例如问题面板点击）。每次 [focus] 递增序号，重复点击同一节点也会重新居中。 */
@Stable
class DagFocusRequester {
    var nodeId by mutableStateOf<String?>(null)
        private set
    var seq by mutableIntStateOf(0)
        private set

    fun focus(id: String) { nodeId = id; seq++ }
}

private data class DagLayoutResult(val graph: DagGraph?, val error: String?)

/** Material 图标 → android.graphics.Path（24×24 视口）。 */
private fun ImageVector.toAndroidPath(): Path {
    val out = Path()
    fun walk(group: VectorGroup) {
        for (child in group) {
            when (child) {
                is VectorPath -> out.addPath(child.pathData.toPath().asAndroidPath())
                is VectorGroup -> walk(child)
            }
        }
    }
    walk(root)
    return out
}

private val categoryPaths: Map<NodeCategory, Path> by lazy {
    NodeCategory.entries.associateWith { NodeCategories.icon(it).toAndroidPath() }
}

@Composable
private fun rememberDagPalette(): DagPalette {
    val cs = MaterialTheme.colorScheme
    val ext = HabitatTheme.extendedColors
    val nodeColors = HabitatTheme.nodeColors
    val dark = HabitatTheme.isDark
    return remember(cs, ext, nodeColors, dark) {
        DagPalette(
            background = cs.surfaceContainerLowest.toArgb(),
            gridDot = ext.gridDot.toArgb(),
            nodeSurface = (if (dark) cs.surfaceContainerHigh else cs.surfaceContainerLowest).toArgb(),
            nodeBorder = cs.outlineVariant.toArgb(),
            selectedBorder = cs.primary.toArgb(),
            startBorder = cs.primary.copy(alpha = 0.55f).toArgb(),
            label = cs.onSurface.toArgb(),
            type = cs.onSurfaceVariant.toArgb(),
            edgeNext = cs.outline.toArgb(),
            edgeTrue = ext.success.toArgb(),
            edgeFalse = cs.error.toArgb(),
            edgeBack = ext.warning.toArgb(),
            edgeJump = cs.tertiary.toArgb(),
            badgeBg = cs.primary.toArgb(),
            badgeFg = cs.onPrimary.toArgb(),
            categoryAccent = NodeCategory.entries.associateWith { nodeColors[it].accent.toArgb() },
            categoryContainer = NodeCategory.entries.associateWith { nodeColors[it].container.toArgb() },
        )
    }
}

/**
 * 工作流结构图面板：解析+布局在后台线程（输入防抖），AndroidView 通过 update 驱动，
 * 点击节点弹出详情底部面板，长按复制节点 id。
 */
@Composable
fun WorkflowDagPanel(
    json: String,
    modifier: Modifier = Modifier,
    focusRequester: DagFocusRequester? = null,
    showLegend: Boolean = true,
    debounceMs: Long = 250,
    interactive: Boolean = true,
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val palette = rememberDagPalette()

    val result by produceState(DagLayoutResult(null, null), json) {
        if (value.graph != null) delay(debounceMs)
        val r = withContext(Dispatchers.Default) {
            try {
                val g = HabitatDagConverter.convert(HabitatJson.fromJson(json))
                DagLayoutResult(DagLayoutEngine.layout(g, density), null)
            } catch (e: Exception) {
                DagLayoutResult(null, e.message ?: "无法解析")
            }
        }
        // 解析失败时保留上次成功的图，仅提示错误
        value = if (r.graph == null) value.copy(error = r.error) else r
    }

    var detail by remember { mutableStateOf<DagNode?>(null) }
    var viewRef by remember { mutableStateOf<DagView?>(null) }

    LaunchedEffect(focusRequester?.seq, viewRef) {
        val id = focusRequester?.nodeId ?: return@LaunchedEffect
        viewRef?.focusNode(id)
    }

    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(MaterialTheme.shapes.large)
                .clipToBounds(),
        ) {
            val graph = result.graph
            AndroidView(
                factory = { ctx ->
                    DagView(ctx).apply {
                        categoryIcons = categoryPaths
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                        viewRef = this
                    }
                },
                update = { view ->
                    view.palette = palette
                    view.interactive = interactive
                    view.onNodeClicked = { detail = it }
                    view.onNodeLongPressed = { node ->
                        copyToClipboard(context, node.stepId, "节点 id", toast = "已复制节点 id")
                    }
                    if (graph != null && view.graph !== graph) view.setGraph(graph)
                },
                modifier = Modifier.fillMaxSize(),
            )

            if (interactive) {
                FilledTonalIconButton(
                    onClick = { viewRef?.zoomToFit() },
                    modifier = Modifier.align(Alignment.TopEnd).padding(Spacing.sm),
                ) {
                    Icon(Icons.Outlined.CenterFocusStrong, contentDescription = "适应屏幕")
                }
            }

            val err = result.error
            if (err != null) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(Spacing.sm),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Row(Modifier.padding(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(Modifier.width(Spacing.sm))
                        Text(
                            if (graph == null) err else "JSON 有误，显示的是上次可解析的结构：$err",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        if (showLegend) {
            DagLegend(Modifier.padding(top = Spacing.sm))
        }
    }

    detail?.let { node ->
        NodeDetailSheet(
            node = node,
            onDismiss = { detail = null; viewRef?.selectedNodeId = null },
            onNavigate = { id ->
                val target = result.graph?.nodes?.find { it.stepId == id }
                viewRef?.focusNode(id)
                detail = target
            },
        )
    }
}

@Composable
fun DagLegend(modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val ext = HabitatTheme.extendedColors
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendItem("顺序", cs.outline, dashed = false)
        LegendItem("是", ext.success, dashed = false)
        LegendItem("否", cs.error, dashed = false)
        LegendItem("循环回边", ext.warning, dashed = true)
        LegendItem("引用", cs.tertiary, dashed = true)
        Text("双击复位 · 长按复制 id", style = MaterialTheme.typography.labelSmall, color = cs.outline)
    }
}

@Composable
private fun LegendItem(label: String, color: Color, dashed: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(width = 18.dp, height = 8.dp)) {
            drawLine(
                color = color,
                start = Offset(0f, size.height / 2),
                end = Offset(size.width, size.height / 2),
                strokeWidth = 2.dp.toPx(),
                pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())) else null,
            )
        }
        Spacer(Modifier.width(Spacing.xs))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val prettyGson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeDetailSheet(
    node: DagNode,
    onDismiss: () -> Unit,
    onNavigate: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val colors = HabitatTheme.nodeColors[node.category]
    val src: WorkflowNode? = node.source

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.xl)
                .navigationBarsPadding()
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(NodeCategories.icon(node.category), tint = colors.accent, container = colors.container, size = 44)
                Spacer(Modifier.width(Spacing.md))
                Column(Modifier.weight(1f)) {
                    Text(node.label, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${node.category.label}${if (node.isTrigger) " · 起始节点" else ""}",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.accent,
                    )
                }
            }

            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column {
                    DetailRow("id", node.stepId, mono = true)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    DetailRow("类型", node.moduleId, mono = true)
                    src?.description?.takeIf { it.isNotBlank() && it != node.label }?.let {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        DetailRow("说明", it)
                    }
                    src?.next?.takeIf { it.isNotBlank() }?.let { next ->
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        DetailRow("下一步", next, mono = true, onClick = onNavigate?.let { { it(next) } })
                    }
                    src?.branches?.forEach { (name, target) ->
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        DetailRow(
                            "分支 $name",
                            target ?: "（结束）",
                            mono = true,
                            onClick = if (target != null) onNavigate?.let { { it(target) } } else null,
                        )
                    }
                }
            }

            val params = src?.params
            if (!params.isNullOrEmpty()) {
                CodeBlock(prettyGson.toJson(params), title = "参数", maxHeight = 280.dp, copyLabel = "复制参数")
            }

            OutlinedButton(
                onClick = { src?.let { copyToClipboard(context, prettyGson.toJson(it), "节点 JSON") } },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.sm))
                Text("复制节点 JSON")
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, mono: Boolean = false, onClick: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 44.dp)
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp),
        )
        Text(
            value,
            style = if (mono) HabitatTextStyles.code else MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (onClick != null) {
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = "跳转到 $value",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 静态小尺寸预览（AI 结果卡片等）：不弹详情，只展示结构。 */
@Composable
fun DagPreview(json: String, modifier: Modifier = Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerLowest, MaterialTheme.shapes.medium)) {
        WorkflowDagPanel(json = json, modifier = Modifier.fillMaxSize(), showLegend = false, debounceMs = 0, interactive = false)
    }
}
