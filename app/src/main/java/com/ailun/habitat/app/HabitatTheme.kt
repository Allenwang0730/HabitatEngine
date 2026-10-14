package com.ailun.habitat.app

import android.content.Context
import android.os.Build
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ailun.habitat.app.ui.theme.NodeCategory

// ════════════════════════════════════════════════════════════════════
// 颜色：Teal 品牌 + 冷灰中性色。浅色纸白、深色为带蓝调的墨色（非纯黑）。
// ════════════════════════════════════════════════════════════════════

private val HabitatLight = lightColorScheme(
    primary = Color(0xFF0B7A70), onPrimary = Color.White,
    primaryContainer = Color(0xFFC9F1EA), onPrimaryContainer = Color(0xFF00201C),
    inversePrimary = Color(0xFF5FDBC9),
    secondary = Color(0xFF4A635F), onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCE8E2), onSecondaryContainer = Color(0xFF05201C),
    tertiary = Color(0xFF3F5E91), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD7E3FF), onTertiaryContainer = Color(0xFF001B3E),
    error = Color(0xFFBA1A1A), onError = Color.White,
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF7F9FA), onBackground = Color(0xFF171C1F),
    surface = Color(0xFFF7F9FA), onSurface = Color(0xFF171C1F),
    surfaceVariant = Color(0xFFDDE3E6), onSurfaceVariant = Color(0xFF41484C),
    surfaceTint = Color(0xFF0B7A70),
    inverseSurface = Color(0xFF2C3134), inverseOnSurface = Color(0xFFEEF1F3),
    outline = Color(0xFF71787C), outlineVariant = Color(0xFFC6CDD1),
    scrim = Color.Black,
    surfaceDim = Color(0xFFD7DADC), surfaceBright = Color(0xFFF7F9FA),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF1F4F5),
    surfaceContainer = Color(0xFFEBEEF0), surfaceContainerHigh = Color(0xFFE5E8EA),
    surfaceContainerHighest = Color(0xFFDFE3E5),
)

private val HabitatDark = darkColorScheme(
    primary = Color(0xFF5FDBC9), onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF005048), onPrimaryContainer = Color(0xFFA6F5E7),
    inversePrimary = Color(0xFF0B7A70),
    secondary = Color(0xFFB0CCC6), onSecondary = Color(0xFF1B3531),
    secondaryContainer = Color(0xFF324B47), onSecondaryContainer = Color(0xFFCCE8E2),
    tertiary = Color(0xFFABC7FF), onTertiary = Color(0xFF0A305F),
    tertiaryContainer = Color(0xFF274777), onTertiaryContainer = Color(0xFFD7E3FF),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0F1417), onBackground = Color(0xFFDFE3E6),
    surface = Color(0xFF0F1417), onSurface = Color(0xFFDFE3E6),
    surfaceVariant = Color(0xFF41484C), onSurfaceVariant = Color(0xFFC1C8CC),
    surfaceTint = Color(0xFF5FDBC9),
    inverseSurface = Color(0xFFDFE3E6), inverseOnSurface = Color(0xFF2C3134),
    outline = Color(0xFF8B9296), outlineVariant = Color(0xFF41484C),
    scrim = Color.Black,
    surfaceDim = Color(0xFF0F1417), surfaceBright = Color(0xFF353A3D),
    surfaceContainerLowest = Color(0xFF0A0F12), surfaceContainerLow = Color(0xFF171C1F),
    surfaceContainer = Color(0xFF1B2023), surfaceContainerHigh = Color(0xFF252B2E),
    surfaceContainerHighest = Color(0xFF303538),
)

/** 语义色扩展：成功/警告/信息 + 各自 container，以及代码区底色。 */
@Immutable
data class ExtendedColors(
    val success: Color, val onSuccess: Color, val successContainer: Color, val onSuccessContainer: Color,
    val warning: Color, val onWarning: Color, val warningContainer: Color, val onWarningContainer: Color,
    val info: Color, val onInfo: Color, val infoContainer: Color, val onInfoContainer: Color,
    val codeBackground: Color, val codeForeground: Color, val gridDot: Color,
)

private val LightExtended = ExtendedColors(
    success = Color(0xFF1B7F3B), onSuccess = Color.White, successContainer = Color(0xFFD3F5D9), onSuccessContainer = Color(0xFF00210B),
    warning = Color(0xFF8A5100), onWarning = Color.White, warningContainer = Color(0xFFFFE0B8), onWarningContainer = Color(0xFF2C1600),
    info = Color(0xFF2F5FA8), onInfo = Color.White, infoContainer = Color(0xFFD9E4FF), onInfoContainer = Color(0xFF001A42),
    codeBackground = Color(0xFFF1F4F5), codeForeground = Color(0xFF1F2A30), gridDot = Color(0xFFC6CDD1),
)

private val DarkExtended = ExtendedColors(
    success = Color(0xFF7DDC93), onSuccess = Color(0xFF00391A), successContainer = Color(0xFF0E5227), onSuccessContainer = Color(0xFFD3F5D9),
    warning = Color(0xFFFFB960), onWarning = Color(0xFF482900), warningContainer = Color(0xFF673D00), onWarningContainer = Color(0xFFFFE0B8),
    info = Color(0xFFAEC6FF), onInfo = Color(0xFF002E6A), infoContainer = Color(0xFF1B4588), onInfoContainer = Color(0xFFD9E4FF),
    codeBackground = Color(0xFF0A0F12), codeForeground = Color(0xFFD5DCE0), gridDot = Color(0xFF2A3135),
)

/** 节点类别色（DAG 与列表共用）：accent 用于色条/图标，container 用于图标底。 */
@Immutable
data class NodeCategoryColor(val accent: Color, val container: Color)

@Immutable
class NodeCategoryColors(private val map: Map<NodeCategory, NodeCategoryColor>) {
    operator fun get(category: NodeCategory): NodeCategoryColor = map[category] ?: map.getValue(NodeCategory.UTILITY)
}

private fun categoryColors(dark: Boolean): NodeCategoryColors {
    fun c(light: Long, darkAccent: Long) = if (dark) {
        NodeCategoryColor(Color(darkAccent), Color(darkAccent).copy(alpha = 0.16f))
    } else {
        NodeCategoryColor(Color(light), Color(light).copy(alpha = 0.12f))
    }
    return NodeCategoryColors(
        mapOf(
            NodeCategory.TRIGGER to c(0xFF0B7A70, 0xFF5FDBC9),
            NodeCategory.LOGIC to c(0xFFB26A00, 0xFFFFB960),
            NodeCategory.DATA to c(0xFF6B4FBB, 0xFFC4B2FF),
            NodeCategory.INTERACTION to c(0xFFC2410C, 0xFFFFA27A),
            NodeCategory.FEEDBACK to c(0xFFA1447A, 0xFFF4A7D2),
            NodeCategory.SYSTEM to c(0xFF1F6FB2, 0xFF8CC8FF),
            NodeCategory.TELEPHONY to c(0xFF2E7D32, 0xFF8FD694),
            NodeCategory.MEDIA to c(0xFFC0265A, 0xFFFF8FB1),
            NodeCategory.NETWORK to c(0xFF00838F, 0xFF6ED6E3),
            NodeCategory.SENSOR to c(0xFF5B6B00, 0xFFC3D66B),
            NodeCategory.AI to c(0xFF7B3FC4, 0xFFD9B8FF),
            NodeCategory.UTILITY to c(0xFF56636B, 0xFFA9B6BE),
        ),
    )
}

private val LocalExtendedColors = staticCompositionLocalOf { LightExtended }
private val LocalNodeCategoryColors = staticCompositionLocalOf { categoryColors(false) }
private val LocalIsDark = staticCompositionLocalOf { false }

// ════════════════════════════════════════════════════════════════════
// 排版：中文正文行高放宽（约 1.5×），标题更紧；字距统一为 0。
// ════════════════════════════════════════════════════════════════════

private val CjkPlatformStyle = PlatformTextStyle(includeFontPadding = false)
private val CjkLineHeight = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

private fun style(size: Int, line: Int, weight: FontWeight) = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = 0.sp,
    platformStyle = CjkPlatformStyle,
    lineHeightStyle = CjkLineHeight,
)

private val HabitatTypography = Typography(
    displayLarge = style(52, 60, FontWeight.SemiBold),
    displayMedium = style(42, 50, FontWeight.SemiBold),
    displaySmall = style(34, 42, FontWeight.SemiBold),
    headlineLarge = style(30, 38, FontWeight.SemiBold),
    headlineMedium = style(26, 34, FontWeight.SemiBold),
    headlineSmall = style(22, 30, FontWeight.SemiBold),
    titleLarge = style(20, 28, FontWeight.SemiBold),
    titleMedium = style(16, 24, FontWeight.SemiBold),
    titleSmall = style(14, 20, FontWeight.SemiBold),
    bodyLarge = style(16, 25, FontWeight.Normal),
    bodyMedium = style(14, 22, FontWeight.Normal),
    bodySmall = style(12, 18, FontWeight.Normal),
    labelLarge = style(14, 20, FontWeight.Medium),
    labelMedium = style(12, 16, FontWeight.Medium),
    labelSmall = style(11, 16, FontWeight.Medium),
)

/** 等宽数字（计数/时间/端口）。 */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")

/** 代码 / JSON / 日志用字体样式。 */
object HabitatTextStyles {
    val code = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.5.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.sp,
        platformStyle = CjkPlatformStyle,
    )
    val codeSmall = code.copy(fontSize = 11.sp, lineHeight = 16.sp)
}

// ════════════════════════════════════════════════════════════════════
// 形状 / 间距 / 动效令牌
// ════════════════════════════════════════════════════════════════════

private val HabitatShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** 4pt 网格间距。 */
object Spacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val xxxl = 48.dp

    /** 页面水平边距。 */
    val page = 16.dp
    /** 最小触控目标。 */
    val touch = 48.dp
}

/** 统一动效规格：标准（大多数状态变化）、强调（进入/重要反馈）、退出（快速离场）。 */
object Motion {
    val EmphasizedEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val StandardEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val ExitEasing = CubicBezierEasing(0.3f, 0f, 1f, 1f)

    const val DurationShort = 150
    const val DurationMedium = 250
    const val DurationLong = 400

    fun <T> standard(): FiniteAnimationSpec<T> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
    fun <T> emphasized(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow)
    fun <T> exit(): FiniteAnimationSpec<T> = tween(DurationShort, easing = ExitEasing)
    fun <T> fade(): FiniteAnimationSpec<T> = tween(DurationMedium, easing = StandardEasing)
    val offset: FiniteAnimationSpec<IntOffset> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
}

// ════════════════════════════════════════════════════════════════════
// 主题入口
// ════════════════════════════════════════════════════════════════════

/** 主题偏好（动态取色）。可观察：切换后所有 HabitatTheme 立即重组。 */
object ThemeSettings {
    private const val PREFS = "habitat_prefs"
    private const val KEY_DYNAMIC = "dynamicColorEnabled"
    private var cached by mutableStateOf<Boolean?>(null)

    val dynamicColorSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    fun isDynamicColor(context: Context): Boolean =
        cached ?: context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DYNAMIC, false).also { cached = it }

    fun setDynamicColor(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DYNAMIC, enabled).apply()
        cached = enabled
    }
}

/** Habitat 青绿色系主题（保留旧调用方式）。 */
@Composable
fun habitatColorScheme(darkTheme: Boolean? = null): ColorScheme {
    val isDark = darkTheme ?: isSystemInDarkTheme()
    val context = LocalContext.current
    return when {
        ThemeSettings.dynamicColorSupported && ThemeSettings.isDynamicColor(context) ->
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        isDark -> HabitatDark
        else -> HabitatLight
    }
}

@Composable
fun HabitatTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(
        LocalExtendedColors provides if (dark) DarkExtended else LightExtended,
        LocalNodeCategoryColors provides categoryColors(dark),
        LocalIsDark provides dark,
    ) {
        MaterialTheme(
            colorScheme = habitatColorScheme(dark),
            typography = HabitatTypography,
            shapes = HabitatShapes,
            content = content,
        )
    }
}

/** 访问扩展令牌：`HabitatTheme.extendedColors.success` 等。 */
object HabitatTheme {
    val extendedColors: ExtendedColors
        @Composable @ReadOnlyComposable get() = LocalExtendedColors.current
    val nodeColors: NodeCategoryColors
        @Composable @ReadOnlyComposable get() = LocalNodeCategoryColors.current
    val isDark: Boolean
        @Composable @ReadOnlyComposable get() = LocalIsDark.current
}
