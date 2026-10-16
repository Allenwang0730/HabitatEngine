package com.ailun.habitat.app.ui.workspace

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ailun.habitat.app.HabitatTheme
import com.ailun.habitat.app.Spacing
import com.ailun.habitat.app.ui.components.EmptyState
import com.ailun.habitat.app.ui.components.IconTile
import com.ailun.habitat.app.ui.components.PillStatus
import com.ailun.habitat.app.ui.components.StatusPill
import com.ailun.habitat.workspace.Workspace
import com.ailun.habitat.workspace.WorkspaceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class WsFilter(val label: String) {
    All("全部"), Notes("笔记"), Images("图片"), Agent("Agent 记录"), Workflow("工作流记录");

    fun matches(e: WorkspaceStore.Entry): Boolean = when (this) {
        All -> true
        Notes -> e.kind == WorkspaceStore.Kind.TEXT || e.kind == WorkspaceStore.Kind.JSON
        Images -> e.kind == WorkspaceStore.Kind.IMAGE
        Agent -> e.kind == WorkspaceStore.Kind.RECORD && "workflow" !in e.tags
        Workflow -> e.kind == WorkspaceStore.Kind.RECORD && "workflow" in e.tags
    }
}

/** 底部「工作区」：节点、Agent、脚本写下的结果，按天分组，置顶在前。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScreen(onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val store = remember { Workspace.get(context) }
    val entries by store.entries.collectAsState()
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { store.list(limit = 1) }
        loaded = true
    }
    var filter by rememberSaveable { mutableStateOf(WsFilter.All) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }

    val visible = remember(entries, filter, query) {
        entries.filter { filter.matches(it) }
            .filter { e -> query.isBlank() || e.name.contains(query, true) || e.tags.any { it.contains(query, true) } }
    }
    val pinned = visible.filter { it.pinned }
    val groups = remember(visible) { visible.filter { !it.pinned }.groupBy { WorkspaceActions.dayLabel(it.updatedAt) } }
    val totalBytes = remember(entries) { entries.sumOf { it.size } }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Column {
                        Text("工作区")
                        if (entries.isNotEmpty()) {
                            Text(
                                "${entries.size} 项 · ${WorkspaceActions.size(totalBytes)} · 只存本机",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = {
                        searching = !searching
                        if (!searching) query = ""
                    }) {
                        Icon(
                            if (searching) Icons.Outlined.Close else Icons.Outlined.Search,
                            contentDescription = if (searching) "关闭搜索" else "搜索工作区",
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = Spacing.page, end = Spacing.page,
                top = padding.calculateTopPadding() + Spacing.xs,
                bottom = padding.calculateBottomPadding() + Spacing.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            item(key = "search", contentType = "header") {
                AnimatedVisibility(searching, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.sm),
                        placeholder = { Text("搜索名称或标签") },
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, contentDescription = "清除搜索") }
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        shape = MaterialTheme.shapes.extraLarge,
                    )
                }
            }
            if (entries.isNotEmpty()) item(key = "filters", contentType = "header") {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    WsFilter.entries.forEach { f ->
                        FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) })
                    }
                }
            }
            when {
                !loaded -> Unit
                entries.isEmpty() -> item(key = "empty") {
                    EmptyState(
                        icon = Icons.Outlined.FolderOpen,
                        title = "工作区还是空的",
                        message = "AI 和工作流的结果会存在这里，只在本机：Agent 每次任务的记录、工作流里「工作区」节点写的笔记和图片、" +
                            "在编辑器里打开「运行结果存到工作区」的工作流。",
                    )
                }
                visible.isEmpty() -> item(key = "nomatch") {
                    EmptyState(icon = Icons.Outlined.SearchOff, title = "没有匹配的条目", message = "换个关键词或筛选条件试试。")
                }
                else -> {
                    if (pinned.isNotEmpty()) {
                        item(key = "h_pinned", contentType = "day") { DayHeader("置顶") }
                        items(pinned, key = { it.id }, contentType = { "entry" }) { e ->
                            EntryCard(e, onClick = { onOpen(e.id) }, modifier = Modifier.animateItem())
                        }
                    }
                    groups.forEach { (day, list) ->
                        item(key = "h_$day", contentType = "day") { DayHeader(day) }
                        items(list, key = { it.id }, contentType = { "entry" }) { e ->
                            EntryCard(e, onClick = { onOpen(e.id) }, modifier = Modifier.animateItem())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(start = Spacing.xs, top = Spacing.md, bottom = Spacing.xxs)
            .semantics { heading() },
    )
}

@Composable
private fun EntryCard(entry: WorkspaceStore.Entry, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val preview by produceState<String?>(null, entry.id, entry.updatedAt) {
        value = if (entry.kind == WorkspaceStore.Kind.TEXT || entry.kind == WorkspaceStore.Kind.JSON) {
            withContext(Dispatchers.IO) { Workspace.get(context).readPreview(entry.id, 240) }
                ?.replace(Regex("\\s+"), " ")?.trim()
        } else null
    }
    val thumb by produceState<android.graphics.Bitmap?>(null, entry.id, entry.updatedAt) {
        if (entry.kind == WorkspaceStore.Kind.IMAGE) value = WorkspaceActions.thumbnail(context, entry, 160)
    }
    val kind = WorkspaceActions.kindLabel(entry)
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .clickable(onClickLabel = "打开") { onClick() }
            .semantics(mergeDescendants = true) { contentDescription = "$kind ${entry.name}" },
    ) {
        Row(
            Modifier.padding(Spacing.md).heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val bitmap = thumb
            if (bitmap != null) {
                Image(
                    bitmap = remember(bitmap) { bitmap.asImageBitmap() },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(44.dp).clip(MaterialTheme.shapes.small),
                )
            } else {
                IconTile(iconFor(entry), size = 44)
            }
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        entry.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (entry.pinned) {
                        Spacer(Modifier.width(Spacing.xs))
                        Icon(Icons.Outlined.PushPin, contentDescription = "已置顶", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SourceText(entry.source)
                    Text(
                        " · $kind · ${WorkspaceActions.time(entry.updatedAt)} · ${WorkspaceActions.size(entry.size)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                preview?.takeIf { it.isNotEmpty() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = Spacing.xxs),
                    )
                }
            }
            WorkspaceActions.recordStatus(entry)?.let { status ->
                Spacer(Modifier.width(Spacing.sm))
                Box { StatusPill(pillFor(status), statusText(status)) }
            }
        }
    }
}

@Composable
internal fun SourceText(source: String) {
    val remote = WorkspaceActions.isRemote(source)
    Text(
        WorkspaceActions.sourceLabel(source),
        style = MaterialTheme.typography.bodySmall,
        color = if (remote) HabitatTheme.extendedColors.warning else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = 140.dp),
    )
}

internal fun iconFor(entry: WorkspaceStore.Entry) = when (entry.kind) {
    WorkspaceStore.Kind.TEXT -> Icons.Outlined.Description
    WorkspaceStore.Kind.JSON -> Icons.Outlined.DataObject
    WorkspaceStore.Kind.IMAGE -> Icons.Outlined.Image
    WorkspaceStore.Kind.RECORD -> if ("workflow" in entry.tags) Icons.Outlined.AccountTree else Icons.Outlined.SmartToy
}

internal fun pillFor(status: String): PillStatus = when (status) {
    "done", "completed" -> PillStatus.Success
    "blocked", "stopped" -> PillStatus.Warning
    "error", "failed" -> PillStatus.Failed
    else -> PillStatus.Idle
}

internal fun statusText(status: String): String = when (status) {
    "done", "completed" -> "完成"
    "blocked" -> "受阻"
    "stopped" -> "已停止"
    "error" -> "出错"
    "failed" -> "有错误"
    else -> status
}
