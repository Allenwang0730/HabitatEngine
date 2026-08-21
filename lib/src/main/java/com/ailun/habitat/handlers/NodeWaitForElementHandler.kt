package com.ailun.habitat.handlers

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode
import com.ailun.habitat.api.IAccessibilityProvider
import kotlinx.coroutines.delay

/**
 * [ACTION_WAIT_FOR_ELEMENT]：等待界面元素出现/消失（需无障碍服务，轮询实现）。
 * params：
 * - `selector`（必填）：查找关键字/控件 ID/类名，支持 ${var}
 * - `mode`（可选）："text"（默认，匹配文本或 contentDescription）/ "id"（viewId 全名如 com.pkg:id/xxx）/ "class"（类名）
 * - `wait_for`（可选）："appear"（默认）/ "disappear"
 * - `timeout` / `timeout_ms`（可选）：最长等待毫秒，默认 10000
 * - `output_var`（可选）：结果变量名，默认 "element_found"
 * 输出：element_found(Boolean，条件是否满足)
 *
 * 实现要点：每 500ms 轮询 rootInActiveWindow，符合无 Looper 线程约束；
 * 查询返回的节点必须 recycle。
 */
class NodeWaitForElementHandler(
    private val provider: IAccessibilityProvider?,
) : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        val rawSelector = node.params?.get("selector")?.toString()?.trim().orEmpty()
        if (rawSelector.isEmpty()) {
            context.variables["element_found"] = false
            context.log("WaitForElement FAILED: no 'selector'")
            return node.next
        }
        val selector = context.interpolate(rawSelector)
        val mode = node.params?.get("mode")?.toString()?.trim()?.lowercase() ?: "text"
        val waitFor = node.params?.get("wait_for")?.toString()?.trim()?.lowercase() ?: "appear"
        val timeoutMs = (node.params?.get("timeout") as? Number)?.toLong()
            ?: (node.params?.get("timeout_ms") as? Number)?.toLong()
            ?: 10_000L
        val outputVar = node.params?.get("output_var")?.toString()?.trim()?.ifEmpty { null }
            ?: "element_found"

        val service = provider?.getService()
        if (service == null) {
            context.variables[outputVar] = false
            context.log("WaitForElement FAILED: a11y not connected")
            return node.next
        }

        val start = System.currentTimeMillis()
        var satisfied = false
        while (System.currentTimeMillis() - start < timeoutMs) {
            val present = findElement(service, selector, mode)
            satisfied = if (waitFor == "disappear") !present else present
            if (satisfied) break
            delay(500)
        }

        context.variables[outputVar] = satisfied
        context.log("WaitForElement selector='$selector' mode=$mode waitFor=$waitFor → $satisfied (${System.currentTimeMillis() - start}ms)")
        return node.next
    }

    private fun findElement(service: AccessibilityService, selector: String, mode: String): Boolean {
        val root = service.rootInActiveWindow ?: return false
        try {
            return when (mode) {
                "id" -> byViewId(root, selector)
                "class" -> containsClassRecursive(root, selector)
                else -> containsTextRecursive(root, selector) // text / content_desc
            }
        } finally {
            runCatching { root.recycle() }
        }
    }

    private fun byViewId(root: AccessibilityNodeInfo, viewId: String): Boolean {
        val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
        return try {
            nodes.isNotEmpty()
        } finally {
            nodes.forEach { runCatching { it.recycle() } }
        }
    }

    private fun containsTextRecursive(node: AccessibilityNodeInfo, keyword: String): Boolean {
        if (!node.isVisibleToUser) return false
        val text = node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        if (text.contains(keyword, ignoreCase = true) || desc.contains(keyword, ignoreCase = true)) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                if (containsTextRecursive(child, keyword)) return true
            } finally {
                runCatching { child.recycle() }
            }
        }
        return false
    }

    private fun containsClassRecursive(node: AccessibilityNodeInfo, className: String): Boolean {
        if (node.className?.toString()?.contains(className, ignoreCase = true) == true) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                if (containsClassRecursive(child, className)) return true
            } finally {
                runCatching { child.recycle() }
            }
        }
        return false
    }
}
