package com.ailun.habitat.handlers

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.ailun.habitat.HabitatAccessibility
import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode
import com.ailun.habitat.api.IAccessibilityProvider
import com.ailun.habitat.paramLong

/**
 * [ACTION_LONG_PRESS] : 在指定目标上执行长按操作。
 *
 * params:
 * - `target` (必需): 目标描述，支持两种格式:
 *   - 坐标格式 "x,y": 例如 "500,1200"，使用无障碍手势在指定坐标长按
 *   - 选择器格式: 通过文本或 viewId 查找无障碍节点并执行 ACTION_LONG_CLICK
 * - `duration` (可选, 默认 800 毫秒，限制在 1..60000): 对于坐标模式的长按持续时间
 *
 * 输出变量:
 * - `long_press_success` (Boolean): 长按操作是否成功执行
 */
class NodeLongPressHandler(
    private val provider: IAccessibilityProvider?,
) : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        context.putVariable("long_press_success", false)
        val rawTarget = node.params?.get("target")?.toString()?.trim().orEmpty()
        if (rawTarget.isEmpty()) {
            Log.w(TAG, "LongPress: 'target' parameter is empty")
            context.putVariable("long_press_success", false)
            return node.next
        }

        val target = context.interpolate(rawTarget)
        val durationMs = paramLong(node, context, "duration", DEFAULT_DURATION_MS).coerceIn(1L, MAX_DURATION_MS)

        val service = provider?.getService() ?: run {
            Log.e(TAG, "LongPress: Accessibility service not available")
            context.putVariable("long_press_success", false)
            return node.next
        }

        Log.d(TAG, "LongPress: target='$target', duration=${durationMs}ms")

        val normalizedTarget = target.trim()
        val success = if (normalizedTarget.contains(",")) {
            val parts = normalizedTarget.split(",")
            val x = parts[0].trim().toIntOrNull()
            val y = parts[1].trim().toIntOrNull()
            if (x != null && y != null) {
                longPressCoordinate(service, x, y, durationMs)
            } else {
                Log.w(TAG, "LongPress: invalid coordinate format '$target'")
                false
            }
        } else {
            longPressAccessibilityNode(
                service, normalizedTarget, context.appContext.packageName, durationMs,
            )
        }

        context.putVariable("long_press_success", success)
        if (success) {
            context.log("LongPress: operation successful on '$target'")
        } else {
            context.log("LongPress: operation failed on '$target'")
        }

        return node.next
    }

    /**
     * 通过无障碍 GestureDescription 在指定坐标执行长按。
     * 长按通过设置 StrokeDescription 的持续时间实现 — 起点与终点相同，持续按住。
     */
    private suspend fun longPressCoordinate(
        service: AccessibilityService,
        x: Int,
        y: Int,
        durationMs: Long,
    ): Boolean {
        if (x < 0 || y < 0) {
            Log.w(TAG, "LongPress: negative coordinates ($x, $y)")
            return false
        }
        return HabitatAccessibility.dispatchLongPress(service, x, y, durationMs)
    }

    /**
     * 通过无障碍节点查找来执行长按。
     * 先按 viewId 和文本搜索目标节点，然后执行 ACTION_LONG_CLICK。
     * 策略顺序:
     *   1. 直接在目标节点上执行 ACTION_LONG_CLICK
     *   2. 如果节点不可长按，向上查找可长按的祖先节点
     *   3. 都失败则回退到在节点中心坐标执行手势长按
     */
    private suspend fun longPressAccessibilityNode(
        service: AccessibilityService,
        selector: String,
        selfPackage: String,
        durationMs: Long,
    ): Boolean {
        val roots = mutableListOf<AccessibilityNodeInfo>()

        try {
            service.rootInActiveWindow?.let { roots.add(it) }
            service.windows.forEach { win ->
                win.root?.let { roots.add(it) }
            }

            if (roots.isEmpty()) {
                Log.w(TAG, "LongPress: no accessibility roots available")
                return false
            }

            for (root in roots) {
                val result = searchAndLongPressInRoot(
                    root, selector, selfPackage, durationMs, service,
                )
                if (result) return true
            }

            Log.w(TAG, "LongPress: no matching node found for selector '$selector'")
            return false
        } finally {
            roots.forEach { root ->
                try {
                    root.recycle()
                } catch (_: Exception) {
                }
            }
        }
    }

    /**
     * 在单个无障碍根节点树中搜索目标并执行长按。
     * 搜索方式：先按 viewId 查找，再按文本查找。
     * 排除本应用自身节点（如悬浮窗、日志窗等）。
     */
    private suspend fun searchAndLongPressInRoot(
        root: AccessibilityNodeInfo,
        selector: String,
        selfPackage: String,
        durationMs: Long,
        service: AccessibilityService,
    ): Boolean {
        val foundNodes = mutableListOf<AccessibilityNodeInfo>()

        // 按 viewId 查找
        root.findAccessibilityNodeInfosByViewId(selector)?.let { list ->
            foundNodes.addAll(list)
        }
        // 按文本查找
        if (foundNodes.isEmpty()) {
            root.findAccessibilityNodeInfosByText(selector)?.let { list ->
                foundNodes.addAll(list)
            }
        }

        if (foundNodes.isEmpty()) return false

        // 排除本应用自身的节点
        val externalNodes = foundNodes.filter { node ->
            val pkg = node.packageName?.toString()
            pkg != null && pkg != selfPackage
        }

        if (externalNodes.isEmpty()) {
            foundNodes.forEach { it.recycle() }
            return false
        }

        // 优先选择可见节点
        val targetNode = externalNodes.find { it.isVisibleToUser } ?: externalNodes.first()

        // 回收其他节点
        foundNodes.forEach { if (it !== targetNode) it.recycle() }

        val nodeRect = Rect()
        targetNode.getBoundsInScreen(nodeRect)
        Log.d(
            TAG, "LongPress: found node text='${targetNode.text}', " +
                "pkg=${targetNode.packageName}, bounds=$nodeRect, " +
                "clickable=${targetNode.isClickable}, longClickable=${targetNode.isLongClickable}",
        )

        // 策略 1: 直接尝试 ACTION_LONG_CLICK
        var result = try {
            targetNode.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
        } catch (e: Exception) {
            Log.e(TAG, "LongPress: ACTION_LONG_CLICK threw exception", e)
            false
        }

        if (result) {
            targetNode.recycle()
            return true
        }

        // 策略 2: 如果节点不可长按，向上查找可长按的祖先
        if (!targetNode.isLongClickable) {
            var parent: AccessibilityNodeInfo? = targetNode.parent
            var depth = 0
            while (parent != null && depth < MAX_ANCESTOR_DEPTH) {
                depth++
                if (parent.isLongClickable) {
                    Log.d(TAG, "LongPress: found long-clickable ancestor: ${parent.className}")
                    result = try {
                        parent.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                    } catch (e: Exception) {
                        Log.e(TAG, "LongPress: ancestor ACTION_LONG_CLICK failed", e)
                        false
                    }
                }
                if (result) {
                    parent.recycle()
                    parent = null
                    break
                }
                // 先取上一级再回收当前节点，避免使用已回收节点
                val grandparent = parent.parent
                parent.recycle()
                parent = grandparent
            }
            parent?.recycle()
        }

        targetNode.recycle()

        // 策略 3: ACTION_LONG_CLICK 失败，回退到在节点中心坐标执行手势长按
        if (!result) {
            Log.d(
                TAG, "LongPress: ACTION_LONG_CLICK failed on node and ancestors, " +
                    "falling back to gesture at (${nodeRect.centerX()}, ${nodeRect.centerY()})",
            )
            result = longPressCoordinate(
                service, nodeRect.centerX(), nodeRect.centerY(), durationMs,
            )
        }

        return result
    }

    companion object {
        private const val TAG = "HabitatLongPress"
        private const val DEFAULT_DURATION_MS = 800L
        private const val MAX_DURATION_MS = 60_000L
        private const val MAX_ANCESTOR_DEPTH = 50
    }
}
