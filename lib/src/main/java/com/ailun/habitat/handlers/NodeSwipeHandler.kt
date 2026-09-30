package com.ailun.habitat.handlers

import com.ailun.habitat.HabitatAccessibility
import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode
import com.ailun.habitat.api.IAccessibilityProvider
import com.ailun.habitat.paramIntOrNull
import com.ailun.habitat.paramLong

/**
 * [ACTION_SWIPE]：直线滑动手势（无障碍）。
 * params：`x1`,`y1`,`x2`,`y2`（整数，支持 "500"/"${x}"），`duration` 毫秒可选，默认 400（限制 1..60000）。
 * 输出：swipe_success（进入时先置 false）
 */
class NodeSwipeHandler(
    private val a11yProvider: IAccessibilityProvider? = null
) : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        context.putVariable("swipe_success", false)
        val service = a11yProvider?.getService() ?: run {
            context.log("Swipe FAILED: 无障碍服务未运行")
            return node.next
        }
        val x1 = paramIntOrNull(node, context, "x1")
        val y1 = paramIntOrNull(node, context, "y1")
        val x2 = paramIntOrNull(node, context, "x2")
        val y2 = paramIntOrNull(node, context, "y2")
        if (x1 == null || y1 == null || x2 == null || y2 == null) {
            context.log("Swipe FAILED: x1/y1/x2/y2 缺失或非数字")
            return node.next
        }
        val duration = paramLong(node, context, "duration", 400L).coerceIn(1L, 60_000L)

        val ok = HabitatAccessibility.dispatchSwipe(service, x1, y1, x2, y2, duration)
        context.putVariable("swipe_success", ok)
        context.log("Swipe ($x1,$y1)→($x2,$y2) dur=${duration}ms ok=$ok")
        return node.next
    }
}
