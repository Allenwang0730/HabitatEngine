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
                "on" -> success = setEnabled(bluetoothAdapter, true)
                "off" -> success = setEnabled(bluetoothAdapter, false)
                "toggle" -> success = setEnabled(bluetoothAdapter, !isEnabled(bluetoothAdapter))
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
    }
}
