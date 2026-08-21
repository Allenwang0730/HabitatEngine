package com.ailun.habitat.handlers

import android.accessibilityservice.AccessibilityService
import android.os.Build
import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode
import com.ailun.habitat.api.IAccessibilityProvider

/**
 * [ACTION_GLOBAL_NAV]：无障碍全局导航动作（需无障碍服务）。
 * params：
 * - `action`（必填）：back/home/recents/notifications/quick_settings/power_dialog/lock_screen/take_screenshot
 * 输出：nav_success、nav_error
 *
 * 说明：与 ACTION_GLOBAL_KEY（keyevent + a11y 双通道）互补，本节点走纯 performGlobalAction，
 * 并额外支持 a11y 专属动作（power_dialog/lock_screen/take_screenshot，受系统版本限制）。
 */
class NodeGlobalNavHandler(
    private val provider: IAccessibilityProvider?,
) : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        val action = node.params?.get("action")?.toString()?.trim()?.lowercase().orEmpty()
        if (action.isEmpty()) {
            context.variables["nav_success"] = false
            context.variables["nav_error"] = "缺少 'action' 参数"
            return node.next
        }

        val service = provider?.getService()
        if (service == null) {
            context.variables["nav_success"] = false
            context.variables["nav_error"] = "无障碍服务未连接"
            context.log("GlobalNav FAILED: a11y not connected")
            return node.next
        }

        val globalAction = ACTION_MAP[action]
        if (globalAction == null) {
            context.variables["nav_success"] = false
            context.variables["nav_error"] = "未知 action: $action（可用: ${ACTION_MAP.keys}）"
            return node.next
        }

        // power_dialog / take_screenshot 需要 API 30+
        if ((action == "power_dialog" || action == "take_screenshot") && Build.VERSION.SDK_INT < 30) {
            context.variables["nav_success"] = false
            context.variables["nav_error"] = "action '$action' 需要 Android 11+"
            return node.next
        }

        val ok = try {
            service.performGlobalAction(globalAction)
        } catch (e: Exception) {
            context.variables["nav_success"] = false
            context.variables["nav_error"] = e.message ?: "Unknown error"
            context.log("GlobalNav FAILED: ${e.message}")
            return node.next
        }

        context.variables["nav_success"] = ok
        if (!ok) context.variables["nav_error"] = "performGlobalAction 返回 false"
        context.log("GlobalNav action=$action → $ok")
        return node.next
    }

    companion object {
        val ACTION_MAP: Map<String, Int> = mapOf(
            "back" to AccessibilityService.GLOBAL_ACTION_BACK,
            "home" to AccessibilityService.GLOBAL_ACTION_HOME,
            "recents" to AccessibilityService.GLOBAL_ACTION_RECENTS,
            "notifications" to AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS,
            "quick_settings" to AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS,
            "power_dialog" to AccessibilityService.GLOBAL_ACTION_POWER_DIALOG,
            "lock_screen" to AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN,
            "take_screenshot" to AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT,
        )
    }
}
