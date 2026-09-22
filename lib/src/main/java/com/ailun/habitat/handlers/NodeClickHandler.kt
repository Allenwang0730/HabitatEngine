package com.ailun.habitat.handlers

import android.graphics.Rect
import com.ailun.habitat.HabitatAccessibility
import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.ailun.habitat.api.IAccessibilityProvider

/**
 * [ACTION_CLICK]：点击指定目标。
 *
 * params:
 * - `target`（必填）："x,y"坐标 或 界面文本（按文本匹配点击）
 * - `region`（可选，仅文本匹配）：限定匹配节点在屏幕区域内，
 *   "top"/"bottom"/"left"/"right"/"center" 或 归一化 "x1,y1,x2,y2"（0-1 相对屏幕，如 0,0.5,1,1 = 下半屏）
 * - `occurrence`（可选，默认 0，仅文本匹配）：在 region 过滤后，取第几个匹配（按屏幕从上到下排序，0 起）
 */
class NodeClickHandler(
    private val a11yProvider: IAccessibilityProvider? = null
) : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        val service = a11yProvider?.getService()
            ?: run {
                Log.e(TAG, "Click failed: Accessibility Service not running")
                return node.next
            }

        val raw = node.params?.get("target")?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) {
            Log.e(TAG, "Click failed: 'target' parameter is empty")
            return node.next
        }

        val target = context.interpolate(raw)
        val regionSpec = context.interpolate(node.params?.get("region")?.toString()?.trim() ?: "")
        val occurrence = (node.params?.get("occurrence") as? Number)?.toInt() ?: 0
        // 点击坐标偏移（相对目标中心，屏幕 Y 向下为正；offset_y 为负表示向上偏移）
        val offsetX = node.params?.get("offset_x")?.toString()?.toIntOrNull() ?: 0
        val offsetY = node.params?.get("offset_y")?.toString()?.toIntOrNull() ?: 0
        val selfPackage = context.appContext.packageName
        Log.d(TAG, "Attempting to click target: '$target' region='$regionSpec' occurrence=$occurrence offset=($offsetX,$offsetY)")

        val success = if (target.contains(",")) {
            val parts = target.split(",")
            val x = parts[0].trim().toIntOrNull()
            val y = parts[1].trim().toIntOrNull()
            if (x != null && y != null) {
                Log.d(TAG, "Clicking coordinate: ($x, $y)")
                HabitatAccessibility.dispatchTap(service, x, y)
            } else {
                // 含逗号但非坐标 → 按文本（region/occurrence 不适用坐标，文本用）
                performA11yClick(service, target, selfPackage, resolveRegionSpec(service, regionSpec), occurrence, offsetX, offsetY)
            }
        } else {
            performA11yClick(service, target, selfPackage, resolveRegionSpec(service, regionSpec), occurrence, offsetX, offsetY)
        }

        Log.i(TAG, "Click result for '$target': $success")
        context.variables["click_success"] = success
        return node.next
    }

    private suspend fun performA11yClick(
        service: android.accessibilityservice.AccessibilityService,
        selector: String,
        selfPackage: String,
        region: Rect?,
        occurrence: Int,
        offsetX: Int = 0,
        offsetY: Int = 0,
    ): Boolean {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        val foundNodes = mutableListOf<AccessibilityNodeInfo>()
        try {
            service.rootInActiveWindow?.let { roots.add(it) }
            service.windows.forEach { win -> win.root?.let { roots.add(it) } }

            for (root in roots) {
                root.findAccessibilityNodeInfosByViewId(selector)?.let { foundNodes.addAll(it) }
                root.findAccessibilityNodeInfosByText(selector)?.let { foundNodes.addAll(it) }
            }

            val filteredNodes = foundNodes.filter { node ->
                val pkg = node.packageName?.toString()
                pkg != null && pkg != selfPackage && inRegion(node, region)
            }

            if (filteredNodes.isEmpty()) {
                Log.w(TAG, "No external node found for: '$selector'${if (region != null) " in region $region" else ""}")
                return false
            }

            // 指定 occurrence：按屏幕从上到下排序取第 N 个；否则维持"第一个可见"
            val targetNode = if (occurrence > 0 && occurrence < filteredNodes.size) {
                filteredNodes.sortedBy { rectTop(it) }[occurrence]
            } else if (occurrence == 0) {
                filteredNodes.find { it.isVisibleToUser } ?: filteredNodes[0]
            } else {
                filteredNodes.find { it.isVisibleToUser } ?: filteredNodes[0]
            }

            val r = Rect()
            targetNode.getBoundsInScreen(r)
            Log.d(TAG, "Target: text=${targetNode.text}, pkg=${targetNode.packageName}, bounds=$r, offset=($offsetX,$offsetY)")

            var ok = HabitatAccessibility.dispatchTap(service, r.centerX() + offsetX, r.centerY() + offsetY)
            if (!ok) {
                ok = targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (!ok && !targetNode.isClickable) {
                    var parent = targetNode.parent
                    while (parent != null) {
                        if (parent.isClickable) {
                            ok = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            parent.recycle()
                            break
                        }
                        val toRecycle = parent
                        parent = parent.parent
                        toRecycle.recycle()
                    }
                }
            }

            return ok
        } finally {
            foundNodes.forEach { try { it.recycle() } catch (_: Exception) {} }
            roots.forEach { try { it.recycle() } catch (_: Exception) {} }
        }
    }

    // ── region 辅助（与 NodeFindElementHandler 同语义）──

    private fun resolveRegionSpec(service: android.accessibilityservice.AccessibilityService, spec: String): Rect? {
        if (spec.isBlank()) return null
        val screen = getScreenRect(service)
        val w = screen.width()
        val h = screen.height()
        return when (spec.trim().lowercase()) {
            "top" -> Rect(screen.left, screen.top, screen.right, screen.top + h / 2)
            "bottom" -> Rect(screen.left, screen.top + h / 2, screen.right, screen.bottom)
            "left" -> Rect(screen.left, screen.top, screen.left + w / 2, screen.bottom)
            "right" -> Rect(screen.left + w / 2, screen.top, screen.right, screen.bottom)
            "center" -> Rect(screen.left + w / 4, screen.top + h / 4, screen.right - w / 4, screen.bottom - h / 4)
            else -> {
                val parts = spec.split(",").map { it.trim().toFloatOrNull() }
                if (parts.size == 4 && parts.all { it != null }) {
                    Rect(
                        screen.left + (parts[0]!! * w).toInt(),
                        screen.top + (parts[1]!! * h).toInt(),
                        screen.left + (parts[2]!! * w).toInt(),
                        screen.top + (parts[3]!! * h).toInt(),
                    )
                } else null
            }
        }
    }

    private fun getScreenRect(service: android.accessibilityservice.AccessibilityService): Rect {
        return try {
            val r = Rect()
            service.rootInActiveWindow?.getBoundsInScreen(r)
            if (r.isEmpty) Rect(0, 0, 1080, 2400) else r
        } catch (_: Exception) {
            Rect(0, 0, 1080, 2400)
        }
    }

    private fun inRegion(node: AccessibilityNodeInfo, region: Rect?): Boolean {
        if (region == null) return true
        val r = Rect()
        node.getBoundsInScreen(r)
        return region.contains(r.centerX(), r.centerY())
    }

    private fun rectTop(node: AccessibilityNodeInfo): Int {
        val r = Rect()
        node.getBoundsInScreen(r)
        return r.top
    }

    companion object {
        private const val TAG = "HabitatClick"
    }
}
