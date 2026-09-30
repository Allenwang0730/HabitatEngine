package com.ailun.habitat.app.ui

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ailun.habitat.BuildConfig
import com.ailun.habitat.HabitatStateStore
import com.ailun.habitat.app.HabitatTheme
import com.ailun.habitat.app.Spacing
import com.ailun.habitat.app.ThemeSettings
import com.ailun.habitat.app.auth.AuthStateManager
import com.ailun.habitat.app.network.HabitatApiClient
import com.ailun.habitat.app.tabular
import com.ailun.habitat.app.ui.components.SectionHeader
import com.ailun.habitat.app.ui.components.SettingRow
import com.ailun.habitat.app.ui.components.SettingsDivider
import com.ailun.habitat.app.ui.components.SettingsGroup
import com.ailun.habitat.app.ui.viewmodel.DashboardViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

// ── Preferences keys ──
private const val TAG = "ProfileTab"
private const val PREFS_PROFILE = "habitat_profile"
private const val KEY_SERVER_URL = "server_url"

object ProfilePrefs {
    fun getServerUrl(ctx: Context): String =
        ctx.getSharedPreferences(PREFS_PROFILE, Context.MODE_PRIVATE)
            .getString(KEY_SERVER_URL, "http://10.0.2.2:8080") ?: "http://10.0.2.2:8080"

    fun setServerUrl(ctx: Context, url: String) {
        ctx.getSharedPreferences(PREFS_PROFILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_SERVER_URL, url).apply()
    }

    /** 明文 http 且不是本机/局域网/模拟器地址 → 需要提示风险。 */
    fun isInsecureRemote(url: String): Boolean {
        val uri = try { Uri.parse(url.trim()) } catch (_: Exception) { return false }
        if (!uri.scheme.equals("http", ignoreCase = true)) return false
        val host = uri.host?.lowercase() ?: return false
        if (host == "localhost" || host == "10.0.2.2" || host.startsWith("127.") || host == "::1") return false
        if (host.startsWith("10.") || host.startsWith("192.168.") || host.endsWith(".local")) return false
        val m = Regex("""^172\.(\d+)\.""").find(host)
        if (m != null && m.groupValues[1].toInt() in 16..31) return false
        return true
    }
}

private sealed class ConnectionState {
    data object Unknown : ConnectionState()
    data object Testing : ConnectionState()
    data object Connected : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileTab(
    isFloatServiceRunning: Boolean,
    onFloatServiceToggle: (Boolean) -> Unit,
    onNavigateToLLM: () -> Unit,
    onNavigateToPermissions: () -> Unit,
    onNavigateToWorkflowAI: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dashboard = remember {
        ViewModelProvider(context as androidx.activity.ComponentActivity)[DashboardViewModel::class.java]
    }

    var serverUrl by remember { mutableStateOf(ProfilePrefs.getServerUrl(context)) }
    var showServerDialog by remember { mutableStateOf(false) }
    var connection by remember { mutableStateOf<ConnectionState>(ConnectionState.Unknown) }
    var showLogin by remember { mutableStateOf(false) }
    var showRegister by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var dynamicColor by remember { mutableStateOf(ThemeSettings.isDynamicColor(context)) }

    val isLoggedIn by AuthStateManager.isLoggedIn.collectAsStateWithLifecycle()
    val name = AuthStateManager.username ?: "Habitat 用户"

    val items by dashboard.items.collectAsStateWithLifecycle()
    val mounted by dashboard.mountedIds.collectAsStateWithLifecycle()
    val running by HabitatStateStore.running.collectAsStateWithLifecycle()

    fun testConnection(url: String) {
        scope.launch {
            connection = ConnectionState.Testing
            connection = try {
                val code = withContext(Dispatchers.IO) {
                    val conn = URL("${url.trimEnd('/')}/actuator/health").openConnection() as HttpURLConnection
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    try { conn.responseCode } finally { conn.disconnect() }
                }
                if (code == 200) ConnectionState.Connected else ConnectionState.Error("HTTP $code")
            } catch (e: Exception) {
                Log.w(TAG, "Connection test failed: ${e.message}")
                ConnectionState.Error(e.message ?: "连接失败")
            }
        }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { LargeTopAppBar(title = { Text("我的") }, scrollBehavior = scrollBehavior) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = Spacing.page, end = Spacing.page,
                top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + Spacing.xl,
            ),
        ) {
            item {
                ProfileHero(
                    name = name,
                    loggedIn = isLoggedIn,
                    onLogin = { showLogin = true },
                    onRegister = { showRegister = true },
                    onLogout = { AuthStateManager.logout(); HabitatApiClient.init(serverUrl) },
                )
            }
            item {
                Row(Modifier.fillMaxWidth().padding(top = Spacing.md), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    StatTile(Modifier.weight(1f), Icons.Outlined.AccountTree, "${items.size}", "工作流", MaterialTheme.colorScheme.primary)
                    StatTile(Modifier.weight(1f), Icons.Outlined.PlayCircle, "${running.size}", "运行中", HabitatTheme.extendedColors.success)
                    StatTile(Modifier.weight(1f), Icons.Outlined.PushPin, "${mounted.size}", "已挂载", MaterialTheme.colorScheme.tertiary)
                }
            }

            item { SectionHeader("快捷") }
            item {
                SettingsGroup {
                    SettingRow(
                        title = "悬浮窗",
                        subtitle = if (isFloatServiceRunning) "已开启 · ${mounted.size} 个工作流挂载" else "已关闭",
                        icon = Icons.Outlined.PictureInPictureAlt,
                        onClick = { onFloatServiceToggle(!isFloatServiceRunning) },
                        trailing = { Switch(checked = isFloatServiceRunning, onCheckedChange = onFloatServiceToggle) },
                    )
                    SettingsDivider()
                    SettingRow("AI 工作流生成", subtitle = "用自然语言描述需求，自动生成并保存", icon = Icons.Outlined.AutoAwesome, onClick = onNavigateToWorkflowAI)
                    SettingsDivider()
                    SettingRow("LLM 控制台", subtitle = "导入端侧模型并测试推理", icon = Icons.Outlined.Psychology, onClick = onNavigateToLLM)
                    SettingsDivider()
                    SettingRow("权限管理", subtitle = "无障碍、悬浮窗、通知等系统权限", icon = Icons.Outlined.Security, onClick = onNavigateToPermissions)
                }
            }

            item { SectionHeader("后端服务") }
            item {
                val (tint, status) = when (val c = connection) {
                    ConnectionState.Connected -> HabitatTheme.extendedColors.success to "已连接"
                    is ConnectionState.Error -> MaterialTheme.colorScheme.error to "连接失败：${c.message}"
                    ConnectionState.Testing -> HabitatTheme.extendedColors.warning to "检测中…"
                    ConnectionState.Unknown -> MaterialTheme.colorScheme.onSurfaceVariant to "未检测"
                }
                SettingsGroup {
                    SettingRow(
                        title = "服务器地址",
                        subtitle = serverUrl,
                        icon = Icons.Outlined.Cloud,
                        onClick = { showServerDialog = true },
                    )
                    if (ProfilePrefs.isInsecureRemote(serverUrl)) {
                        InsecureUrlWarning(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs))
                    }
                    SettingsDivider()
                    SettingRow(
                        title = "测试连接",
                        subtitle = status,
                        icon = Icons.Outlined.Cloud,
                        iconTint = tint,
                        subtitleColor = tint,
                        onClick = { testConnection(serverUrl) },
                        trailing = {
                            if (connection is ConnectionState.Testing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        },
                    )
                }
            }

            item { SectionHeader("外观") }
            item {
                SettingsGroup {
                    SettingRow(
                        title = "动态取色",
                        subtitle = if (ThemeSettings.dynamicColorSupported) "跟随系统壁纸配色（Android 12+）" else "需要 Android 12 及以上",
                        icon = Icons.Outlined.Palette,
                        enabled = ThemeSettings.dynamicColorSupported,
                        onClick = {
                            dynamicColor = !dynamicColor
                            ThemeSettings.setDynamicColor(context, dynamicColor)
                        },
                        trailing = {
                            Switch(
                                checked = dynamicColor && ThemeSettings.dynamicColorSupported,
                                enabled = ThemeSettings.dynamicColorSupported,
                                onCheckedChange = { dynamicColor = it; ThemeSettings.setDynamicColor(context, it) },
                            )
                        },
                    )
                }
            }

            item { SectionHeader("关于") }
            item {
                SettingsGroup {
                    SettingRow(
                        title = "关于 Habitat",
                        subtitle = "智能自动化脚本引擎 · v${BuildConfig.VERSION_NAME}",
                        icon = Icons.Outlined.Info,
                        onClick = { showAbout = true },
                    )
                }
            }
            item { Spacer(Modifier.height(Spacing.xl)) }
        }
    }

    if (showServerDialog) {
        ServerDialog(
            initial = serverUrl,
            onDismiss = { showServerDialog = false },
            onSave = { url ->
                serverUrl = url
                ProfilePrefs.setServerUrl(context, url)
                HabitatApiClient.init(url)
                connection = ConnectionState.Unknown
                showServerDialog = false
            },
        )
    }
    if (showLogin) {
        LoginDialog(
            onDismiss = { showLogin = false },
            onSuccess = { showLogin = false },
            onNavigateToRegister = { showRegister = true },
        )
    }
    if (showRegister) {
        RegisterDialog(onDismiss = { showRegister = false }, onSuccess = { showRegister = false })
    }
    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            icon = { Icon(Icons.Outlined.Info, contentDescription = null) },
            title = { Text("Habitat") },
            text = {
                Text(
                    "智能自动化脚本引擎\n版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）\n\n用 JSON 工作流编排手机操作，支持触发器、悬浮窗、AI 生成与 Agent 远程调用。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = { TextButton(onClick = { showAbout = false }) { Text("好") } },
        )
    }
}

@Composable
private fun ProfileHero(
    name: String,
    loggedIn: Boolean,
    onLogin: () -> Unit,
    onRegister: () -> Unit,
    onLogout: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.extraLarge, color = cs.primaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.xl), verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(56.dp).clip(CircleShape).background(cs.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(name.take(1).uppercase(), style = MaterialTheme.typography.headlineSmall, color = cs.onPrimary)
                }
                Spacer(Modifier.width(Spacing.lg))
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleLarge, color = cs.onPrimaryContainer)
                    Text(
                        if (loggedIn) "已登录" else "未登录 · 登录后可同步到 Habitat Server",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onPrimaryContainer.copy(alpha = 0.75f),
                    )
                }
            }
            if (loggedIn) {
                OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(Spacing.sm))
                    Text("退出登录")
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    OutlinedButton(onClick = onLogin, modifier = Modifier.weight(1f)) {
                        Icon(Icons.AutoMirrored.Outlined.Login, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(Spacing.sm))
                        Text("登录")
                    }
                    Button(onClick = onRegister, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(Spacing.sm))
                        Text("注册")
                    }
                }
            }
        }
    }
}

@Composable
private fun StatTile(modifier: Modifier, icon: ImageVector, value: String, label: String, color: Color) {
    Surface(modifier = modifier, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(Spacing.lg)) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(Modifier.height(Spacing.sm))
            Text(value, style = MaterialTheme.typography.headlineSmall.tabular())
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun InsecureUrlWarning(modifier: Modifier = Modifier) {
    val ext = HabitatTheme.extendedColors
    Surface(shape = MaterialTheme.shapes.medium, color = ext.warningContainer, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(Spacing.md), verticalAlignment = Alignment.Top) {
            Icon(Icons.Outlined.WarningAmber, contentDescription = "警告", tint = ext.onWarningContainer, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.sm))
            Text(
                "该地址使用明文 HTTP 且不在本机/局域网，登录凭据与数据可能被窃听。建议改用 https://。",
                style = MaterialTheme.typography.bodySmall,
                color = ext.onWarningContainer,
            )
        }
    }
}

@Composable
private fun ServerDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var input by remember { mutableStateOf(initial) }
    val trimmed = input.trim().trimEnd('/')
    val valid = trimmed.startsWith("http://") || trimmed.startsWith("https://")
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Cloud, contentDescription = null) },
        title = { Text("后端服务地址") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("服务器地址") },
                    placeholder = { Text("https://example.com") },
                    singleLine = true,
                    isError = input.isNotBlank() && !valid,
                    supportingText = { if (input.isNotBlank() && !valid) Text("需以 http:// 或 https:// 开头") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (valid && ProfilePrefs.isInsecureRemote(trimmed)) InsecureUrlWarning()
            }
        },
        confirmButton = { TextButton(onClick = { onSave(trimmed) }, enabled = valid) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
