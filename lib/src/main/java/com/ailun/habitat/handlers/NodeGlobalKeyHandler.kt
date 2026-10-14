package com.ailun.habitat.handlers

import android.accessibilityservice.AccessibilityService
import android.util.Log
import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode
import com.ailun.habitat.api.IAccessibilityProvider
import com.ailun.habitat.api.IShellExecutor

/**
 * [ACTION_GLOBAL_KEY] : 发送全局按键事件。
 *
 * 执行策略:
 * 1. 优先通过 Shell 发送 `input keyevent`（需要 Shizuku/Root）
 * 2. Shell 不可用或失败时，通过 A11y `performGlobalAction()` 降级
 * 3. 均失败则 `key_success = false`
 *
 * params:
 * - `key` (必需): 按键标识符，支持以下值:
 *   - back, home, recents, notifications  (Shell + A11y 双通道)
 *   - quick_settings, power_dialog  (仅 A11y performGlobalAction，没有等价的 keyevent)
 *   - screenshot_toggle, enter, delete, space, tab  (仅 Shell)
 *
 * 输出变量:
 * - `key_success` (Boolean)
 */
class NodeGlobalKeyHandler(
    private val provider: IAccessibilityProvider?,
    private val shellExecutor: IShellExecutor?,
) : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        context.putVariable("key_success", false)
        val key = node.params?.get("key")?.toString()?.let { context.interpolate(it) }?.trim()?.lowercase().orEmpty()
        if (key.isEmpty()) {
            Log.w(TAG, "GlobalKey: 'key' parameter is empty")
            return node.next
        }

        val keyCode = KEY_MAP[key]
        if (keyCode == null && key !in A11Y_ACTION_MAP) {
            Log.w(TAG, "GlobalKey: unknown key '$key', valid keys: ${KEY_MAP.keys + A11Y_ACTION_MAP.keys}")
            return node.next
        }

        // Strategy 1: Shell
        if (shellExecutor != null && keyCode != null) {
            val shellSuccess = tryShell(key, keyCode, context)
            if (shellSuccess) {
                context.putVariable("key_success", true)
                return node.next
            }
            Log.d(TAG, "GlobalKey: shell failed for '$key', falling back to A11y")
        }

        // Strategy 2: A11y performGlobalAction
        if (tryA11y(key, context)) {
            context.putVariable("key_success", true)
            return node.next
        }

        context.putVariable("key_success", false)
        context.log("GlobalKey: key '$key' failed — shell and A11y both unavailable")
        return node.next
    }

    private suspend fun tryShell(key: String, keyCode: Int, context: WorkflowContext): Boolean {
        val executor = shellExecutor ?: return false
        Log.d(TAG, "GlobalKey: sending keyevent $keyCode via shell for '$key'")
        return try {
            val output = executor.exec("input keyevent $keyCode")
            val ok = !output.contains("Error", ignoreCase = true) &&
                     !output.contains("exception", ignoreCase = true)
            if (ok) context.log("GlobalKey: key '$key' (code=$keyCode) via shell")
            ok
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "GlobalKey: shell error for '$key'", e)
            false
        }
    }

    private fun tryA11y(key: String, context: WorkflowContext): Boolean {
        val service = provider?.getService() ?: run {
            Log.d(TAG, "GlobalKey: A11y service not available")
            return false
        }

        val globalAction = A11Y_ACTION_MAP[key] ?: run {
            Log.d(TAG, "GlobalKey: no A11y global action for '$key'")
            return false
        }

        Log.d(TAG, "GlobalKey: performing global action $globalAction via A11y for '$key'")
        return try {
            val ok = service.performGlobalAction(globalAction)
            if (ok) context.log("GlobalKey: key '$key' (action=$globalAction) via A11y")
            else Log.w(TAG, "GlobalKey: A11y performGlobalAction returned false for '$key'")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "GlobalKey: A11y error for '$key'", e)
            false
        }
    }

    companion object {
        private const val TAG = "HabitatGlobalKey"

        val KEY_MAP: Map<String, Int> = mapOf(
            "back"              to 4,
            "home"              to 3,
            "recents"           to 187,
            "notifications"     to 83,
            "screenshot_toggle" to 120,
            "enter"             to 66,
            "delete"            to 67,
            "space"             to 62,
            "tab"               to 61,
        )

        private val A11Y_ACTION_MAP: Map<String, Int> = mapOf(
            "back"           to AccessibilityService.GLOBAL_ACTION_BACK,
            "home"           to AccessibilityService.GLOBAL_ACTION_HOME,
            "recents"        to AccessibilityService.GLOBAL_ACTION_RECENTS,
            "notifications"  to AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS,
            "quick_settings" to AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS,
            "power_dialog"   to AccessibilityService.GLOBAL_ACTION_POWER_DIALOG,
        )
    }
}
