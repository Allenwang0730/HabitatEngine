package com.ailun.habitat.app.miniscreen

import android.view.MotionEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.UnfoldLess
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ailun.habitat.app.HabitatTheme
import com.ailun.habitat.app.Spacing
import kotlin.math.hypot

/** 小屏窗口的内容：展开是卡片，折叠是胶囊。 */
@Composable
internal fun MiniScreenContent(maxHeightPx: Int) {
    HabitatTheme {
        val state by MiniScreen.state.collectAsState()
        if (!state.visible) return@HabitatTheme
        if (state.collapsed) MiniPill(state) else MiniCard(state, maxHeightPx)
    }
}

@Composable
private fun MiniCard(state: MiniScreenState, maxHeightPx: Int) {
    val label = MiniScreenModel.sourceLabel(state.source)
    val maxHeight = with(LocalDensity.current) { maxHeightPx.toDp() }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 8.dp,
        modifier = Modifier.padding(Spacing.xs),
    ) {
        Column(Modifier.heightIn(max = maxHeight)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = Spacing.md, end = Spacing.xxs),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        .dragHandle()
                        .semantics { contentDescription = "Habitat 小屏，来源 $label，拖动可移动" }
                        .padding(vertical = Spacing.sm),
                ) {
                    SourceChip(state.source, label)
                    Spacer(Modifier.width(Spacing.sm))
                    Text(
                        state.title.ifEmpty { "Habitat 小屏" },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = MiniScreen::onToggleCollapsed, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.UnfoldLess, contentDescription = "折叠小屏", modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = MiniScreen::onClose, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.Close, contentDescription = "关闭小屏", modifier = Modifier.size(20.dp))
                }
            }
            state.progress?.let { value ->
                LinearProgressIndicator(
                    progress = { value / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.md)
                        .padding(bottom = Spacing.sm)
                        .semantics { contentDescription = "进度 $value%" },
                )
            }
            if (state.blocks.isNotEmpty()) {
                val scroll = rememberScrollState()
                val lastId = state.blocks.last().id
                // 追加内容后滚到底（等一帧布局完成再滚）
                LaunchedEffect(lastId) {
                    withFrameNanos { }
                    scroll.animateScrollTo(scroll.maxValue)
                }
                Column(
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(scroll)
                        .padding(start = Spacing.md, end = Spacing.md, bottom = Spacing.md),
                ) {
                    state.blocks.forEach { block -> BlockView(block) }
                }
            }
        }
    }
}

@Composable
private fun BlockView(block: MiniBlock) {
    when (block) {
        is MiniBlock.Text -> Text(
            block.text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        is MiniBlock.Image -> {
            val bitmap = MiniScreen.images[block.imageId] ?: return
            val ratio = if (block.height > 0) block.width.toFloat() / block.height else 1f
            Image(
                bitmap = remember(bitmap) { bitmap.asImageBitmap() },
                contentDescription = "小屏图片",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(ratio.coerceIn(0.3f, 4f))
                    .clip(MaterialTheme.shapes.medium),
            )
        }
    }
}

@Composable
private fun MiniPill(state: MiniScreenState) {
    val label = MiniScreenModel.sourceLabel(state.source)
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 6.dp,
        modifier = Modifier.padding(Spacing.xs),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            modifier = Modifier
                .dragHandle(onTap = MiniScreen::onToggleCollapsed)
                .semantics {
                    contentDescription = "Habitat 小屏已折叠，来源 $label"
                    onClick(label = "展开") { MiniScreen.onToggleCollapsed(); true }
                }
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
            Text(
                state.title.ifEmpty { label },
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 180.dp),
            )
            state.progress?.let {
                Text("$it%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            Icon(Icons.Outlined.UnfoldMore, contentDescription = null, modifier = Modifier.size(16.dp))
        }
    }
}

/** 来源标签：远程（MCP）写入的内容用警示色，和本机内容一眼区分。 */
@Composable
private fun SourceChip(source: String, label: String) {
    val remote = MiniScreenModel.isRemote(source)
    val bg = if (remote) HabitatTheme.extendedColors.warningContainer else MaterialTheme.colorScheme.secondaryContainer
    val fg = if (remote) HabitatTheme.extendedColors.onWarningContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = fg,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .widthIn(max = 120.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

private class DragTracker {
    var downX = 0f
    var downY = 0f
    var moved = false
}

/**
 * 拖动窗口：用屏幕坐标（rawX/rawY）算位移——窗口跟着手指走，局部坐标几乎不变，不能用。
 * 没有拖动就当作点按（[onTap]）。
 */
@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.dragHandle(onTap: (() -> Unit)? = null): Modifier = composed {
    val tracker = remember { DragTracker() }
    val slop = LocalViewConfiguration.current.touchSlop
    pointerInteropFilter { e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracker.downX = e.rawX
                tracker.downY = e.rawY
                tracker.moved = false
                MiniScreen.onDragStart()
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - tracker.downX
                val dy = e.rawY - tracker.downY
                if (!tracker.moved && hypot(dx, dy) > slop) tracker.moved = true
                if (tracker.moved) MiniScreen.onDrag(dx.toInt(), dy.toInt())
                true
            }
            MotionEvent.ACTION_UP -> {
                if (tracker.moved) MiniScreen.onDragEnd() else onTap?.invoke()
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (tracker.moved) MiniScreen.onDragEnd()
                true
            }
            else -> false
        }
    }
}
