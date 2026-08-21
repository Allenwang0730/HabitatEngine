package com.ailun.habitat.handlers

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode
import com.google.gson.Gson

/**
 * [ACTION_READ_NOTIFICATIONS]：读取当前通知列表（需在系统设置中启用本应用"通知使用权"）。
 * params：
 * - `output_var`（可选）：JSON 数组输出变量名，默认 "notifications_json"
 * - `limit`（可选）：最多条数，默认 20，范围 1-50
 * - `package`（可选）：只过滤该包名的通知
 * 输出：notifications_json(String，JSON 数组)、notification_count(Int)、notification_success
 *
 * 实现要点：NotificationManager.getActiveNotifications()（应用启用 NotificationListenerService
 * 后可见全部通知）。无通知使用权时 SecurityException → 如实失败。
 * 注意：属于高风险节点（隐私），建议在测试专用机验证。
 */
class NodeReadNotificationsHandler : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        val outputVar = node.params?.get("output_var")?.toString()?.trim()?.ifEmpty { null }
            ?: "notifications_json"
        val limit = (node.params?.get("limit") as? Number)?.toInt()?.coerceIn(1, 50) ?: 20
        val filterPkg = node.params?.get("package")?.toString()?.takeIf { it.isNotBlank() }

        try {
            val nm = context.appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val active = nm?.activeNotifications.orEmpty()
            val filtered = active
                .filter { filterPkg == null || it.packageName == filterPkg }
                .take(limit)

            val list = filtered.map { n ->
                val extras = n.notification?.extras
                mapOf(
                    "package" to n.packageName,
                    "title" to (extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()),
                    "text" to (extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()),
                    "post_time" to n.postTime,
                )
            }

            val json = Gson().toJson(list)
            context.variables["notifications_json"] = json
            context.variables["notification_count"] = filtered.size
            if (outputVar != null) context.variables[outputVar] = json
            context.variables["notification_success"] = true
            context.log("ReadNotifications: ${filtered.size} active (limit=$limit)")
        } catch (e: SecurityException) {
            context.variables["notification_success"] = false
            context.variables["notification_error"] =
                "缺少通知使用权（系统设置→通知使用权→启用本应用）: ${e.message}"
            context.log("ReadNotifications FAILED (no notification listener access)")
        } catch (e: Exception) {
            context.variables["notification_success"] = false
            context.variables["notification_error"] = e.message ?: "Unknown error"
            context.log("ReadNotifications FAILED: ${e.message}")
        }
        return node.next
    }
}
