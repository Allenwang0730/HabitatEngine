package com.ailun.habitat.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ailun.habitat.HabitatStateStore
import com.ailun.habitat.app.Spacing
import com.ailun.habitat.app.TriggerManager
import com.ailun.habitat.app.tabular
import com.ailun.habitat.app.ui.components.EmptyState
import com.ailun.habitat.app.ui.components.IconTile
import com.ailun.habitat.app.ui.components.PillStatus
import com.ailun.habitat.app.ui.components.SkeletonList
import com.ailun.habitat.app.ui.components.StatusPill
import com.ailun.habitat.app.ui.theme.TriggerVisuals
import com.ailun.habitat.app.ui.viewmodel.DashboardViewModel
import com.ailun.habitat.app.ui.viewmodel.WorkflowItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowListScreen(
    vm: DashboardViewModel,
    isFloatServiceRunning: Boolean,
    onFloatServiceToggle: (Boolean) -> Unit,
    onEdit: (WorkflowItem) -> Unit,
    onNew: () -> Unit,
    onMounted: () -> Unit,
    onRun: (WorkflowItem) -> Unit,
) {
    val items by vm.items.collectAsStateWithLifecycle()
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    val pending by vm.pendingDeletes.collectAsStateWithLifecycle()
    val mounted by vm.mountedIds.collectAsStateWithLifecycle()
    val running by HabitatStateStore.running.collectAsStateWithLifecycle()
    val enabled by TriggerManager.enabledFlow.collectAsStateWithLifecycle()

    var searching by rememberSaveable { mutableStateOf(false) }
    val query = vm.query
    val filter = vm.triggerFilter

    val visible = remember(items, pending, query, filter) {
        items.filter { it.id !in pending }
            .filter { filter == null || (it.trigger?.type ?: TriggerVisuals.MANUAL) == filter }
            .filter {
                query.isBlank() || it.workflow.name.contains(query, true) || it.workflow.description.contains(query, true)
            }
    }
    val triggerTypes = remember(items) {
        items.map { it.trigger?.type ?: TriggerVisuals.MANUAL }.distinct()
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val listState = rememberLazyListState()
    val fabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("工作流") },
                actions = {
                    IconButton(onClick = {
                        searching = !searching
                        if (!searching) vm.query = ""
                    }) {
                        Icon(
                            if (searching) Icons.Outlined.Close else Icons.Outlined.Search,
                            contentDescription = if (searching) "关闭搜索" else "搜索工作流",
                        )
                    }
                    IconToggleButton(
                        checked = isFloatServiceRunning,
                        onCheckedChange = onFloatServiceToggle,
                        modifier = Modifier.semantics { stateDescription = if (isFloatServiceRunning) "已开启" else "已关闭" },
                    ) {
                        Icon(
                            if (isFloatServiceRunning) Icons.Filled.PictureInPictureAlt else Icons.Outlined.PictureInPictureAlt,
                            contentDescription = "悬浮窗",
                            tint = if (isFloatServiceRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text("新建") },
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                onClick = onNew,
                expanded = fabExpanded,
            )
        },
        snackbarHost = { SnackbarHost(vm.snackbar) },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = Spacing.page, end = Spacing.page,
                top = padding.calculateTopPadding() + Spacing.xs,
                bottom = padding.calculateBottomPadding() + 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            if (searching) item(key = "search", contentType = "header") {
                val appear = remember { MutableTransitionState(false).apply { targetState = true } }
                AnimatedVisibility(appear, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { vm.query = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("搜索名称或描述") },
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { vm.query = "" }) { Icon(Icons.Outlined.Close, contentDescription = "清除搜索") }
                            }
                        },
                        singleLine = true,
                        shape = MaterialTheme.shapes.extraLarge,
                    )
                }
            }
            if (triggerTypes.size > 1) {
                item(key = "filters", contentType = "header") {
                    FilterRow(triggerTypes, filter) { vm.triggerFilter = it }
                }
            }

            when {
                !loaded -> item(key = "skeleton") { SkeletonList(count = 4, horizontalPadding = 0.dp) }
                items.none { it.id !in pending } -> item(key = "empty") {
                    EmptyState(
                        icon = Icons.Outlined.AccountTree,
                        title = "还没有工作流",
                        message = "工作流把一串手机操作编排成可重复运行的脚本。新建一个，或去「AI 生成」用一句话描述需求。",
                        actionLabel = "新建工作流",
                        actionIcon = Icons.Outlined.Add,
                        onAction = onNew,
                    )
                }
                visible.isEmpty() -> item(key = "nomatch") {
                    EmptyState(icon = Icons.Outlined.SearchOff, title = "没有匹配的工作流", message = "换个关键词或筛选条件试试。")
                }
                else -> items(visible, key = { it.id }, contentType = { "workflow" }) { item ->
                    val isRunning = vm.isRunning(item.id, running)
                    SwipeToDeleteRow(
                        onDelete = { vm.requestDelete(item) },
                        modifier = Modifier.animateItem(),
                    ) {
                        WorkflowCard(
                            item = item,
                            isRunning = isRunning,
                            isEnabled = item.id in enabled,
                            isMounted = item.id in mounted,
                            onToggle = { vm.setEnabled(item, it) },
                            onRun = { onRun(item) },
                            onStop = { vm.stop(item) },
                            onEdit = { onEdit(item) },
                            onMountToggle = { if (vm.toggleMount(item)) onMounted() },
                            onDelete = { vm.requestDelete(item) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterRow(types: List<String>, selected: String?, onSelect: (String?) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text("全部") })
        types.forEach { t ->
            FilterChip(
                selected = selected == t,
                onClick = { onSelect(if (selected == t) null else t) },
                label = { Text(TriggerVisuals.shortLabel(t)) },
                leadingIcon = { Icon(TriggerVisuals.icon(t), contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDeleteRow(
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val state = rememberSwipeToDismissBoxState()
    LaunchedEffect(Unit) {
        // 撤销后项重新出现：确保回到初始位置
        if (state.currentValue != SwipeToDismissBoxValue.Settled) state.snapTo(SwipeToDismissBoxValue.Settled)
    }
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        enableDismissFromStartToEnd = false,
        onDismiss = { if (it == SwipeToDismissBoxValue.EndToStart) onDelete() },
        backgroundContent = {
            val active = state.targetValue == SwipeToDismissBoxValue.EndToStart
            val bg by animateColorAsState(
                if (active) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                label = "swipeBg",
            )
            Box(
                Modifier.fillMaxSize().clip(MaterialTheme.shapes.large).background(bg).padding(horizontal = Spacing.xl),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    Icons.Outlined.DeleteOutline,
                    contentDescription = null,
                    tint = if (active) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    ) { content() }
}

@Composable
private fun WorkflowCard(
    item: WorkflowItem,
    isRunning: Boolean,
    isEnabled: Boolean,
    isMounted: Boolean,
    onToggle: (Boolean) -> Unit,
    onRun: () -> Unit,
    onStop: () -> Unit,
    onEdit: () -> Unit,
    onMountToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    val wf = item.workflow
    val cs = MaterialTheme.colorScheme
    val (status, statusText) = when {
        item.parseError != null -> PillStatus.Failed to "JSON 有误"
        isRunning -> PillStatus.Running to "运行中"
        item.hasTrigger && isEnabled -> PillStatus.Enabled to "监听中"
        item.hasTrigger -> PillStatus.Idle to "未启用"
        else -> PillStatus.Idle to "就绪"
    }
    val active = isRunning || (item.hasTrigger && isEnabled)
    var menu by remember { mutableStateOf(false) }

    Surface(
        onClick = onEdit,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = cs.surfaceContainerLow,
    ) {
        Column(Modifier.padding(start = Spacing.lg, end = Spacing.xs, top = Spacing.lg, bottom = Spacing.xs)) {
            Row(verticalAlignment = Alignment.Top) {
                IconTile(
                    TriggerVisuals.icon(item.trigger?.type),
                    tint = if (active) cs.primary else cs.onSurfaceVariant,
                    container = if (active) cs.primaryContainer else cs.surfaceContainerHighest,
                    size = 40,
                )
                Spacer(Modifier.width(Spacing.md))
                Column(Modifier.weight(1f)) {
                    Text(
                        wf.name.ifBlank { "未命名" },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (wf.description.isNotBlank()) {
                        Text(
                            wf.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = cs.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.width(Spacing.sm))
                Box(Modifier.padding(end = Spacing.md)) { StatusPill(status, statusText) }
            }

            Spacer(Modifier.height(Spacing.sm))
            Row(
                Modifier.padding(start = 52.dp),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MetaItem(TriggerVisuals.icon(item.trigger?.type), TriggerVisuals.label(item.trigger?.type))
                MetaItem(Icons.Outlined.Hub, "${item.nodeCount} 个节点")
                if (isMounted) MetaItem(Icons.Filled.PushPin, "悬浮窗", tint = cs.primary)
                if (item.parseError != null) MetaItem(Icons.Outlined.ErrorOutline, "点击修复", tint = cs.error)
            }

            Row(
                Modifier.fillMaxWidth().padding(start = 52.dp - Spacing.xs, top = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (item.hasTrigger) {
                    Switch(
                        checked = isEnabled,
                        onCheckedChange = onToggle,
                        enabled = item.parseError == null,
                        modifier = Modifier.padding(start = Spacing.xs).semantics {
                            contentDescription = "启用触发器"
                        },
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Text(
                        if (isEnabled) "已启用" else "已停用",
                        style = MaterialTheme.typography.labelMedium,
                        color = cs.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))

                if (isRunning) {
                    FilledTonalButton(onClick = onStop, contentPadding = PaddingValues(horizontal = Spacing.md)) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(Spacing.sm))
                        Icon(Icons.Outlined.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(Spacing.xs))
                        Text("停止")
                    }
                } else if (!item.hasTrigger) {
                    FilledTonalButton(
                        onClick = onRun,
                        enabled = item.parseError == null,
                        contentPadding = PaddingValues(horizontal = Spacing.md),
                    ) {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(Spacing.xs))
                        Text("运行")
                    }
                }

                IconButton(onClick = onMountToggle) {
                    Icon(
                        if (isMounted) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                        contentDescription = if (isMounted) "取消挂载到悬浮窗" else "挂载到悬浮窗",
                        tint = if (isMounted) cs.primary else cs.onSurfaceVariant,
                    )
                }
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "更多操作")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("编辑") },
                            leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                            onClick = { menu = false; onEdit() },
                        )
                        DropdownMenuItem(
                            text = { Text(if (isMounted) "取消挂载悬浮窗" else "挂载到悬浮窗") },
                            leadingIcon = { Icon(Icons.Outlined.PushPin, null) },
                            onClick = { menu = false; onMountToggle() },
                        )
                        DropdownMenuItem(
                            text = { Text("删除", color = cs.error) },
                            leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, tint = cs.error) },
                            onClick = { menu = false; onDelete() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MetaItem(icon: ImageVector, text: String, tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(Spacing.xs))
        Text(text, style = MaterialTheme.typography.labelMedium.tabular(), color = tint, maxLines = 1)
    }
}
