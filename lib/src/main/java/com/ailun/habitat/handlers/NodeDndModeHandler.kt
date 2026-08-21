package com.ailun.habitat.handlers

import android.app.NotificationManager
import android.content.Context
import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode

/**
 * [ACTION_DND_MODE]：免打扰模式（需"通知使用权" ACCESS_NOTIFICATION_POLICY）。
 * params：
 * - `action`（可选）："get"（默认）/"on"（完全静音）/"off"（全部允许）/"priority"（仅优先）/ "alarms"（仅闹钟）
 * 输出：dnd_state(String: off/on/priority/alarms/unknown)、dnd_success
 */
class NodeDndModeHandler : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        val action = node.params?.get("action")?.toString()?.trim()?.lowercase() ?: "get"
        val nm = context.appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (nm == null) {
            context.variables["dnd_success"] = false
            context.variables["dnd_error"] = "NotificationManager not available"
            return node.next
        }

        try {
            when (action) {
                "on" -> nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
                "off" -> nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                "priority" -> nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                "alarms" -> nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
                "get", "query" -> Unit
                else -> {
                    context.variables["dnd_success"] = false
                    context.variables["dnd_error"] = "未知 action: $action（on/off/priority/alarms/get）"
                    return node.next
                }
            }

            val filter = nm.currentInterruptionFilter
            val state = when (filter) {
                NotificationManager.INTERRUPTION_FILTER_ALL -> "off"
                NotificationManager.INTERRUPTION_FILTER_NONE -> "on"
                NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "priority"
                NotificationManager.INTERRUPTION_FILTER_ALARMS -> "alarms"
                else -> "unknown"
            }
            context.variables["dnd_state"] = state
            context.variables["dnd_success"] = true
            context.log("DndMode action=$action → $state")
        } catch (e: SecurityException) {
            context.variables["dnd_success"] = false
            context.variables["dnd_error"] = "缺少通知使用权（设置→通知使用权）: ${e.message}"
            context.log("DndMode FAILED (no notification policy access)")
        } catch (e: Exception) {
            context.variables["dnd_success"] = false
            context.variables["dnd_error"] = e.message ?: "Unknown error"
            context.log("DndMode FAILED: ${e.message}")
        }
        return node.next
    }
}
