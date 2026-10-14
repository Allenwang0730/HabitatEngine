package com.ailun.habitat.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ailun.habitat.app.HabitatTextStyles
import com.ailun.habitat.app.HabitatTheme
import com.ailun.habitat.app.Spacing

enum class LogLevel { Success, Error, Warning, Info }

/** 按行首符号/关键词粗分级别（引擎日志没有结构化 level）。 */
fun logLevelOf(line: String): LogLevel {
    val s = line.trimStart()
    return when {
        s.startsWith("✓") || s.startsWith("✅") || s.startsWith("结束 done") -> LogLevel.Success
        s.startsWith("✗") || s.startsWith("❌") || s.contains("error", true) || s.contains("失败") || s.contains("异常") -> LogLevel.Error
        s.startsWith("⚠") || s.contains("warn", true) || s.contains("blocked") || s.contains("跳过") -> LogLevel.Warning
        else -> LogLevel.Info
    }
}

@Composable
fun logLevelColor(level: LogLevel): Color = when (level) {
    LogLevel.Success -> HabitatTheme.extendedColors.success
    LogLevel.Error -> MaterialTheme.colorScheme.error
    LogLevel.Warning -> HabitatTheme.extendedColors.warning
    LogLevel.Info -> HabitatTheme.extendedColors.codeForeground
}

/**
 * 日志控制台：等宽、按级别着色、贴底时自动滚动、可复制/清空。
 * 父级需给定高度（weight/height）。
 */
@Composable
fun LogConsole(
    lines: List<String>,
    modifier: Modifier = Modifier,
    title: String = "执行日志",
    onClear: (() -> Unit)? = null,
    emptyText: String = "暂无日志",
    showHeader: Boolean = true,
) {
    val context = LocalContext.current
    val ext = HabitatTheme.extendedColors
    val listState = rememberLazyListState()
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null || last.index >= info.totalItemsCount - 2
        }
    }
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty() && atBottom) listState.animateScrollToItem(lines.size - 1)
    }

    Surface(modifier = modifier, shape = MaterialTheme.shapes.medium, color = ext.codeBackground) {
        Column(Modifier.fillMaxSize()) {
            if (showHeader) {
                Row(
                    Modifier.fillMaxWidth().padding(start = Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Terminal, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(Spacing.sm))
                    Text(
                        title,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (lines.isNotEmpty()) {
                        Text(
                            "${lines.size} 行",
                            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    IconButton(onClick = { copyToClipboard(context, lines.joinToString("\n"), "日志") }, enabled = lines.isNotEmpty()) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = "复制日志", modifier = Modifier.size(18.dp))
                    }
                    if (onClear != null) {
                        IconButton(onClick = onClear, enabled = lines.isNotEmpty()) {
                            Icon(Icons.Outlined.DeleteSweep, contentDescription = "清空日志", modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
            if (lines.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(Spacing.lg), contentAlignment = Alignment.Center) {
                    Text(emptyText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, textAlign = TextAlign.Center)
                }
            } else {
                SelectionContainer {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.md),
                        verticalArrangement = Arrangement.spacedBy(1.dp),
                    ) {
                        itemsIndexed(lines) { _, line ->
                            Text(line, style = HabitatTextStyles.codeSmall, color = logLevelColor(logLevelOf(line)))
                        }
                        item { Spacer(Modifier.padding(bottom = Spacing.sm)) }
                    }
                }
            }
        }
    }
}
