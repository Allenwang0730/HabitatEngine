package com.ailun.habitat.handlers

import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode

/**
 * [ACTION_LAUNCH_APP]：启动指定应用。
 *
 * params：`package_name`（必填），`activity`（可选）
 */
class NodeLaunchAppHandler : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        context.putVariable("launch_success", false)
        val intentAction = node.params?.get("intent_action")?.toString()?.let { context.interpolate(it) }?.trim().orEmpty()
        if (intentAction.isNotEmpty()) return launchSettingsAction(node, context, intentAction)

        val packageName = node.params?.get("package_name")?.toString()?.let { context.interpolate(it) }?.trim().orEmpty()
        if (packageName.isEmpty()) {
            Log.e(TAG, "Launch app failed: 'package_name' parameter is empty")
            context.putVariable("launch_success", false)
            return node.next
        }

        val activityName = node.params?.get("activity")?.toString()?.let { context.interpolate(it) }?.trim()
            ?.takeIf { it.isNotEmpty() }

        try {
            // REORDER_TO_FRONT + SINGLE_TOP：若目标 App 已有任务，则拉回前台而非新建实例
            val bringToFrontFlags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP

            val intent = if (activityName != null) {
                Intent().apply {
                    setClassName(packageName, activityName)
                    addFlags(bringToFrontFlags)
                }
            } else {
                context.appContext.packageManager.getLaunchIntentForPackage(packageName)?.apply {
                    addFlags(bringToFrontFlags)
                }
            }

            if (intent == null) {
                Log.e(TAG, "Launch app failed: unable to resolve intent for package '$packageName'")
                context.putVariable("launch_success", false)
                context.putVariable("launch_error", "无法解析启动 Intent: $packageName（应用未安装？）")
                return node.next
            }

            // 后台 startActivity 豁免检测（Android 10+）：有悬浮窗权限时后台启动才被允许
            val hasOverlay = Settings.canDrawOverlays(context.appContext)
            context.putVariable("launch_has_overlay", hasOverlay)

            context.appContext.startActivity(intent)
            Log.i(TAG, "Successfully launched app: $packageName")
            context.putVariable("launch_success", true)
        } catch (e: Exception) {
            Log.e(TAG, "Launch app error for '$packageName': ${e.message}", e)
            context.putVariable("launch_success", false)
            context.putVariable("launch_error", e.message ?: e.javaClass.simpleName)
        }

        return node.next
    }

    /**
     * `intent_action`：直接打开系统设置的某一页（如 `android.settings.BLUETOOTH_SETTINGS`）。
     * 只接受 `android.settings.` 开头的 action——这个入口只用于"导航到设置页"，
     * 不能被拿来发 CALL/SENDTO/VIEW 这类有外部副作用的 Intent。
     */
    private fun launchSettingsAction(node: WorkflowNode, context: WorkflowContext, action: String): String? {
        val allowed = action.startsWith(SETTINGS_ACTION_PREFIX) || action in EXTRA_SETTINGS_ACTIONS
        if (!allowed || action.length > 120 || action.any { it.isWhitespace() }) {
            context.putVariable("launch_error", "intent_action 只允许 $SETTINGS_ACTION_PREFIX* : $action")
            return node.next
        }
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val resolved = context.appContext.packageManager.resolveActivity(intent, 0)
        if (resolved == null) {
            context.putVariable("launch_error", "本机没有响应 $action 的设置页")
            return node.next
        }
        return try {
            context.appContext.startActivity(intent)
            context.putVariable("launch_success", true)
            context.putVariable("launch_package", resolved.activityInfo?.packageName.orEmpty())
            node.next
        } catch (e: Exception) {
            Log.e(TAG, "Launch settings action '$action' failed: ${e.message}", e)
            context.putVariable("launch_error", e.message ?: e.javaClass.simpleName)
            node.next
        }
    }

    companion object {
        private const val TAG = "HabitatLaunchApp"
        const val SETTINGS_ACTION_PREFIX = "android.settings."

        /** 不在 android.settings.* 下、但同样只是打开设置页的 action。 */
        val EXTRA_SETTINGS_ACTIONS = setOf("android.intent.action.POWER_USAGE_SUMMARY")
    }
}
