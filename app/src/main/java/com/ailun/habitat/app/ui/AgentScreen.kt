package com.ailun.habitat.app.ui

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.ailun.habitat.app.AgentService
import kotlinx.coroutines.delay

/**
 * Agent 控制页：开关服务 / 连接状态 / 端口设置 / 局域网 IP。
 */
@Composable
fun AgentScreen() {
    val context = LocalContext.current
    var port by remember { mutableIntStateOf(AgentService.getPort(context)) }
    var portInput by remember { mutableStateOf(port.toString()) }
    var running by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(true) }
    var lastError by remember { mutableStateOf<String?>(null) }

    val checkHealth: suspend (Int) -> Boolean = { p ->
        try {
            val resp = okhttp3.OkHttpClient.Builder()
                .callTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                .build()
                .newCall(okhttp3.Request.Builder().url("http://127.0.0.1:$p/health").build())
                .execute()
            resp.use { it.body?.string() == "habitat-agent-ok" }
        } catch (e: Exception) {
            false
        }
    }

    // 定期健康检查
    LaunchedEffect(port, running) {
        while (true) {
            running = checkHealth(port)
            checking = false
            delay(3000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("AI Agent（手机工具服务）", style = MaterialTheme.typography.headlineSmall)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("服务开关", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Switch(
                        checked = running,
                        onCheckedChange = { enable ->
                            if (enable) {
                                context.startService(Intent(context, AgentService::class.java))
                            } else {
                                context.stopService(Intent(context, AgentService::class.java))
                            }
                        },
                    )
                }
                StatusRow("服务状态", if (running) "运行中" else if (checking) "检测中…" else "已停止")
                StatusRow("局域网 IP", wifiIp(context))
                StatusRow("MCP 地址", "ws://${wifiIp(context)}:$port/mcp")
                StatusRow("WebSocket 地址", "ws://${wifiIp(context)}:$port/ws")
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("端口设置（改动需重启服务）", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = portInput,
                        onValueChange = { portInput = it.filter { c -> c.isDigit() }.take(5) },
                        label = { Text("端口") },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        val newPort = portInput.toIntOrNull()?.coerceIn(1024, 65535) ?: return@Button
                        if (newPort != port) {
                            context.stopService(Intent(context, AgentService::class.java))
                            AgentService.setPort(context, newPort)
                            port = newPort
                            context.startService(Intent(context, AgentService::class.java))
                        }
                    }) { Text("保存并重启") }
                }
                Text(
                    "说明：启动服务后，局域网内任意 AI 客户端（MCP 或自定义 WS 协议）可调用 80 个节点操作手机。" +
                        "当前无鉴权，请仅在可信网络使用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        lastError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** 获取 WLAN IPv4 地址。 */
private fun wifiIp(context: Context): String {
    return try {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ip = wm.connectionInfo?.ipAddress ?: 0
        if (ip != 0) {
            String.format("%d.%d.%d.%d", ip and 0xFF, (ip shr 8) and 0xFF, (ip shr 16) and 0xFF, (ip shr 24) and 0xFF)
        } else "未知（未连 WiFi）"
    } catch (e: Exception) {
        "未知"
    }
}
