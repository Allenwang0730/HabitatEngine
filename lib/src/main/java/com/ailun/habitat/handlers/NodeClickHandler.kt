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
        val selfPackage = context.appContext.packageName
        Log.d(TAG, "Attempting to click target: '$target'")

        val success = if (target.contains(",")) {
            val parts = target.split(",")
            val x = parts[0].trim().toIntOrNull()
            val y = parts[1].trim().toIntOrNull()
            if (x != null && y != null) {
                Log.d(TAG, "Clicking coordinate: ($x, $y)")
                HabitatAccessibility.dispatchTap(service, x, y)
            } else {
                performA11yClick(service, target, selfPackage)
            }
        } else {
            performA11yClick(service, target, selfPackage)
        }

        Log.i(TAG, "Click result for '$target': $success")
        context.variables["click_success"] = success
        return node.next
    }

    private suspend fun performA11yClick(
        service: android.accessibilityservice.AccessibilityService,
        selector: String,
        selfPackage: String,
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
                pkg != null && pkg != selfPackage
            }

            if (filteredNodes.isEmpty()) {
                Log.w(TAG, "No external node found for: '$selector'")
                return false
            }

            val targetNode = filteredNodes.find { it.isVisibleToUser } ?: filteredNodes[0]
            val r = Rect()
            targetNode.getBoundsInScreen(r)
            Log.d(TAG, "Target: text=${targetNode.text}, pkg=${targetNode.packageName}, bounds=$r")

            var ok = HabitatAccessibility.dispatchTap(service, r.centerX(), r.centerY())
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

    companion object {
        private const val TAG = "HabitatClick"
    }
}
