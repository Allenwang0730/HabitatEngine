package com.ailun.habitat.handlers

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode
import com.ailun.habitat.api.INotificationSource
import com.ailun.habitat.paramInt
import com.ailun.habitat.paramString
import com.google.gson.Gson

/**
 * [ACTION_READ_NOTIFICATIONS]：读取当前通知列表（需在系统设置中启用本应用"通知使用权"）。
 * params：
 * - `output_var`（可选）：JSON 数组输出变量名，默认 "notifications_json"
 * - `limit`（可选）：最多条数，默认 20，范围 1-50
 * - `package`（可选）：只过滤该包名的通知
 * 输出：notifications_json(String，JSON 数组)、notification_count(Int)、
 * notifications_success / notification_success（同值，后者为旧名兼容）、notification_error
 *
 * 实现要点：全局通知只能经 NotificationListenerService 读取，由宿主注入 [INotificationSource]。
 * 未注入时回退 NotificationManager.getActiveNotifications()（仅本应用通知），结果标记为失败并给出明确错误。
 * 注意：属于高风险节点（隐私），建议在测试专用机验证。
 */
class NodeReadNotificationsHandler(
    private val source: INotificationSource? = null,
) : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        val outputVar = node.params?.get("output_var")?.toString()?.trim()?.ifEmpty { null }
            ?: "notifications_json"
        val limit = paramInt(node, context, "limit", 20).coerceIn(1, 50)
        val filterPkg = paramString(node, context, "package")?.trim()?.takeIf { it.isNotBlank() }
        setSuccess(context, false)

        try {
            val all: List<Map<String, Any?>> = source?.activeNotifications() ?: ownNotifications(context)
            val filtered = all
                .filter { filterPkg == null || it["package"] == filterPkg }
                .take(limit)

            val json = Gson().toJson(filtered)
            context.putVariable("notifications_json", json)
            context.putVariable("notification_count", filtered.size)
            context.putVariable(outputVar, json)
            if (source == null) {
                context.variables["notification_error"] =
                    "未注入通知监听源（INotificationSource），只能读取本应用自身通知"
                context.log("ReadNotifications: 无通知监听源，仅本应用通知 ${filtered.size} 条（视为失败）")
            } else {
                setSuccess(context, true)
                context.log("ReadNotifications: ${filtered.size} active (limit=$limit)")
            }
        } catch (e: SecurityException) {
            context.variables["notification_error"] =
                "缺少通知使用权（系统设置→通知使用权→启用本应用）: ${e.message}"
            context.log("ReadNotifications FAILED (no notification listener access)")
        } catch (e: Exception) {
            context.putVariable("notification_error", e.message ?: e.javaClass.simpleName)
            context.log("ReadNotifications FAILED: ${e.message}")
        }
        return node.next
    }

    private fun setSuccess(context: WorkflowContext, ok: Boolean) {
        context.putVariable("notifications_success", ok)
        context.putVariable("notification_success", ok)
    }

    private fun ownNotifications(context: WorkflowContext): List<Map<String, Any?>> {
        val nm = context.appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        return nm?.activeNotifications.orEmpty().map { n ->
            val extras = n.notification?.extras
            mapOf(
                "package" to n.packageName,
                "title" to (extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()),
                "text" to (extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()),
                "post_time" to n.postTime,
            )
        }
    }
}
