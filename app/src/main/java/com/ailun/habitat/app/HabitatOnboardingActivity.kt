package com.ailun.habitat.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.IntegrationInstructions
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.ailun.habitat.app.ui.PermissionItem
import com.ailun.habitat.app.ui.buildPermissionItems
import com.ailun.habitat.app.ui.components.IconTile
import com.ailun.habitat.app.ui.components.PillStatus
import com.ailun.habitat.app.ui.components.StatusPill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HabitatOnboardingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HabitatTheme {
                OnboardingScreen(onFinish = { completeOnboarding() })
            }
        }
    }

    private fun completeOnboarding() {
        val prefs = getSharedPreferences(HabitatMainActivity.PREFS_NAME, MODE_PRIVATE)
        prefs.edit().putBoolean("is_first_run", false).apply()
        startActivity(Intent(this, HabitatMainActivity::class.java))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, android.R.anim.fade_in, android.R.anim.fade_out)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
        finish()
    }
}

private const val TOTAL_PAGES = 4

@Composable
private fun OnboardingScreen(onFinish: () -> Unit) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    BackHandler(enabled = page > 0) { page-- }

    Scaffold(
        topBar = {
            if (page < TOTAL_PAGES - 1) {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = Spacing.xl, vertical = Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PageIndicator(page, TOTAL_PAGES, Modifier.weight(1f))
                    TextButton(onClick = { page = TOTAL_PAGES - 1 }) { Text("跳过") }
                }
            }
        },
    ) { padding ->
        AnimatedContent(
            targetState = page,
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            transitionSpec = {
                val dir = if (targetState > initialState) 1 else -1
                (slideInHorizontally(Motion.offset) { dir * it / 5 } + fadeIn(Motion.fade()))
                    .togetherWith(slideOutHorizontally(Motion.offset) { -dir * it / 5 } + fadeOut(Motion.exit()))
            },
            label = "onboarding",
        ) { p ->
            when (p) {
                0 -> WelcomePage(onNext = { page = 1 })
                1 -> ShellSetupPage(onNext = { page = 2 })
                2 -> PermissionsSetupPage(onNext = { page = 3 })
                else -> CompletionPage(onFinish = onFinish)
            }
        }
    }
}

/** 分页指示器：当前页为胶囊，其余为圆点。 */
@Composable
private fun PageIndicator(current: Int, total: Int, modifier: Modifier = Modifier) {
    Row(
        modifier.semantics { contentDescription = "第 ${current + 1} 步，共 $total 步" },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(total) { i ->
            val w by animateDpAsState(if (i == current) 24.dp else 8.dp, Motion.emphasized(), label = "dotW")
            val c by animateColorAsState(
                when {
                    i == current -> MaterialTheme.colorScheme.primary
                    i < current -> MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                    else -> MaterialTheme.colorScheme.outlineVariant
                },
                label = "dotC",
            )
            Box(Modifier.height(8.dp).width(w).clip(CircleShape).background(c))
        }
    }
}

/** 插画式大图标：两层柔和同心圆。 */
@Composable
private fun HeroIllustration(icon: ImageVector, tint: Color = MaterialTheme.colorScheme.primary, size: Int = 132, modifier: Modifier = Modifier) {
    Box(modifier.size(size.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(size.dp).clip(CircleShape).background(tint.copy(alpha = 0.08f)))
        Box(Modifier.size((size * 0.7f).dp).clip(CircleShape).background(tint.copy(alpha = 0.14f)))
        Box(Modifier.size((size * 0.46f).dp).clip(CircleShape).background(tint), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size((size * 0.24f).dp))
        }
    }
}

/** 可滚动页面主体；内容不足一屏时 weight 仍生效（把主按钮推到底部）。 */
@Composable
private fun PageBody(content: @Composable ColumnScope.() -> Unit) {
    // 完成页没有顶栏，由这里补状态栏间距（有顶栏时 insets 已被消费，此处为 0）
    BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding()) {
        val minH = maxHeight
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Column(
                Modifier.fillMaxWidth().heightIn(min = minH).padding(horizontal = Spacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
                content = content,
            )
        }
    }
}

@Composable
private fun PrimaryAction(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = MaterialTheme.shapes.large,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(Spacing.sm))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
}

// ── Welcome ──

@Composable
private fun WelcomePage(onNext: () -> Unit) {
    val scale = remember { Animatable(0.85f) }
    LaunchedEffect(Unit) { scale.animateTo(1f, spring(0.55f, Spring.StiffnessLow)) }
    PageBody {
        Spacer(Modifier.height(Spacing.xxxl))
        HeroIllustration(Icons.Rounded.AutoAwesome, size = 160, modifier = Modifier.scale(scale.value))
        Spacer(Modifier.height(Spacing.xxl))
        Text("欢迎来到 Habitat", style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Spacing.md))
        Text(
            "把重复的手机操作编排成工作流，\n让它按时、按事件自动完成。",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.xxl))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            FeatureBadge(Icons.Rounded.FlashOn, "事件触发")
            FeatureBadge(Icons.Rounded.TouchApp, "模拟操作")
            FeatureBadge(Icons.Rounded.Security, "系统级")
        }
        Spacer(Modifier.weight(1f).heightIn(min = Spacing.xxl))
        PrimaryAction("开始配置", onClick = onNext)
        Spacer(Modifier.height(Spacing.xl))
    }
}

@Composable
private fun FeatureBadge(icon: ImageVector, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconTile(icon, size = 48)
        Spacer(Modifier.height(Spacing.sm))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ── Shell Setup ──

@Composable
private fun ShellSetupPage(onNext: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(HabitatMainActivity.PREFS_NAME, Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var mode by rememberSaveable { mutableStateOf("none") }
    var verified by rememberSaveable { mutableStateOf(false) }
    var verifying by remember { mutableStateOf(false) }
    var autoEnableAcc by rememberSaveable { mutableStateOf(false) }
    var keepAlive by rememberSaveable { mutableStateOf(false) }
    val ext = HabitatTheme.extendedColors

    fun verify() {
        verifying = true
        scope.launch {
            // isRootAvailable 会执行 su 并等待（Magisk 弹窗期间阻塞），必须在 IO 线程
            val ok = withContext(Dispatchers.IO) {
                if (mode == "shizuku") HabitatShellManager.isShizukuActive() else HabitatShellManager.isRootAvailable()
            }
            verifying = false
            verified = ok
            if (!ok) {
                Toast.makeText(context, if (mode == "shizuku") "Shizuku 未运行或未授权" else "未检测到 Root 权限", Toast.LENGTH_SHORT).show()
            }
        }
    }

    PageBody {
        Spacer(Modifier.height(Spacing.xl))
        HeroIllustration(Icons.Outlined.Terminal, size = 112)
        Spacer(Modifier.height(Spacing.xl))
        Text("选择 Shell 模式", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(Spacing.sm))
        Text(
            "通过 Shizuku 或 Root 执行系统级操作（WiFi、截图、强制停止等）",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.xl))

        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            ShellCard(Icons.Rounded.IntegrationInstructions, "Shizuku（推荐）", "安全高效，无需 Root", mode == "shizuku", MaterialTheme.colorScheme.primary) {
                mode = "shizuku"; verified = false
            }
            ShellCard(Icons.Rounded.Build, "Root", "完整的系统访问能力", mode == "root", ext.warning) {
                mode = "root"; verified = false
            }
            ShellCard(Icons.Outlined.Block, "暂不配置", "仅使用无障碍服务", mode == "none", MaterialTheme.colorScheme.outline) {
                mode = "none"; verified = true
            }
        }

        Spacer(Modifier.height(Spacing.lg))
        AnimatedVisibility(mode != "none" && !verified, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            OutlinedButton(
                onClick = ::verify,
                enabled = !verifying,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = MaterialTheme.shapes.large,
            ) {
                if (verifying) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(Spacing.sm))
                    Text(if (mode == "root") "检测中…（如有授权弹窗请允许）" else "检测中…")
                } else {
                    Icon(Icons.Outlined.VerifiedUser, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(Spacing.sm))
                    Text("验证连接")
                }
            }
        }
        AnimatedVisibility(verified && mode != "none", enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Surface(shape = MaterialTheme.shapes.large, color = ext.successContainer, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(Spacing.lg)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = ext.success)
                        Spacer(Modifier.width(Spacing.sm))
                        Text("连接验证成功", style = MaterialTheme.typography.titleSmall, color = ext.onSuccessContainer)
                    }
                    Spacer(Modifier.height(Spacing.sm))
                    CheckRow("自动维持无障碍服务开启", autoEnableAcc) { autoEnableAcc = it }
                    if (mode == "shizuku") CheckRow("保持后台服务存活", keepAlive) { keepAlive = it }
                }
            }
        }

        Spacer(Modifier.weight(1f).heightIn(min = Spacing.xl))
        val canProceed = mode == "none" || verified
        PrimaryAction(if (mode == "none") "跳过，继续" else "保存并继续", enabled = canProceed) {
            prefs.edit()
                .putString("default_shell_mode", mode)
                .putBoolean("autoEnableAccessibility", autoEnableAcc)
                .putBoolean("forceKeepAliveEnabled", keepAlive)
                .apply()
            onNext()
        }
        Spacer(Modifier.height(Spacing.xl))
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Checkbox) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(Spacing.sm))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ShellCard(icon: ImageVector, title: String, subtitle: String, selected: Boolean, accent: Color, onClick: () -> Unit) {
    val border by animateColorAsState(if (selected) accent else MaterialTheme.colorScheme.outlineVariant, label = "b")
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = if (selected) accent.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(if (selected) 2.dp else 1.dp, border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            IconTile(icon, tint = accent, size = 44)
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            RadioButton(selected = selected, onClick = null)
        }
    }
}

// ── Permissions ──

private val ONBOARDING_PERMISSION_IDS = listOf("notification_listener", "post_notifications", "battery", "overlay")

@Composable
private fun PermissionsSetupPage(onNext: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val items = remember {
        val all = buildPermissionItems().associateBy { it.id }
        ONBOARDING_PERMISSION_IDS.mapNotNull { all[it] }
    }
    var granted by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var clickCount by remember { mutableIntStateOf(0) }
    var lastClick by remember { mutableLongStateOf(0L) }

    suspend fun refresh() { granted = items.associate { it.id to it.isGranted(context) } }

    LifecycleResumeEffect(Unit) {
        val job = scope.launch { refresh() }
        onPauseOrDispose { job.cancel() }
    }
    val runtimeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        scope.launch { refresh() }
    }

    fun request(item: PermissionItem) {
        val rt = item.runtimePermission
        if (rt != null) { runtimeLauncher.launch(rt); return }
        val intent = item.createRequestIntent(context) ?: return
        try { context.startActivity(intent) } catch (_: Exception) {
            Toast.makeText(context, "无法打开设置页，请手动前往系统设置", Toast.LENGTH_LONG).show()
        }
    }

    val grantedCount = granted.count { it.value }
    val allGranted = granted.size == items.size && grantedCount == items.size

    PageBody {
        Spacer(Modifier.height(Spacing.xl))
        HeroIllustration(
            Icons.Outlined.Shield,
            tint = if (allGranted) HabitatTheme.extendedColors.success else MaterialTheme.colorScheme.primary,
            size = 112,
            modifier = Modifier.clickable(indication = null, interactionSource = null) {
                // 隐藏入口：2 秒内连点 5 次直接进入下一步
                val now = System.currentTimeMillis()
                clickCount = if (now - lastClick > 2000) 1 else clickCount + 1
                lastClick = now
                if (clickCount >= 5) onNext()
            },
        )
        Spacer(Modifier.height(Spacing.xl))
        Text("授予必要权限", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(Spacing.sm))
        Text(
            "授权后返回这里，状态会自动更新（$grantedCount/${items.size}）",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.xl))

        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            items.forEach { item ->
                val ok = granted[item.id] == true
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = if (ok) HabitatTheme.extendedColors.successContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
                        IconTile(item.icon, tint = if (ok) HabitatTheme.extendedColors.success else MaterialTheme.colorScheme.primary, size = 40)
                        Spacer(Modifier.width(Spacing.md))
                        Column(Modifier.weight(1f)) {
                            Text(item.name, style = MaterialTheme.typography.titleSmall)
                            Text(item.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(Spacing.sm))
                        if (ok) StatusPill(PillStatus.Success, "已授予")
                        else FilledTonalButton(onClick = { request(item) }, contentPadding = PaddingValues(horizontal = Spacing.md)) { Text("去授权") }
                    }
                }
            }
        }

        Spacer(Modifier.weight(1f).heightIn(min = Spacing.xl))
        PrimaryAction(if (allGranted) "全部完成，继续" else "稍后再说，继续", onClick = onNext)
        Spacer(Modifier.height(Spacing.xl))
    }
}

// ── Completion ──

@Composable
private fun CompletionPage(onFinish: () -> Unit) {
    val scale = remember { Animatable(0f) }
    LaunchedEffect(Unit) { scale.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow)) }
    PageBody {
        Spacer(Modifier.weight(1f).heightIn(min = Spacing.xxxl))
        HeroIllustration(Icons.Outlined.Check, tint = HabitatTheme.extendedColors.success, size = 160, modifier = Modifier.scale(scale.value))
        Spacer(Modifier.height(Spacing.xxl))
        Text("引擎就绪", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Spacing.md))
        Text(
            "随时可以在「我的 › 权限管理」里补全授权。\n现在就开始创建第一个工作流吧。",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.weight(1f).heightIn(min = Spacing.xxl))
        PrimaryAction("进入 Habitat", onClick = onFinish)
        Spacer(Modifier.height(Spacing.xl))
    }
}
