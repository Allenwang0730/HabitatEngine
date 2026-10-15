package com.ailun.habitat.app.ui.workspace

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ailun.habitat.app.HabitatTextStyles
import com.ailun.habitat.app.HabitatTheme
import com.ailun.habitat.app.Spacing
import com.ailun.habitat.app.ui.components.CodeBlock
import com.ailun.habitat.app.ui.components.ConfirmDialog
import com.ailun.habitat.app.ui.components.EmptyState
import com.ailun.habitat.app.ui.components.SectionHeader
import com.ailun.habitat.app.ui.components.StatusPill
import com.ailun.habitat.app.ui.components.copyToClipboard
import com.ailun.habitat.app.workspace.RunRecord
import com.ailun.habitat.app.workspace.RunRecords
import com.ailun.habitat.workspace.Workspace
import com.ailun.habitat.workspace.WorkspaceStore
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 工作区条目详情：按类型展示（文本 / JSON / 图片 / 运行记录），可复制、分享、置顶、重命名、上小屏、删除。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceEntryScreen(entryId: String, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { Workspace.get(context) }
    val entries by store.entries.collectAsState()
    val entry = entries.firstOrNull { it.id == entryId }

    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    // 内容只在条目更新时重读
    val content by produceState<String?>(null, entry?.id, entry?.updatedAt) {
        value = entry?.takeIf { it.kind != WorkspaceStore.Kind.IMAGE }?.let { e ->
            withContext(Dispatchers.IO) { store.readText(e.id) }
        }
    }
    val image by produceState<android.graphics.Bitmap?>(null, entry?.id, entry?.updatedAt) {
        value = entry?.takeIf { it.kind == WorkspaceStore.Kind.IMAGE }?.let { e ->
            withContext(Dispatchers.IO) { store.fileFor(e.id)?.let { WorkspaceActions.decodeSampled(it, 2048) } }
        }
    }

    fun shownText(): String? = when (entry?.kind) {
        WorkspaceStore.Kind.RECORD -> content?.let { raw -> RunRecords.parse(raw)?.let(::recordText) ?: raw }
        else -> content
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(entry?.name ?: "条目", maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回") }
                },
                actions = {
                    if (entry != null) {
                        IconButton(onClick = { scope.launch(Dispatchers.IO) { store.setPinned(entry.id, !entry.pinned) } }) {
                            Icon(
                                if (entry.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                                contentDescription = if (entry.pinned) "取消置顶" else "置顶",
                                tint = if (entry.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { WorkspaceActions.share(context, entry, shownText()) }) {
                            Icon(Icons.Outlined.Share, contentDescription = "分享")
                        }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "更多操作") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                if (entry.kind != WorkspaceStore.Kind.IMAGE) {
                                    DropdownMenuItem(
                                        text = { Text("复制内容") },
                                        leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                                        onClick = {
                                            menu = false
                                            shownText()?.let { copyToClipboard(context, it, entry.name) }
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("显示到小屏") },
                                    leadingIcon = { Icon(Icons.Outlined.PictureInPictureAlt, contentDescription = null) },
                                    onClick = {
                                        menu = false
                                        scope.launch { WorkspaceActions.showOnMiniScreen(context, entry, shownText()?.take(4000)) }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("重命名") },
                                    leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, contentDescription = null) },
                                    onClick = { menu = false; renaming = true },
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                    onClick = { menu = false; deleting = true },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (entry == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(icon = Icons.Outlined.Delete, title = "条目已不存在", message = "可能已被删除，或因空间上限被清理。")
            }
            return@Scaffold
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.page, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            MetaLine(entry)
            when (entry.kind) {
                WorkspaceStore.Kind.TEXT -> TextBody(content)
                WorkspaceStore.Kind.JSON -> content?.let { CodeBlock(prettyJson(it), title = "JSON", maxHeight = 2000.dp) }
                WorkspaceStore.Kind.IMAGE -> image?.let { ZoomableImage(it, entry.name) }
                WorkspaceStore.Kind.RECORD -> content?.let { raw ->
                    val record = remember(raw) { RunRecords.parse(raw) }
                    if (record != null) RecordBody(record) else CodeBlock(raw, title = "记录", maxHeight = 2000.dp)
                }
            }
        }
    }

    if (renaming && entry != null) {
        var name by remember { mutableStateOf(entry.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("重命名") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(WorkspaceStore.MAX_NAME) },
                    singleLine = true,
                    label = { Text("名称") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        renaming = false
                        scope.launch(Dispatchers.IO) { store.rename(entry.id, name) }
                    },
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("取消") } },
        )
    }
    if (deleting && entry != null) {
        ConfirmDialog(
            title = "删除「${entry.name}」？",
            message = "文件会从本机删除，无法恢复。",
            confirmLabel = "删除",
            destructive = true,
            icon = Icons.Outlined.Delete,
            onConfirm = {
                deleting = false
                scope.launch {
                    withContext(Dispatchers.IO) { store.delete(entry.id) }
                    onBack()
                }
            },
            onDismiss = { deleting = false },
        )
    }
}

@Composable
private fun MetaLine(entry: WorkspaceStore.Entry) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SourceText(entry.source)
        Text(
            " · ${WorkspaceActions.kindLabel(entry)} · ${WorkspaceActions.fullTime(entry.updatedAt)} · ${WorkspaceActions.size(entry.size)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (entry.tags.isNotEmpty() && entry.kind != WorkspaceStore.Kind.RECORD) {
        Text(
            entry.tags.joinToString("  ") { "#$it" },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun TextBody(text: String?) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        SelectionContainer {
            Text(
                text ?: "",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(Spacing.lg),
            )
        }
    }
}

@Composable
private fun ZoomableImage(bitmap: android.graphics.Bitmap, name: String) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val ratio = if (bitmap.height > 0) bitmap.width.toFloat() / bitmap.height else 1f
    Box(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    offset = if (scale == 1f) Offset.Zero else offset + pan
                }
            },
    ) {
        Image(
            bitmap = remember(bitmap) { bitmap.asImageBitmap() },
            contentDescription = name,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 900.dp)
                .aspectRatio(ratio.coerceIn(0.2f, 5f))
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )
    }
    Text(
        "双指缩放查看 · ${bitmap.width}×${bitmap.height}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecordBody(record: RunRecord) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusPill(pillFor(record.status), statusText(record.status))
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    "用时 ${WorkspaceActions.duration(record.durationMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SelectionContainer {
                Text(record.result.ifBlank { "（无结果说明）" }, style = MaterialTheme.typography.bodyLarge)
            }
            val chips = buildList {
                record.meta["provider"]?.let { add("执行器 $it") }
                if (record.meta["planner"] == "true") add("DeepSeek 规划")
                if (record.meta["origin"] == "remote") add("远程发起")
                add("开始 ${WorkspaceActions.fullTime(record.startedAt)}")
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                chips.forEach { Chip(it) }
            }
            record.meta["goal"]?.takeIf { it != record.title }?.let {
                Text("目标：$it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (record.outputs.isNotEmpty()) {
        SectionHeader("输出变量（${record.outputs.size}）")
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
            SelectionContainer {
                Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    record.outputs.forEach { (k, v) ->
                        Text(
                            "$k = $v",
                            style = HabitatTextStyles.code,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
    if (record.lines.isNotEmpty()) {
        SectionHeader("步骤（${record.lines.size}）")
        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
            record.lines.forEachIndexed { i, line -> TimelineRow(line, last = i == record.lines.lastIndex) }
        }
    }
}

@Composable
private fun Chip(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = Spacing.sm, vertical = Spacing.xxs),
    )
}

/** 时间线一行：失败/卡住类用警示色圆点，其它用主色。 */
@Composable
private fun TimelineRow(line: String, last: Boolean) {
    val bad = listOf("失败", "卡住", "未通过", "异常", "error", "拒绝", "没成功").any { line.contains(it) }
    val dot = if (bad) HabitatTheme.extendedColors.warning else MaterialTheme.colorScheme.primary
    Row {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(20.dp)) {
            Spacer(Modifier.size(6.dp))
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            if (!last) {
                Box(
                    Modifier
                        .width(1.dp)
                        .heightIn(min = 18.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }
        }
        Text(
            line,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = Spacing.xs, bottom = Spacing.sm).weight(1f),
        )
    }
}

private fun recordText(r: RunRecord): String = buildString {
    appendLine(r.title)
    appendLine("${statusText(r.status)} · ${r.result}")
    if (r.outputs.isNotEmpty()) {
        appendLine()
        r.outputs.forEach { (k, v) -> appendLine("$k = $v") }
    }
    if (r.lines.isNotEmpty()) {
        appendLine()
        r.lines.forEach(::appendLine)
    }
}.trimEnd()

private val pretty = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

private fun prettyJson(raw: String): String = runCatching { pretty.toJson(JsonParser.parseString(raw)) }.getOrDefault(raw)
