package com.ailun.habitat.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ailun.habitat.app.HabitatTheme
import com.ailun.habitat.app.Spacing

/** 状态胶囊的语义种类。文字始终可见（不只靠颜色区分）。 */
enum class PillStatus { Running, Enabled, Waiting, Success, Warning, Failed, Idle }

private data class PillColors(val fg: Color, val bg: Color)

@Composable
private fun pillColors(status: PillStatus): PillColors {
    val ext = HabitatTheme.extendedColors
    val cs = MaterialTheme.colorScheme
    return when (status) {
        PillStatus.Running, PillStatus.Success -> PillColors(ext.onSuccessContainer, ext.successContainer)
        PillStatus.Enabled -> PillColors(cs.onPrimaryContainer, cs.primaryContainer)
        PillStatus.Waiting, PillStatus.Warning -> PillColors(ext.onWarningContainer, ext.warningContainer)
        PillStatus.Failed -> PillColors(cs.onErrorContainer, cs.errorContainer)
        PillStatus.Idle -> PillColors(cs.onSurfaceVariant, cs.surfaceContainerHighest)
    }
}

@Composable
private fun dotColor(status: PillStatus): Color {
    val ext = HabitatTheme.extendedColors
    val cs = MaterialTheme.colorScheme
    return when (status) {
        PillStatus.Running, PillStatus.Success -> ext.success
        PillStatus.Enabled -> cs.primary
        PillStatus.Waiting, PillStatus.Warning -> ext.warning
        PillStatus.Failed -> cs.error
        PillStatus.Idle -> cs.outline
    }
}

/** 呼吸点：运行中时缩放+透明度脉动。 */
@Composable
fun StatusDot(status: PillStatus, modifier: Modifier = Modifier, size: Int = 8) {
    val color by animateColorAsState(dotColor(status), label = "dot")
    if (status == PillStatus.Running) {
        val t = rememberInfiniteTransition(label = "breath")
        val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
        Box(modifier.size(size.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(size.dp)
                    .graphicsLayer { scaleX = 1f + (1f - a) * 0.9f; scaleY = scaleX; alpha = (1f - a) * 0.6f }
                    .clip(CircleShape).background(color),
            )
            Box(Modifier.size(size.dp).graphicsLayer { alpha = 0.55f + a * 0.45f }.clip(CircleShape).background(color))
        }
    } else {
        Box(modifier.size(size.dp).clip(CircleShape).background(color))
    }
}

@Composable
fun StatusPill(
    status: PillStatus,
    text: String,
    modifier: Modifier = Modifier,
) {
    val colors = pillColors(status)
    val bg by animateColorAsState(colors.bg, label = "pillBg")
    val fg by animateColorAsState(colors.fg, label = "pillFg")
    Surface(
        modifier = modifier.semantics { contentDescription = "状态：$text" },
        shape = CircleShape,
        color = bg,
    ) {
        Row(
            Modifier.padding(start = Spacing.sm, end = Spacing.sm + 2.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(status, size = 6)
            Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1)
        }
    }
}
