package com.ailun.habitat.app.ui

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.GppMaybe
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ailun.habitat.app.AgentService
import com.ailun.habitat.app.HabitatTextStyles
import com.ailun.habitat.app.HabitatTheme
import com.ailun.habitat.app.Spacing
import com.ailun.habitat.app.agent.AgentLoopController
import com.ailun.habitat.app.ai.WorkflowAIClient
import com.ailun.habitat.app.tabular
import com.ailun.habitat.app.ui.components.ConfirmDialog
import com.ailun.habitat.app.ui.components.IconTile
import com.ailun.habitat.app.ui.components.PillStatus
import com.ailun.habitat.app.ui.components.SecretField
import com.ailun.habitat.app.ui.components.SectionHeader
import com.ailun.habitat.app.ui.components.SettingRow
import com.ailun.habitat.app.ui.components.SettingsDivider
import com.ailun.habitat.app.ui.components.SettingsGroup
import com.ailun.habitat.app.ui.components.StatusDot
import com.ailun.habitat.app.ui.components.StatusPill
import com.ailun.habitat.app.ui.components.copyToClipboard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.Inet4Address

private const val PRIVACY_PREFS = "agent_privacy_prefs"

/**
 * Agent 页：顶部服务状态卡（端口/访问范围/令牌）、访问控制开关、自主模式（决策器/目标/步数/时间线日志）。
 * 服务状态直接读 AgentService.serverRunning（同进程标志），不再在主线程做 HTTP 健康检查。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loop by AgentLoopController.state.collectAsStateWithLifecycle()

    var running by remember { mutableStateOf(AgentService.serverRunning) }
    var restarting by remember { mutableStateOf(false) }
    var port by remember { mutableIntStateOf(AgentService.getPort(context)) }
    var portInput by rememberSaveable { mutableStateOf(port.toString()) }
    var lanEnabled by remember { mutableStateOf(AgentService.isLanEnabled(context)) }
    var lanChangedWhileRunning by remember { mutableStateOf(false) }
    var dangerousAllowed by remember { mutableStateOf(AgentService.isRemoteDangerousAllowed(context)) }
    var confirmDangerous by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf(AgentService.getToken(context)) }
    var tokenVisible by rememberSaveable { mutableStateOf(false) }
    var confirmRegenerate by remember { mutableStateOf(false) }
    var ip by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    var provider by rememberSaveable { mutableStateOf(AgentLoopController.getProvider(context)) }
    var goal by rememberSaveable { mutableStateOf("") }
    var jevKey by remember { mutableStateOf(AgentLoopController.getJevKey(context)) }
    var jevModel by remember { mutableStateOf(AgentLoopController.getJevModel(context)) }
    var deepseekKey by remember { mutableStateOf(WorkflowAIClient.getApiKey(context).orEmpty()) }
    var steps by rememberSaveable { mutableFloatStateOf(12f) }
    var privacyFor by remember { mutableStateOf<String?>(null) }

    // serverRunning 是同进程的内存标志，轮询代价极低
    LaunchedEffect(Unit) {
        while (true) {
            running = AgentService.serverRunning
            delay(1000)
        }
    }
    LifecycleResumeEffect(Unit) {
        ip = lanIpv4(context)
        onPauseOrDispose { }
    }

    fun restartService() {
        restarting = true
        scope.launch {
            context.stopService(Intent(context, AgentService::class.java))
            AgentService.serverRunning = false
            delay(800) // 等待旧 Netty 释放端口
            try {
                AgentService.start(context)
                message = null
            } catch (e: Exception) {
                message = "重启失败：${e.message}"
            }
            lanChangedWhileRunning = false
            delay(600)
            restarting = false
        }
    }

    fun persistKeys() {
        when (provider) {
            AgentLoopController.PROVIDER_JEV -> {
                AgentLoopController.setJevKey(context, jevKey)
                AgentLoopController.setJevModel(context, jevModel)
            }
            AgentLoopController.PROVIDER_DEEPSEEK -> WorkflowAIClient.setApiKey(context, deepseekKey)
        }
    }

    fun startLoop() {
        persistKeys()
        message = AgentLoopController.start(context, goal, provider, steps.toInt())
    }

    fun privacyAcknowledged(p: String): Boolean =
        p == AgentLoopController.PROVIDER_LOCAL ||
            context.getSharedPreferences(PRIVACY_PREFS, Context.MODE_PRIVATE).getBoolean("ack_$p", false)

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { LargeTopAppBar(title = { Text("Agent") }, scrollBehavior = scrollBehavior) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.page),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            // ── Hero：服务状态 ──
            ServiceHeroCard(
                running = running,
                restarting = restarting,
                port = port,
                lanEnabled = lanEnabled,
                ip = ip,
                onToggle = { enable ->
                    message = null
                    if (enable) {
                        running = true
                        try {
                            AgentService.start(context)
                        } catch (e: Exception) {
                            running = AgentService.serverRunning
                            message = "启动失败：${e.message}"
                        }
                    } else {
                        running = false
                        context.stopService(Intent(context, AgentService::class.java))
                        AgentService.serverRunning = false
                    }
                },
            )

            message?.let {
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                    Text(it, Modifier.padding(Spacing.md), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }

            // ── 访问令牌 ──
            SectionHeader("访问令牌")
            SettingsGroup {
                Row(
                    Modifier.fillMaxWidth().padding(start = Spacing.lg, end = Spacing.xs, top = Spacing.sm, bottom = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconTile(Icons.Outlined.Lock)
                    Spacer(Modifier.width(Spacing.md))
                    Column(Modifier.weight(1f)) {
                        Text("连接需携带 ?token=", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            if (tokenVisible) token else maskToken(token),
                            style = HabitatTextStyles.codeSmall,
                            maxLines = if (tokenVisible) 3 else 1,
                        )
                    }
                    IconButton(onClick = { tokenVisible = !tokenVisible }) {
                        Icon(
                            if (tokenVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = if (tokenVisible) "隐藏令牌" else "显示令牌",
                        )
                    }
                    IconButton(onClick = { copyToClipboard(context, token, "Agent 令牌", sensitive = true) }) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = "复制令牌")
                    }
                }
                SettingsDivider()
                SettingRow(
                    title = "重新生成令牌",
                    subtitle = "旧令牌立即失效，已连接的客户端需要更新",
                    icon = Icons.Outlined.Refresh,
                    onClick = { confirmRegenerate = true },
                )
            }

            // ── 访问控制 ──
            SectionHeader("访问控制")
            SettingsGroup {
                SettingRow(
                    title = "允许局域网访问",
                    subtitle = if (lanEnabled) "监听 0.0.0.0，同一网络内的设备可连接" else "仅本机（127.0.0.1）可连接",
                    icon = Icons.Outlined.Lan,
                    onClick = {
                        val v = !lanEnabled
                        lanEnabled = v; AgentService.setLanEnabled(context, v)
                        if (running) lanChangedWhileRunning = true
                    },
                    trailing = {
                        Switch(checked = lanEnabled, onCheckedChange = {
                            lanEnabled = it; AgentService.setLanEnabled(context, it)
                            if (running) lanChangedWhileRunning = true
                        })
                    },
                )
                AnimatedVisibility(lanChangedWhileRunning) {
                    Surface(color = HabitatTheme.extendedColors.warningContainer, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "需重启服务后生效",
                                style = MaterialTheme.typography.bodySmall,
                                color = HabitatTheme.extendedColors.onWarningContainer,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = ::restartService, enabled = !restarting) {
                                Icon(Icons.Outlined.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(Spacing.xs))
                                Text("立即重启")
                            }
                        }
                    }
                }
                SettingsDivider()
                SettingRow(
                    title = "允许远程调用高危节点",
                    subtitle = if (dangerousAllowed) "已允许 Shell、文件、通话、短信等 ${AgentService.REMOTE_DANGEROUS_TOOLS.size} 类节点被远程调用"
                    else "远程调用 Shell、文件、通话、短信等节点会被拒绝",
                    icon = Icons.Outlined.GppMaybe,
                    iconTint = if (dangerousAllowed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    subtitleColor = if (dangerousAllowed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = {
                        if (dangerousAllowed) { dangerousAllowed = false; AgentService.setRemoteDangerousAllowed(context, false) }
                        else confirmDangerous = true
                    },
                    trailing = {
                        Switch(checked = dangerousAllowed, onCheckedChange = { on ->
                            if (on) confirmDangerous = true
                            else { dangerousAllowed = false; AgentService.setRemoteDangerousAllowed(context, false) }
                        })
                    },
                )
                SettingsDivider()
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconTile(Icons.Outlined.Dns)
                    Spacer(Modifier.width(Spacing.md))
                    OutlinedTextField(
                        value = portInput,
                        onValueChange = { portInput = it.filter(Char::isDigit).take(5) },
                        label = { Text("端口") },
                        singleLine = true,
                        textStyle = HabitatTextStyles.code,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        supportingText = { Text("1024–65535，保存后自动重启") },
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    FilledTonalButton(
                        onClick = {
                            val newPort = portInput.toIntOrNull()?.coerceIn(1024, 65535) ?: return@FilledTonalButton
                            portInput = newPort.toString()
                            if (newPort != port) {
                                AgentService.setPort(context, newPort)
                                port = newPort
                                if (running) restartService()
                            }
                        },
                        enabled = portInput.toIntOrNull() != port && !restarting,
                    ) { Text("保存") }
                }
            }

            // ── 自主模式 ──
            SectionHeader("自主模式")
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    Text(
                        "读屏 → 决策 → 执行节点 → 再读屏。决策器只在当前屏幕的候选里选择，执行仍走 Habitat 节点。微信需另开「Habitat 微信读屏」。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val providers = listOf(
                        AgentLoopController.PROVIDER_JEV to "Jev",
                        AgentLoopController.PROVIDER_DEEPSEEK to "DeepSeek",
                        AgentLoopController.PROVIDER_LOCAL to "本地",
                    )
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        providers.forEachIndexed { i, (id, label) ->
                            SegmentedButton(
                                selected = provider == id,
                                onClick = { provider = id; AgentLoopController.setProvider(context, id) },
                                shape = SegmentedButtonDefaults.itemShape(i, providers.size),
                                enabled = !loop.running,
                            ) { Text(label) }
                        }
                    }
                    when (provider) {
                        AgentLoopController.PROVIDER_JEV -> {
                            SecretField(value = jevKey, onValueChange = { jevKey = it }, label = "Jev API Key", modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(
                                value = jevModel, onValueChange = { jevModel = it },
                                label = { Text("Jev 模型") }, singleLine = true,
                                textStyle = HabitatTextStyles.code, modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        AgentLoopController.PROVIDER_DEEPSEEK ->
                            SecretField(value = deepseekKey, onValueChange = { deepseekKey = it }, label = "DeepSeek API Key", modifier = Modifier.fillMaxWidth())
                        else -> Text(
                            "本地策略不联网：点目标里出现的文字，否则打开点名的应用，再否则向下滚两次。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedTextField(
                        value = goal,
                        onValueChange = { goal = it },
                        label = { Text("目标") },
                        placeholder = { Text("例如：打开设置，进入 WLAN 页面") },
                        minLines = 2,
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !loop.running,
                    )
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("最多步数", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Text("${steps.toInt()}", style = MaterialTheme.typography.titleMedium.tabular())
                        }
                        Slider(
                            value = steps,
                            onValueChange = { steps = it },
                            valueRange = 1f..30f,
                            steps = 28,
                            enabled = !loop.running,
                            modifier = Modifier.semantics { contentDescription = "最多步数" },
                        )
                    }
                    if (loop.running) {
                        Button(
                            onClick = { AgentLoopController.stop() },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        ) {
                            Icon(Icons.Outlined.Stop, contentDescription = null)
                            Spacer(Modifier.width(Spacing.sm))
                            Text("停止")
                        }
                    } else {
                        Button(
                            onClick = { if (privacyAcknowledged(provider)) startLoop() else privacyFor = provider },
                            enabled = goal.isNotBlank(),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        ) {
                            Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(Spacing.sm))
                            Text("开始")
                        }
                    }
                }
            }

            LoopTimeline(loop.phase, loop.running, loop.detail, loop.lines)
            Spacer(Modifier.height(Spacing.xl))
        }
    }

    if (confirmDangerous) {
        ConfirmDialog(
            title = "允许远程调用高危节点？",
            message = "开启后，持有令牌的远程客户端（MCP / WebSocket）可以执行 Shell 命令、读写文件与数据库、拨打电话、读取短信和通话记录、录音拍照等。" +
                (if (lanEnabled) "当前已允许局域网访问，同一网络中的其他设备也能调用。" else "") +
                "请只在完全信任连接方时开启。",
            confirmLabel = "我了解风险，开启",
            icon = Icons.Outlined.GppMaybe,
            destructive = true,
            onConfirm = {
                confirmDangerous = false
                dangerousAllowed = true
                AgentService.setRemoteDangerousAllowed(context, true)
            },
            onDismiss = { confirmDangerous = false },
        )
    }

    if (confirmRegenerate) {
        ConfirmDialog(
            title = "重新生成令牌？",
            message = "旧令牌会立即失效，所有使用它的客户端都需要更新配置。",
            confirmLabel = "重新生成",
            icon = Icons.Outlined.Refresh,
            onConfirm = {
                confirmRegenerate = false
                token = AgentService.regenerateToken(context)
                tokenVisible = true
            },
            onDismiss = { confirmRegenerate = false },
        )
    }

    privacyFor?.let { p ->
        val name = if (p == AgentLoopController.PROVIDER_JEV) "Jev" else "DeepSeek"
        ConfirmDialog(
            title = "屏幕内容将发送给 $name",
            message = "自主模式每一步都会把当前屏幕上的文字（可能包含聊天记录、通知、账号等个人信息）发送给 $name 用于决策。" +
                "如不希望上传，请改用「本地」决策器。此提示只显示一次。",
            confirmLabel = "同意并开始",
            icon = Icons.Outlined.PrivacyTip,
            onConfirm = {
                context.getSharedPreferences(PRIVACY_PREFS, Context.MODE_PRIVATE).edit().putBoolean("ack_$p", true).apply()
                privacyFor = null
                startLoop()
            },
            onDismiss = { privacyFor = null },
        )
    }
}

@Composable
private fun ServiceHeroCard(
    running: Boolean,
    restarting: Boolean,
    port: Int,
    lanEnabled: Boolean,
    ip: String?,
    onToggle: (Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val host = if (lanEnabled) (ip ?: "未连接网络") else "127.0.0.1"
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = if (running) cs.primaryContainer else cs.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Spacing.xl), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(
                    Icons.Outlined.SmartToy,
                    tint = if (running) cs.onPrimaryContainer else cs.onSurfaceVariant,
                    container = if (running) cs.primary.copy(alpha = 0.16f) else cs.surfaceContainerHighest,
                    size = 48,
                )
                Spacer(Modifier.width(Spacing.md))
                Column(Modifier.weight(1f)) {
                    Text("手机工具服务", style = MaterialTheme.typography.titleMedium, color = if (running) cs.onPrimaryContainer else cs.onSurface)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(if (restarting) PillStatus.Waiting else if (running) PillStatus.Running else PillStatus.Idle)
                        Spacer(Modifier.width(Spacing.sm))
                        Text(
                            when { restarting -> "重启中…"; running -> "运行中"; else -> "已停止" },
                            style = MaterialTheme.typography.labelLarge,
                            color = if (running) cs.onPrimaryContainer else cs.onSurfaceVariant,
                        )
                    }
                }
                Switch(
                    checked = running,
                    onCheckedChange = onToggle,
                    enabled = !restarting,
                    modifier = Modifier.semantics { contentDescription = "Agent 服务开关" },
                )
            }
            HorizontalDivider(color = (if (running) cs.onPrimaryContainer else cs.outlineVariant).copy(alpha = 0.15f))
            HeroRow("访问范围", if (lanEnabled) "局域网" else "仅本机", running)
            HeroRow("端口", "$port", running, mono = true)
            HeroRow("MCP", "ws://$host:$port/mcp", running, mono = true)
            HeroRow("WebSocket", "ws://$host:$port/ws", running, mono = true)
        }
    }
}

@Composable
private fun HeroRow(label: String, value: String, active: Boolean, mono: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    val fg = if (active) cs.onPrimaryContainer else cs.onSurface
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = fg.copy(alpha = 0.72f), modifier = Modifier.width(88.dp))
        Text(value, style = if (mono) HabitatTextStyles.codeSmall.copy(color = fg) else MaterialTheme.typography.bodyMedium.copy(color = fg))
    }
}

/** 自主模式步骤时间线：每行一个节点，结束状态用语义色。 */
@Composable
private fun LoopTimeline(phase: String, running: Boolean, detail: String, lines: List<String>) {
    if (lines.isEmpty() && phase == "idle") return
    val cs = MaterialTheme.colorScheme
    val ext = HabitatTheme.extendedColors
    val (status, label) = when {
        running -> PillStatus.Running to "运行中"
        phase == "done" -> PillStatus.Success to "已完成"
        phase == "blocked" -> PillStatus.Warning to "受阻"
        phase == "error" -> PillStatus.Failed to "出错"
        phase == "stopped" -> PillStatus.Idle to "已停止"
        else -> PillStatus.Idle to phase
    }
    Surface(shape = MaterialTheme.shapes.large, color = cs.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("执行过程", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                StatusPill(status, label)
            }
            if (!running && detail.isNotBlank()) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.xs))
            }
            Spacer(Modifier.height(Spacing.md))
            lines.forEachIndexed { i, line ->
                val last = i == lines.lastIndex
                val color: Color = when {
                    line.startsWith("结束 done") -> ext.success
                    line.startsWith("结束 blocked") -> ext.warning
                    line.startsWith("结束 error") -> cs.error
                    line.startsWith("已停止") -> cs.outline
                    line.startsWith("开始") -> cs.primary
                    last && running -> cs.primary
                    else -> cs.outlineVariant
                }
                TimelineRow(line, color, isLast = last, pulsing = last && running)
            }
        }
    }
}

@Composable
private fun TimelineRow(text: String, color: Color, isLast: Boolean, pulsing: Boolean) {
    Row(Modifier.fillMaxWidth()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(20.dp)) {
            Spacer(Modifier.height(5.dp))
            if (pulsing) StatusDot(PillStatus.Running, size = 10)
            else androidx.compose.foundation.Canvas(Modifier.size(10.dp)) { drawCircle(color) }
            if (!isLast) {
                androidx.compose.foundation.Canvas(Modifier.width(2.dp).height(22.dp)) {
                    drawRect(color.copy(alpha = 0.35f))
                }
            }
        }
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = if (color == MaterialTheme.colorScheme.outlineVariant) MaterialTheme.colorScheme.onSurface else color,
            modifier = Modifier.padding(bottom = Spacing.sm),
        )
    }
}

private fun maskToken(t: String): String =
    if (t.length <= 8) "••••••••" else t.take(4) + "••••••••••••" + t.takeLast(4)

/** 当前活动网络的 IPv4 地址（替代已废弃的 WifiManager.connectionInfo）。 */
private fun lanIpv4(context: Context): String? = try {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    cm?.getLinkProperties(cm.activeNetwork)?.linkAddresses
        ?.map { it.address }
        ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
        ?.hostAddress
} catch (_: Exception) {
    null
}
