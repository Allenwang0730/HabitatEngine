package com.ailun.habitat.handlers

import android.content.ContentResolver
import android.provider.Settings
import android.util.Log
import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode
import com.ailun.habitat.paramIntOrNull

/**
 * [ACTION_BRIGHTNESS]：控制屏幕亮度及获取亮度信息。
 *
 * params：
 *   - `action`（必填："set"/"get"/"auto_on"/"auto_off"）
 *   - `value`（用于 set：0-255）
 * 输出：brightness_value、brightness_mode、brightness_success、brightness_error
 *
 * set/auto_on/auto_off 需要"修改系统设置"（WRITE_SETTINGS）授权；未授权时直接失败（不再从后台拉起设置页），
 * 由用户在 App 内引导授权。
 */
class NodeBrightnessHandler : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        context.putVariable("brightness_success", false)
        val action = node.params?.get("action")?.toString()?.let { context.interpolate(it) }?.trim()?.lowercase().orEmpty()
        if (action.isEmpty()) {
            Log.e(TAG, "Brightness failed: 'action' parameter is empty")
            return node.next
        }

        val contentResolver = context.appContext.contentResolver

        if (action != "get" && !Settings.System.canWrite(context.appContext)) {
            val msg = "缺少\"修改系统设置\"权限（WRITE_SETTINGS），请在 App 内授权后重试"
            context.putVariable("brightness_error", msg)
            context.log("Brightness FAILED: $msg")
            return node.next
        }

        try {
            val ok = when (action) {
                "set" -> handleSet(node, context, contentResolver)
                "get" -> handleGet(context, contentResolver)
                "auto_on" -> handleAutoMode(context, contentResolver, true)
                "auto_off" -> handleAutoMode(context, contentResolver, false)
                else -> {
                    Log.e(TAG, "Brightness failed: unknown action '$action'")
                    false
                }
            }
            context.putVariable("brightness_success", ok)
        } catch (e: SecurityException) {
            context.putVariable("brightness_error", "WRITE_SETTINGS 权限被拒: ${e.message}")
            Log.e(TAG, "Brightness error: missing WRITE_SETTINGS permission - ${e.message}")
        } catch (e: Exception) {
            context.putVariable("brightness_error", e.message ?: e.javaClass.simpleName)
            Log.e(TAG, "Brightness error for action '$action': ${e.message}", e)
        }

        return node.next
    }

    private fun handleSet(
        node: WorkflowNode,
        context: WorkflowContext,
        contentResolver: ContentResolver,
    ): Boolean {
        val brightness = paramIntOrNull(node, context, "value") ?: run {
            Log.e(TAG, "Brightness set failed: 'value' parameter is missing or invalid")
            context.putVariable("brightness_error", "'value' 缺失或非数字")
            return false
        }
        val clamped = brightness.coerceIn(0, 255)
        val ok = Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, clamped)
        if (ok) context.putVariable("brightness_value", clamped)
        Log.i(TAG, "Brightness set to $clamped (0-255): $ok")
        return ok
    }

    private fun handleGet(
        context: WorkflowContext,
        contentResolver: ContentResolver,
    ): Boolean {
        return try {
            val brightness = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
            val mode = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE)
            val modeLabel = if (mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) "auto" else "manual"
            context.putVariable("brightness_value", brightness)
            context.putVariable("brightness_mode", modeLabel)
            Log.i(TAG, "Brightness get: value=$brightness, mode=$modeLabel")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Brightness get failed: ${e.message}", e)
            context.putVariable("brightness_error", e.message ?: e.javaClass.simpleName)
            false
        }
    }

    private fun handleAutoMode(
        context: WorkflowContext,
        contentResolver: ContentResolver,
        enable: Boolean,
    ): Boolean {
        val mode = if (enable) {
            Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        } else {
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        }
        val ok = Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, mode)
        val modeLabel = if (enable) "auto" else "manual"
        if (ok) context.putVariable("brightness_mode", modeLabel)
        Log.i(TAG, "Brightness mode set to $modeLabel: $ok")
        return ok
    }

    companion object {
        private const val TAG = "HabitatBrightness"
    }
}
