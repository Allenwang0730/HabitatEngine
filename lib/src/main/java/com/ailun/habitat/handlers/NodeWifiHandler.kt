package com.ailun.habitat.handlers

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode
import com.ailun.habitat.api.IShellExecutor
import kotlinx.coroutines.CancellationException

/**
 * [ACTION_WIFI]：WiFi 开关/状态。params：`action`（"on"/"off"/"toggle"/"status"）。
 * 开关优先 `svc wifi enable|disable`（Shizuku）；无 shell 时回退 WifiManager.setWifiEnabled（Android 10+ 对普通应用无效）。
 */
class NodeWifiHandler(
    private val shellExecutor: IShellExecutor? = null,
) : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        context.putVariable("wifi_success", false)
        val action = node.params?.get("action")?.toString()?.let { context.interpolate(it) }?.trim()?.lowercase().orEmpty()
        if (action.isEmpty()) {
            Log.e(TAG, "WiFi failed: 'action' parameter is empty")
            context.putVariable("wifi_success", false)
            return node.next
        }

        @Suppress("DEPRECATION")
        val wifiManager = context.appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifiManager == null) {
            Log.e(TAG, "WiFi failed: unable to get WifiManager service")
            context.putVariable("wifi_success", false)
            return node.next
        }

        var success = false

        try {
            when (action) {
                "on" -> {
                    success = setWifiEnabled(context, wifiManager, true)
                    Log.i(TAG, "WiFi turn on: success=$success")
                }
                "off" -> {
                    success = setWifiEnabled(context, wifiManager, false)
                    Log.i(TAG, "WiFi turn off: success=$success")
                }
                "toggle" -> {
                    @Suppress("DEPRECATION")
                    val currentlyEnabled = wifiManager.isWifiEnabled
                    success = setWifiEnabled(context, wifiManager, !currentlyEnabled)
                    Log.i(TAG, "WiFi toggle to ${!currentlyEnabled}: success=$success")
                }
                "status" -> {
                    // Give async WiFi state time to settle before reading
                    kotlinx.coroutines.delay(500)
                    @Suppress("DEPRECATION")
                    val state = wifiManager.wifiState
                    val enabled = state == WifiManager.WIFI_STATE_ENABLED ||
                            state == WifiManager.WIFI_STATE_ENABLING
                    context.putVariable("wifi_enabled", enabled)
                    context.putVariable("wifi_state", state)
                    success = true
                    Log.i(TAG, "WiFi status: enabled=$enabled, state=$state")
                }
                else -> {
                    Log.e(TAG, "WiFi failed: unknown action '$action'")
                    context.putVariable("wifi_success", false)
                    return node.next
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "WiFi error for action '$action': ${e.message}", e)
            success = false
        }

        context.putVariable("wifi_success", success)
        return node.next
    }

    private suspend fun setWifiEnabled(context: WorkflowContext, wifiManager: WifiManager, enable: Boolean): Boolean {
        // Strategy 1: shell command (svc wifi) — works on most devices without special permission
        val shell = shellExecutor
        if (shell != null) {
            try {
                val cmd = if (enable) "svc wifi enable" else "svc wifi disable"
                val result = shell.exec(cmd, asRoot = false)
                Log.i(TAG, "Shell $cmd → ${result.take(200)}")
                // svc 成功时通常无输出
                if (!result.startsWith("Error") && !result.contains("Exception", ignoreCase = true)) {
                    return true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Shell WiFi command failed, falling back to WifiManager: ${e.message}")
            }
        }

        // Strategy 2: deprecated WifiManager API (may silently fail on Android 10+)
        @Suppress("DEPRECATION")
        val apiResult = wifiManager.setWifiEnabled(enable)
        if (apiResult) return true

        // Strategy 3: on Android 10+, the API is restricted — log the likely cause
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Log.w(
                TAG,
                "WiFi setWifiEnabled returned false. This API is restricted on Android 10+." +
                        " Ensure CHANGE_WIFI_STATE permission is granted or use Shell/Shizuku."
            )
        }
        return false
    }

    companion object {
        private const val TAG = "HabitatWifi"
    }
}
