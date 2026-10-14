package com.ailun.habitat.handlers

import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode
import com.ailun.habitat.api.IAccessibilityProvider

/**
 * [ACTION_GET_APP_INFO]：获取当前前台应用包名和 Activity。
 */
class NodeAppInfoHandler(
    private val a11yProvider: IAccessibilityProvider? = null
) : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        val outputVar = node.params?.get("output_var")?.toString()?.trim()
            ?.ifEmpty { null } ?: "current_app"
        val activityVar = node.params?.get("activity_var")?.toString()?.trim()
            ?.ifEmpty { null } ?: "current_activity"

        val provider = a11yProvider
        // foregroundPackage 为空（无障碍刚连接未收到窗口事件）时退回活动窗口 root 的包名
        val packageName = provider?.foregroundPackage
            ?: provider?.getService()?.rootInActiveWindow?.let { root ->
                try { root.packageName?.toString() }
                finally { @Suppress("DEPRECATION") root.recycle() }
            }
            ?: "unknown"
        val className = provider?.foregroundActivity ?: "unknown"

        context.putVariable(outputVar, packageName)
        context.putVariable(activityVar, className)
        context.log("AppInfo: $packageName / $className")
        return node.next
    }
}
