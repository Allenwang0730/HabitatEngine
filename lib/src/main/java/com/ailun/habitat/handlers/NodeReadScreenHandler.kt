package com.ailun.habitat.handlers

import android.util.Log
import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.ailun.habitat.api.IAccessibilityProvider

/**
 * [ACTION_READ_SCREEN]：在当前界面查找关键字或抓取全屏文本。
 */
class NodeReadScreenHandler(
    private val a11yProvider: IAccessibilityProvider? = null
) : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        val rawKeyword = node.params?.get("keyword")?.toString().orEmpty()
        val keyword = context.interpolate(rawKeyword)
        val outputVar = node.params?.get("output_var")?.toString().orEmpty()

        val service = a11yProvider?.getService()
            ?: run {
                context.log("ReadScreen Error: Accessibility not running")
                context.putVariable("screen_data", false)
                return node.next
            }

        val roots = mutableListOf<AccessibilityNodeInfo>()
        service.windows.forEach { window ->
            if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                window.root?.let { r ->
                    val pkg = r.packageName?.toString()
                    if (pkg != null) {
                        roots.add(r)
                    } else {
                        r.recycle()
                    }
                }
            }
        }

        if (roots.isEmpty()) {
            service.rootInActiveWindow?.let { r ->
                val pkg = r.packageName?.toString()
                if (pkg != null) {
                    roots.add(r)
                } else {
                    r.recycle()
                }
            }
        }

        if (roots.isEmpty()) {
            context.putVariable("screen_data", false)
            if (outputVar.isNotEmpty()) context.putVariable(outputVar, "")
            return node.next
        }

        var found = false
        val allTexts = mutableListOf<String>()
        try {
            val collect = outputVar.isNotEmpty()
            for (root in roots) {
                // 单次遍历同时收集文本与匹配关键字；需要输出全文时不能提前结束
                if (scan(root, keyword, if (collect) allTexts else null)) found = true
                if (found && !collect) break
            }
            context.putVariable("screen_data", found)
            if (found) context.log("ReadScreen: Target found in external app.")

            if (outputVar.isNotEmpty()) {
                context.putVariable(outputVar, allTexts.joinToString("\n"))
            }
        } finally {
            roots.forEach { it.recycle() }
        }
        return node.next
    }

    /**
     * 深度优先遍历可见节点：[texts] 非空时收集全部文本；返回是否包含 [keyword]（keyword 为空恒 false）。
     * 仅匹配关键字（texts == null）时命中即停止。
     */
    private fun scan(node: AccessibilityNodeInfo, keyword: String, texts: MutableList<String>?): Boolean {
        if (!node.isVisibleToUser) return false
        val pkg = node.packageName?.toString() ?: ""
        if (pkg.isEmpty()) return false
        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        if (texts != null) {
            if (!text.isNullOrEmpty()) texts.add(text)
            if (!desc.isNullOrEmpty() && desc != text) texts.add(desc)
        }
        var found = keyword.isNotEmpty() &&
            (text?.contains(keyword, ignoreCase = true) == true || desc?.contains(keyword, ignoreCase = true) == true)
        if (found && texts == null) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                if (scan(child, keyword, texts)) {
                    found = true
                    if (texts == null) return true
                }
            } finally {
                @Suppress("DEPRECATION")
                child.recycle()
            }
        }
        return found
    }
}
