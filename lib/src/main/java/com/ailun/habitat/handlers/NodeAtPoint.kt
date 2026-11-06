package com.ailun.habitat.handlers

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * 坐标 → 无障碍节点，给 [NodeClickHandler] / [NodeLongPressHandler] 的 `mode: "node"` 用：不派发手势，
 * 对包含该点、支持这个动作的最小节点直接执行 ACTION_CLICK / ACTION_LONG_CLICK。
 *
 * 有的应用不认注入的手势：文件管理器（DocumentsUI）里手势点列表里的文件夹、文件不打开（2026-11-06 在 AndroidWorld 的
 * 模拟器上同一行对比：手势点不开，adb input tap 和节点 ACTION_CLICK 都点得开）。节点动作走的是应用给无障碍用户的那条路径。
 */
internal object NodeAtPoint {

    /** 找不到节点、点不在最上层应用窗口里、或者应用不处理这个动作，都返回 false。 */
    fun perform(service: AccessibilityService, x: Int, y: Int, action: Int, selfPackage: String): Boolean {
        val root = topAppRootAt(service, x, y) ?: return false
        val found = mutableListOf<AccessibilityNodeInfo>()
        try {
            collect(root, x, y, action, selfPackage, found, 0)
            val r = Rect()
            val best = found.minByOrNull { it.getBoundsInScreen(r); r.width().toLong() * r.height() } ?: return false
            return best.performAction(action)
        } finally {
            @Suppress("DEPRECATION")
            (found + root).forEach { runCatching { it.recycle() } }
        }
    }

    /**
     * 最上层的应用窗口的根节点，而且 (x,y) 在它里面、不在输入法窗口里；不满足返回 null（不做）。
     * 弹窗外面、键盘下面的节点用户点不到，节点动作却点得到。拿不到窗口列表时用活动窗口。
     */
    private fun topAppRootAt(service: AccessibilityService, x: Int, y: Int): AccessibilityNodeInfo? = try {
        val windows = service.windows
        val r = Rect()
        fun AccessibilityWindowInfo.hit(): Boolean {
            getBoundsInScreen(r)
            return r.contains(x, y)
        }
        val top = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.maxByOrNull { it.layer }
        when {
            top == null -> service.rootInActiveWindow
            windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD && it.hit() } -> null
            top.hit() -> top.root
            else -> null
        }
    } catch (_: Exception) {
        service.rootInActiveWindow
    }

    /** 节点自己声明可做（isClickable / isLongClickable），或者动作表里有它（应用用 AccessibilityDelegate 加的动作不一定反映在这两个标志上）。 */
    private fun supports(node: AccessibilityNodeInfo, action: Int): Boolean =
        when (action) {
            AccessibilityNodeInfo.ACTION_CLICK -> node.isClickable
            AccessibilityNodeInfo.ACTION_LONG_CLICK -> node.isLongClickable
            else -> false
        } || node.actionList.any { it.id == action }

    private fun collect(
        node: AccessibilityNodeInfo, x: Int, y: Int, action: Int, selfPackage: String,
        out: MutableList<AccessibilityNodeInfo>, depth: Int,
    ) {
        if (depth > 30 || out.size >= 40 || !node.isVisibleToUser) return
        val r = Rect()
        node.getBoundsInScreen(r)
        if (!r.contains(x, y)) return
        if (node.isEnabled && node.packageName?.toString() != selfPackage && supports(node, action)) {
            out.add(AccessibilityNodeInfo.obtain(node))
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                collect(child, x, y, action, selfPackage, out, depth + 1)
            } finally {
                @Suppress("DEPRECATION")
                runCatching { child.recycle() }
            }
        }
    }
}
