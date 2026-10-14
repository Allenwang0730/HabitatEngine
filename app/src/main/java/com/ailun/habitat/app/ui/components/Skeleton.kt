package com.ailun.habitat.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ailun.habitat.app.Spacing

/** 骨架屏微光：底色 surfaceContainerHigh，高光斜向扫过。 */
fun Modifier.shimmer(): Modifier = composed {
    val base = MaterialTheme.colorScheme.surfaceContainerHighest
    val highlight = MaterialTheme.colorScheme.surfaceContainerLow
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(
        initialValue = -1f, targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart),
        label = "x",
    )
    this.background(
        Brush.linearGradient(
            colors = listOf(base, highlight, base),
            start = Offset(x * 600f, 0f),
            end = Offset(x * 600f + 400f, 200f),
        ),
    )
}

@Composable
fun SkeletonBox(
    modifier: Modifier = Modifier,
    width: Dp? = null,
    height: Dp = 14.dp,
    shape: Shape = MaterialTheme.shapes.extraSmall,
) {
    val w = if (width != null) Modifier.width(width) else Modifier.fillMaxWidth()
    Box(modifier.then(w).height(height).clip(shape).shimmer())
}

/** 列表卡片骨架。 */
@Composable
fun SkeletonCard(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().semantics { contentDescription = "加载中" },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Row {
                Box(Modifier.size(40.dp).clip(MaterialTheme.shapes.small).shimmer())
                Spacer(Modifier.width(Spacing.md))
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    SkeletonBox(width = 160.dp, height = 16.dp)
                    SkeletonBox(width = 220.dp, height = 12.dp)
                }
            }
            SkeletonBox(width = 120.dp, height = 12.dp)
        }
    }
}

@Composable
fun SkeletonList(count: Int = 4, modifier: Modifier = Modifier, horizontalPadding: Dp = Spacing.page) {
    Column(modifier.padding(horizontal = horizontalPadding), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        repeat(count) { SkeletonCard() }
    }
}
