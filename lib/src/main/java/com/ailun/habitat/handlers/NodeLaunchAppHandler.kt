package com.ailun.habitat.handlers

import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode
import com.ailun.habitat.api.IAccessibilityProvider
import com.ailun.habitat.api.IShellExecutor

/**
 * [ACTION_LAUNCH_APP]：启动指定应用。
 *
 * params：`package_name`（必填），`activity`（可选）
 */
class NodeLaunchAppHandler(
    private val provider: IAccessibilityProvider? = null,
    private val shellExecutor: IShellExecutor? = null,
) : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        val packageName = node.params?.get("package_name")?.toString()?.trim().orEmpty()
        if (packageName.isEmpty()) {
            Log.e(TAG, "Launch app failed: 'package_name' parameter is empty")
            context.variables["launch_success"] = false
            return node.next
        }

        val activityName = node.params?.get("activity")?.toString()?.trim()
            ?.takeIf { it.isNotEmpty() }

        try {
            // REORDER_TO_FRONT + SINGLE_TOP：若目标 App 已有任务，则拉回前台而非新建实例
            val bringToFrontFlags = Intent.FLAG_ACTIVITY_NEW_TASK + Intent.FLAG_ACTIVITY_REORDER_TO_FRONT + Intent.FLAG_ACTIVITY_SINGLE_TOP

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
                context.variables["launch_success"] = false
                context.variables["launch_error"] = "无法解析启动 Intent: $packageName（应用未安装？）"
                return node.next
            }

            // 后台 startActivity 豁免检测（Android 10+）：有悬浮窗权限时后台启动才被允许
            val hasOverlay = Settings.canDrawOverlays(context.appContext)
            context.variables["launch_has_overlay"] = hasOverlay

            context.appContext.startActivity(intent)
            Log.i(TAG, "Successfully launched app: $packageName")
            context.variables["launch_success"] = true
        } catch (e: Exception) {
            Log.e(TAG, "Launch app error for '$packageName': ${e.message}", e)
            context.variables["launch_success"] = false
            context.variables["launch_error"] = e.message ?: e.javaClass.simpleName
        }

        return node.next
    }

    companion object {
        private const val TAG = "HabitatLaunchApp"
    }
}
