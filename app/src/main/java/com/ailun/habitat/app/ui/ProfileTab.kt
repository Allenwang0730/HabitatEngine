package com.ailun.habitat.app.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ailun.habitat.HabitatStateStore
import com.ailun.habitat.WorkflowRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

// ── Preferences keys ──
private const val PREFS_PROFILE = "habitat_profile"
private const val KEY_DISPLAY_NAME = "display_name"
private const val KEY_SERVER_URL = "server_url"

object ProfilePrefs {
    fun getDisplayName(ctx: Context): String =
        ctx.getSharedPreferences(PREFS_PROFILE, Context.MODE_PRIVATE)
            .getString(KEY_DISPLAY_NAME, "") ?: ""

    fun setDisplayName(ctx: Context, name: String) {
        ctx.getSharedPreferences(PREFS_PROFILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_DISPLAY_NAME, name).apply()
    }

    fun getServerUrl(ctx: Context): String =
        ctx.getSharedPreferences(PREFS_PROFILE, Context.MODE_PRIVATE)
            .getString(KEY_SERVER_URL, "http://10.0.2.2:8080") ?: "http://10.0.2.2:8080"

    fun setServerUrl(ctx: Context, url: String) {
        ctx.getSharedPreferences(PREFS_PROFILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_SERVER_URL, url).apply()
    }
}

// ── API Key (local storage) ──
data class LocalApiKey(
    val id: String = java.util.UUID.randomUUID().toString(),
    val provider: String,
    val apiKeyPrefix: String,   // first 4 chars + "****" + last 4
    val modelName: String,
    val active: Boolean = true,
)

@Composable
fun ProfileTab(
    isFloatServiceRunning: Boolean,
    onFloatServiceToggle: (Boolean) -> Unit,
    onNavigateToLLM: () -> Unit,
    onNavigateToPermissions: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var displayName by remember {
        mutableStateOf(ProfilePrefs.getDisplayName(context).ifEmpty { "Habitat 用户" })
    }
    var showNameDialog by remember { mutableStateOf(false) }
    var nameInput by remember { mutableStateOf(displayName) }

    var serverUrl by remember { mutableStateOf(ProfilePrefs.getServerUrl(context)) }
    var showServerDialog by remember { mutableStateOf(false) }
    var serverInput by remember { mutableStateOf(serverUrl) }
    var connectionStatus by remember { mutableStateOf<ConnectionState>(ConnectionState.Unknown) }

    var showApiKeyDialog by remember { mutableStateOf(false) }
    var apiKeys: MutableList<LocalApiKey> by remember {
        mutableStateOf(
            context.getSharedPreferences("habitat_api_keys", Context.MODE_PRIVATE)
                .getStringSet("keys", emptySet())?.mapNotNull { raw ->
                    val parts = raw.split("|", limit = 5)
                    if (parts.size >= 5) LocalApiKey(parts[0], parts[1], parts[2], parts[3], parts[4].toBoolean())
                    else null
                }?.toMutableList() ?: mutableListOf()
        )
    }

    fun saveApiKeys() {
        val raw = apiKeys.map {
            "${it.id}|${it.provider}|${it.apiKeyPrefix}|${it.modelName}|${it.active}"
        }.toSet()
        context.getSharedPreferences("habitat_api_keys", Context.MODE_PRIVATE)
            .edit().putStringSet("keys", raw).apply()
    }

    val runningStates by HabitatStateStore.runningStates.collectAsState()
    val activeCount = runningStates.count { it.value.isRunning }
    val workflowCount = remember { WorkflowRepository.getAll(context).size }
    val mountedCount = remember { WorkflowRepository.getFloatMountedWorkflowIds(context).size }

    fun testConnection(url: String) {
        scope.launch {
            connectionStatus = ConnectionState.Testing
            try {
                val result = withContext(Dispatchers.IO) {
                    val conn = URL("${url.trimEnd('/')}/actuator/health").openConnection() as HttpURLConnection
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    conn.responseCode
                }
                connectionStatus = if (result == 200) ConnectionState.Connected
                else ConnectionState.Error("HTTP $result")
            } catch (e: Exception) {
                connectionStatus = ConnectionState.Error(e.message ?: "连接失败")
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // ── Header ──
        item {
            Spacer(Modifier.height(8.dp))
            Text("个人主页", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }

        // ── Profile Card ──
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)),
            ) {
                Row(
                    modifier = Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Avatar
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = displayName.take(1).uppercase(),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(2.dp))
                        Text("工作流自动化用户", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { nameInput = displayName; showNameDialog = true }) {
                        Icon(Icons.Outlined.Edit, "编辑名称", modifier = Modifier.size(20.dp))
                    }
                }
            }
        }

        // ── Stats Row ──
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.AccountTree,
                    value = "$workflowCount",
                    label = "工作流",
                    color = MaterialTheme.colorScheme.primary,
                )
                StatCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.PlayCircle,
                    value = "$activeCount",
                    label = "运行中",
                    color = Color(0xFF22C55E),
                )
                StatCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.PushPin,
                    value = "$mountedCount",
                    label = "已挂载",
                    color = Color(0xFFF59E0B),
                )
            }
        }

        // ── Backend Server Config ──
        item {
            SectionHeader("后端服务")
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Cloud,
                        null,
                        tint = when (connectionStatus) {
                            is ConnectionState.Connected -> Color(0xFF22C55E)
                            is ConnectionState.Error -> MaterialTheme.colorScheme.error
                            is ConnectionState.Testing -> Color(0xFFF59E0B)
                            is ConnectionState.Unknown -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        },
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Habitat Server", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            serverUrl,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                        Text(
                            when (val s = connectionStatus) {
                                is ConnectionState.Connected -> "已连接"
                                is ConnectionState.Error -> "连接失败: ${s.message}"
                                is ConnectionState.Testing -> "检测中..."
                                is ConnectionState.Unknown -> "未检测"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = when (connectionStatus) {
                                is ConnectionState.Connected -> Color(0xFF16A34A)
                                is ConnectionState.Error -> MaterialTheme.colorScheme.error
                                is ConnectionState.Testing -> Color(0xFFF59E0B)
                                is ConnectionState.Unknown -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            },
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        IconButton(
                            onClick = { serverInput = serverUrl; showServerDialog = true },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(Icons.Outlined.Settings, "配置", modifier = Modifier.size(20.dp))
                        }
                        TextButton(
                            onClick = { testConnection(serverUrl) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        ) {
                            Text("测试连接", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // ── API Key Management ──
        item {
            SectionHeader("API 密钥管理")
        }

        if (apiKeys.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Outlined.VpnKey,
                            null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "尚未配置 API 密钥",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "添加第三方大模型 API 密钥以启用云端 AI 工作流生成",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        } else {
            items(apiKeys, key = { it.id }) { key ->
                ApiKeyCard(
                    apiKey = key,
                    onToggleActive = {
                        val idx = apiKeys.indexOfFirst { it.id == key.id }
                        if (idx >= 0) {
                            val copy = mutableListOf<LocalApiKey>()
                            copy.addAll(apiKeys)
                            copy[idx] = key.copy(active = !key.active)
                            apiKeys = copy
                            saveApiKeys()
                        }
                    },
                    onDelete = {
                        val filtered = mutableListOf<LocalApiKey>()
                        apiKeys.filterTo(filtered) { it.id != key.id }
                        apiKeys = filtered
                        saveApiKeys()
                    },
                )
            }
        }

        item {
            OutlinedButton(
                onClick = { showApiKeyDialog = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("添加 API 密钥")
            }
        }

        // ── Quick Actions ──
        item {
            SectionHeader("快捷操作")
        }

        // Float window toggle
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.PushPin,
                        null,
                        tint = if (isFloatServiceRunning) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("悬浮窗", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (isFloatServiceRunning) "已开启 · $mountedCount 个工作流挂载" else "已关闭",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = isFloatServiceRunning, onCheckedChange = onFloatServiceToggle)
                }
            }
        }

        // ── Navigation Menu ──
        item {
            SectionHeader("更多")
        }

        item {
            MenuCard(
                icon = Icons.Filled.Psychology,
                title = "LLM 控制台",
                subtitle = "加载本地模型并进行推理测试",
                onClick = onNavigateToLLM,
            )
        }

        item {
            MenuCard(
                icon = Icons.Filled.Security,
                title = "权限管理",
                subtitle = "管理无障碍、悬浮窗等系统权限",
                onClick = onNavigateToPermissions,
            )
        }

        item {
            MenuCard(
                icon = Icons.Filled.Info,
                title = "关于 Habitat",
                subtitle = "智能自动化脚本引擎 v1.0",
                onClick = {
                    Toast.makeText(context, "Habitat - 智能自动化脚本引擎", Toast.LENGTH_SHORT).show()
                },
            )
        }

        item { Spacer(Modifier.height(24.dp)) }
    }

    // ── Edit Name Dialog ──
    if (showNameDialog) {
        AlertDialog(
            onDismissRequest = { showNameDialog = false },
            title = { Text("修改显示名称") },
            text = {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val trimmed = nameInput.trim()
                    if (trimmed.isNotEmpty()) {
                        displayName = trimmed
                        ProfilePrefs.setDisplayName(context, trimmed)
                    }
                    showNameDialog = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showNameDialog = false }) { Text("取消") }
            },
        )
    }

    // ── Server Config Dialog ──
    if (showServerDialog) {
        AlertDialog(
            onDismissRequest = { showServerDialog = false },
            title = { Text("后端服务配置") },
            text = {
                Column {
                    OutlinedTextField(
                        value = serverInput,
                        onValueChange = { serverInput = it },
                        label = { Text("服务器地址") },
                        placeholder = { Text("http://10.0.2.2:8080") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (connectionStatus is ConnectionState.Connected) {
                        Text(
                            "连接成功",
                            color = Color(0xFF16A34A),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val trimmed = serverInput.trim().trimEnd('/')
                    if (trimmed.isNotEmpty()) {
                        serverUrl = trimmed
                        ProfilePrefs.setServerUrl(context, trimmed)
                        connectionStatus = ConnectionState.Unknown
                    }
                    showServerDialog = false
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showServerDialog = false }) { Text("取消") }
            },
        )
    }

    // ── Add API Key Dialog ──
    if (showApiKeyDialog) {
        var providerInput by remember { mutableStateOf("openai") }
        var keyInput by remember { mutableStateOf("") }
        var modelInput by remember { mutableStateOf("gpt-4o") }
        AlertDialog(
            onDismissRequest = { showApiKeyDialog = false },
            title = { Text("添加 API 密钥") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = providerInput,
                        onValueChange = { providerInput = it },
                        label = { Text("提供商") },
                        placeholder = { Text("openai / deepseek / azure") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = keyInput,
                        onValueChange = { keyInput = it },
                        label = { Text("API Key") },
                        placeholder = { Text("sk-...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = modelInput,
                        onValueChange = { modelInput = it },
                        label = { Text("模型名称") },
                        placeholder = { Text("gpt-4o") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val provider = providerInput.trim()
                    val rawKey = keyInput.trim()
                    val model = modelInput.trim()
                    if (provider.isNotEmpty() && rawKey.isNotEmpty() && model.isNotEmpty()) {
                        val masked = if (rawKey.length > 8) {
                            rawKey.take(4) + "****" + rawKey.takeLast(4)
                        } else {
                            "****"
                        }
                        val newKey = LocalApiKey(
                            provider = provider,
                            apiKeyPrefix = masked,
                            modelName = model,
                        )
                        val updated = mutableListOf<LocalApiKey>()
                        updated.addAll(apiKeys)
                        updated.add(newKey)
                        apiKeys = updated
                        saveApiKeys()
                        showApiKeyDialog = false
                        Toast.makeText(context, "密钥已添加 (存储于本地)", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("添加") }
            },
            dismissButton = {
                TextButton(onClick = { showApiKeyDialog = false }) { Text("取消") }
            },
        )
    }
}

// ── Sub-components ──

private sealed class ConnectionState {
    data object Unknown : ConnectionState()
    data object Testing : ConnectionState()
    data object Connected : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun StatCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    value: String,
    label: String,
    color: Color,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.08f)),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ApiKeyCard(
    apiKey: LocalApiKey,
    onToggleActive: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (apiKey.active) MaterialTheme.colorScheme.surface
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (apiKey.active) 1.dp else 0.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.VpnKey,
                null,
                tint = if (apiKey.active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    apiKey.provider.uppercase().replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        apiKey.apiKeyPrefix,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                    Text(
                        apiKey.modelName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 11.sp,
                    )
                }
            }
            Switch(
                checked = apiKey.active,
                onCheckedChange = { onToggleActive() },
                modifier = Modifier.padding(end = 4.dp),
            )
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Outlined.Delete,
                    "删除",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Composable
private fun MenuCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
