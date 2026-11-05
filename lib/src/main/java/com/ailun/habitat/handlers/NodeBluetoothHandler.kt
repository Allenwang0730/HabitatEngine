package com.ailun.habitat.handlers

import android.bluetooth.BluetoothAdapter
import android.os.Build
import android.util.Log
import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode
import com.ailun.habitat.api.IShellExecutor
import kotlinx.coroutines.CancellationException

/**
 * [ACTION_BLUETOOTH]：控制蓝牙开关及获取状态。
 *
 * params：`action`（必填："on"/"off"/"toggle"/"status"）
 *
 * 开关优先 `svc bluetooth enable|disable`（Shizuku）；无 shell 时回退 BluetoothAdapter.enable/disable
 * （Android 13+ 对普通应用恒返回 false；Android 12+ 另需 BLUETOOTH_CONNECT 运行时权限）。
 */
class NodeBluetoothHandler(
    private val shellExecutor: IShellExecutor? = null,
) : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        context.putVariable("bluetooth_success", false)
        val action = node.params?.get("action")?.toString()?.let { context.interpolate(it) }?.trim()?.lowercase().orEmpty()
        if (action.isEmpty()) {
            Log.e(TAG, "Bluetooth failed: 'action' parameter is empty")
            return node.next
        }

        @Suppress("DEPRECATION")
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null) {
            Log.e(TAG, "Bluetooth failed: device does not support Bluetooth")
            return node.next
        }

        var success = false

        try {
            when (action) {
                // 以最终状态为准：调用返回成功、状态没变（模拟器上 disable() 返回 true，bluetooth_on 还是 1）也算没做到
                "on" -> success = setEnabled(bluetoothAdapter, true).let { settlesTo(bluetoothAdapter, true, context) }
                "off" -> success = setEnabled(bluetoothAdapter, false).let { settlesTo(bluetoothAdapter, false, context) }
                "toggle" -> {
                    val want = !isEnabled(bluetoothAdapter)
                    success = setEnabled(bluetoothAdapter, want).let { settlesTo(bluetoothAdapter, want, context) }
                }
                "status" -> {
                    val enabled = isEnabled(bluetoothAdapter)
                    context.putVariable("bluetooth_enabled", enabled)
                    success = true
                    Log.i(TAG, "Bluetooth status: enabled=$enabled")
                }
                else -> {
                    Log.e(TAG, "Bluetooth failed: unknown action '$action'")
                    return node.next
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Bluetooth error for action '$action': ${e.message}", e)
            success = false
        }

        context.putVariable("bluetooth_success", success)
        return node.next
    }

    @Suppress("DEPRECATION", "MissingPermission")
    private fun isEnabled(adapter: BluetoothAdapter): Boolean = try {
        adapter.isEnabled
    } catch (_: SecurityException) {
        false
    }

    /**
     * 调用报失败、但开关其实在变：最多等 [SETTLE_MS] 看最终状态。AndroidWorld（API 33、无 Shizuku）：
     * BluetoothAdapter.enable() 返回 false，蓝牙却开了；节点报失败，Agent 又去设置页里找开关，把 9 步耗光。
     * "开了没有"同时看系统设置 `bluetooth_on`（状态栏、设置页、AndroidWorld 判分读的都是它）：
     * 模拟器上 adapter.isEnabled 一直不变，这个值已经变了。
     */
    private suspend fun settlesTo(adapter: BluetoothAdapter, enabled: Boolean, context: WorkflowContext): Boolean {
        val app = try { context.appContext } catch (_: Exception) { null }
        // 读得到 bluetooth_on 就以它为准（两者不一致时，用户和判分看到的都是它）；读不到才看 adapter
        fun reached(): Boolean = (app?.let { globalOn(it) } ?: isEnabled(adapter)) == enabled
        val deadline = System.currentTimeMillis() + SETTLE_MS
        while (System.currentTimeMillis() < deadline) {
            if (reached()) return true
            kotlinx.coroutines.delay(300)
        }
        return reached()
    }

    private fun globalOn(context: android.content.Context): Boolean? = try {
        android.provider.Settings.Global.getInt(context.contentResolver, "bluetooth_on") == 1
    } catch (_: Exception) {
        null
    }

    @Suppress("DEPRECATION", "MissingPermission")
    private suspend fun setEnabled(adapter: BluetoothAdapter, enable: Boolean): Boolean {
        val shell = shellExecutor
        if (shell != null) {
            try {
                val cmd = if (enable) "svc bluetooth enable" else "svc bluetooth disable"
                val result = shell.exec(cmd, asRoot = false)
                Log.i(TAG, "Shell $cmd → ${result.take(200)}")
                if (!result.startsWith("Error") && !result.contains("Exception", ignoreCase = true)) {
                    return true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Shell bluetooth command failed, falling back to BluetoothAdapter: ${e.message}")
            }
        }
        return try {
            val ok = if (enable) adapter.enable() else adapter.disable()
            if (!ok && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Log.w(TAG, "BluetoothAdapter.enable/disable 在 Android 13+ 对普通应用无效，需 Shizuku")
            }
            ok
        } catch (e: SecurityException) {
            Log.w(TAG, "Bluetooth permission denied: ${e.message}")
            false
        }
    }

    companion object {
        private const val TAG = "HabitatBluetooth"
        private const val SETTLE_MS = 3_000L
    }
}
