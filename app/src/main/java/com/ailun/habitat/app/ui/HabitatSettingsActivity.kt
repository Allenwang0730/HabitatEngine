package com.ailun.habitat.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.BatterySaver
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.ailun.habitat.app.HabitatLogger
import com.ailun.habitat.app.HabitatTheme
import com.ailun.habitat.app.Spacing
import com.ailun.habitat.app.bridge.ShizukuShellExecutor
import com.ailun.habitat.app.tabular
import com.ailun.habitat.app.ui.components.PillStatus
import com.ailun.habitat.app.ui.components.SettingRow
import com.ailun.habitat.app.ui.components.SettingsDivider
import com.ailun.habitat.app.ui.components.SettingsGroup
import com.ailun.habitat.app.ui.components.StatusPill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

class HabitatSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HabitatTheme {
                HabitatSettingsScreen(onBack = { finish() })
            }
        }
    }
}

data class PermissionItem(
    val id: String,
    val name: String,
    val description: String,
    val icon: ImageVector,
    val isGranted: suspend (Context) -> Boolean,
    val createRequestIntent: (Context) -> Intent?,
    /** 运行时权限（非 null 时用 RequestPermission 申请，而不是跳设置页）。 */
    val runtimePermission: String? = null,
)

internal fun buildPermissionItems(): List<PermissionItem> = buildList {
    add(
        PermissionItem(
            id = "accessibility",
            name = "无障碍服务",
            description = "读取屏幕内容、查找界面元素、模拟点击和手势",
            icon = Icons.Outlined.Accessibility,
            isGranted = { ctx ->
                withContext(Dispatchers.IO) {
                    runCatching {
                        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                            ?.contains(ctx.packageName) == true
                    }.getOrDefault(false)
                }
            },
            createRequestIntent = { Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS) },
        ),
    )
    add(
        PermissionItem(
            id = "overlay",
            name = "悬浮窗",
            description = "在其他应用上方显示悬浮控制面板",
            icon = Icons.Outlined.PictureInPictureAlt,
            isGranted = { ctx -> Settings.canDrawOverlays(ctx) },
            createRequestIntent = { ctx -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")) },
        ),
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(
            PermissionItem(
                id = "post_notifications",
                name = "发送通知",
                description = "显示前台服务通知、工作流发出的通知与运行状态",
                icon = Icons.Outlined.NotificationsActive,
                isGranted = { ctx -> ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED },
                createRequestIntent = { ctx ->
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                },
                runtimePermission = Manifest.permission.POST_NOTIFICATIONS,
            ),
        )
    }
    add(
        PermissionItem(
            id = "notification_listener",
            name = "读取通知",
            description = "监听系统通知作为工作流触发器（如收到验证码时执行）",
            icon = Icons.Outlined.Visibility,
            isGranted = { ctx ->
                withContext(Dispatchers.IO) {
                    runCatching {
                        Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")?.contains(ctx.packageName) == true
                    }.getOrDefault(false)
                }
            },
            createRequestIntent = { Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) },
        ),
    )
    add(
        PermissionItem(
            id = "shizuku",
            name = "Shizuku 服务",
            description = "提供 Shell 级系统命令能力（WiFi、截图等），无需 Root",
            icon = Icons.Outlined.Terminal,
            isGranted = { withContext(Dispatchers.IO) { runCatching { Shizuku.pingBinder() }.getOrDefault(false) } },
            createRequestIntent = { ctx ->
                runCatching { ctx.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api") }.getOrNull()
            },
        ),
    )
    add(
        PermissionItem(
            id = "battery",
            name = "电池优化白名单",
            description = "防止系统在后台停止工作流和悬浮窗服务",
            icon = Icons.Outlined.BatterySaver,
            isGranted = { ctx ->
                withContext(Dispatchers.IO) {
                    (ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isIgnoringBatteryOptimizations(ctx.packageName) ?: false
                }
            },
            createRequestIntent = { ctx ->
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
            },
        ),
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        add(
            PermissionItem(
                id = "storage",
                name = "所有文件访问",
                description = "读写工作流文件、导出截图与日志",
                icon = Icons.Outlined.Folder,
                isGranted = { android.os.Environment.isExternalStorageManager() },
                createRequestIntent = { ctx ->
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                },
            ),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HabitatSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val items = remember { buildPermissionItems() }
    var states by remember { mutableStateOf<Map<String, Boolean>?>(null) }
    var autoGranting by remember { mutableStateOf(false) }

    suspend fun refresh() {
        states = items.associate { it.id to it.isGranted(context) }
    }

    // 从系统设置页返回时（onResume）重新检测
    LifecycleResumeEffect(Unit) {
        val job = scope.launch { refresh() }
        onPauseOrDispose { job.cancel() }
    }

    val runtimeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        scope.launch { refresh() }
    }

    fun request(item: PermissionItem) {
        val rt = item.runtimePermission
        if (rt != null) {
            runtimeLauncher.launch(rt)
            return
        }
        val intent = item.createRequestIntent(context)
        if (intent == null) {
            Toast.makeText(context, "${item.name}：无法跳转，请手动前往系统设置", Toast.LENGTH_LONG).show()
            return
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "${item.name}：无法打开设置页", Toast.LENGTH_LONG).show()
        }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("权限管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
                actions = {
                    IconButton(onClick = { scope.launch { refresh() } }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "重新检测")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        val s = states
        val granted = s?.count { it.value } ?: 0
        val total = items.size
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = Spacing.page, end = Spacing.page,
                top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + Spacing.xl,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item {
                val allDone = s != null && granted == total
                val progress by animateFloatAsState(if (total == 0) 0f else granted / total.toFloat(), label = "perm")
                val ext = HabitatTheme.extendedColors
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = if (allDone) ext.successContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(Spacing.xl), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    when {
                                        s == null -> "正在检测…"
                                        allDone -> "所有权限已就绪"
                                        else -> "还需授权 ${total - granted} 项"
                                    },
                                    style = MaterialTheme.typography.titleLarge,
                                    color = if (allDone) ext.onSuccessContainer else MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    "授权后返回此页会自动刷新状态",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text("$granted/$total", style = MaterialTheme.typography.headlineMedium.tabular())
                        }
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }

            item {
                SettingsGroup {
                    items.forEachIndexed { i, item ->
                        if (i > 0) SettingsDivider()
                        val ok = s?.get(item.id)
                        SettingRow(
                            title = item.name,
                            subtitle = item.description,
                            icon = item.icon,
                            iconTint = if (ok == true) HabitatTheme.extendedColors.success else MaterialTheme.colorScheme.primary,
                            onClick = if (ok == true) null else ({ request(item) }),
                            trailing = {
                                when (ok) {
                                    null -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    true -> StatusPill(PillStatus.Success, "已授权")
                                    false -> FilledTonalButton(onClick = { request(item) }, contentPadding = PaddingValues(horizontal = Spacing.md)) {
                                        Text("去授权")
                                    }
                                }
                            },
                        )
                    }
                }
            }

            item {
                OutlinedButton(
                    onClick = {
                        autoGranting = true
                        scope.launch {
                            autoGrantPermissions(context, items)
                            refresh()
                            autoGranting = false
                            Toast.makeText(context, "已尝试通过 Shell 授权", Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = !autoGranting,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    if (autoGranting) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Outlined.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(Spacing.sm))
                    Text("通过 Shizuku / Root 一键授权")
                }
                Text(
                    "可自动处理悬浮窗、电池白名单、通知读取；无障碍与文件访问仍需手动开启。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.sm, start = Spacing.xs, end = Spacing.xs),
                )
            }
            item { Spacer(Modifier.height(Spacing.lg)) }
        }
    }
}

private suspend fun autoGrantPermissions(context: Context, items: List<PermissionItem>) {
    val executor = ShizukuShellExecutor(context)
    withContext(Dispatchers.IO) {
        for (item in items) {
            try {
                when (item.id) {
                    "overlay" -> executor.exec("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow", true)
                    "battery" -> executor.exec("dumpsys deviceidle whitelist +${context.packageName}", true)
                    "notification_listener" -> {
                        // allow_listener 需要完整组件名（包名/类名），只给包名不生效
                        val component = android.content.ComponentName(context, com.ailun.habitat.app.HabitatNotificationService::class.java).flattenToString()
                        executor.exec("cmd notification allow_listener $component", true)
                    }
                    "post_notifications" -> executor.exec("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS", true)
                    else -> {}
                }
            } catch (e: Exception) {
                HabitatLogger.w("HabitatSettings", "Auto-grant failed for ${item.id}: ${e.message}")
            }
        }
    }
}
