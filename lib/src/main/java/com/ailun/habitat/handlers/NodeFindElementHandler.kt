package com.ailun.habitat.handlers

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode
import com.ailun.habitat.api.IAccessibilityProvider
import com.ailun.habitat.api.IShellExecutor
import kotlinx.coroutines.delay

/**
 * [ACTION_FIND_ELEMENT] : 在当前界面中通过无障碍服务查找符合条件的 UI 元素。
 *
 * params:
 * - `selector` (必需): 用于匹配元素的文本/ID/类名/内容描述
 * - `search_mode` (可选, 默认 "text"): 搜索模式
 *   - "text": 按节点文本 (text) 匹配
 *   - "id": 按 viewId 匹配
 *   - "class": 按类名 (className) 匹配
 *   - "content_desc": 按内容描述 (contentDescription) 匹配
 * - `region` (可选): 限定匹配元素必须在该屏幕区域内（解决同文本多处出现点错）：
 *   - 语义: "top"/"bottom"/"left"/"right"/"center"（对应屏幕半区/中心区）
 *   - 归一化矩形: "x1,y1,x2,y2"（0-1 相对屏幕，如 "0,0.5,1,1" = 下半屏）
 * - `output_var` (可选): 变量名，用于存储元素详细信息 map
 * - `timeout_ms` (可选, 默认 3000): 查找超时时间（毫秒），期间会轮询重试
 *
 * 输出变量:
 * - `element_found` (Boolean): 是否找到匹配元素
 * - `element_bounds` (String): 元素边界 "left,top,right,bottom"
 * - `element_text` (String): 元素文本内容
 * - `element_clickable` (Boolean): 元素是否可点击
 * - 若指定了 output_var，还会在该变量名下存储包含 found/bounds/text/clickable/contentDescription/className/packageName 的 map
 */
class NodeFindElementHandler(
    private val provider: IAccessibilityProvider?,
    private val shellExecutor: IShellExecutor?,
) : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        val rawSelector = node.params?.get("selector")?.toString()?.trim().orEmpty()
        if (rawSelector.isEmpty()) {
            Log.w(TAG, "FindElement: 'selector' parameter is empty")
            context.variables["element_found"] = false
            return node.next
        }

        val selector = context.interpolate(rawSelector)
        val searchMode = node.params?.get("search_mode")?.toString()?.trim()?.lowercase()
            ?: SEARCH_MODE_TEXT
        val regionSpec = context.interpolate(node.params?.get("region")?.toString()?.trim() ?: "")
        val outputVar = node.params?.get("output_var")?.toString()?.trim()?.ifEmpty { null }
        val timeoutMs = (node.params?.get("timeout_ms") as? Number)?.toLong() ?: DEFAULT_TIMEOUT_MS

        val service = provider?.getService() ?: run {
            Log.e(TAG, "FindElement: Accessibility service not available")
            context.variables["element_found"] = false
            return node.next
        }

        Log.d(TAG, "FindElement: selector='$selector', mode=$searchMode, region='$regionSpec', timeout=${timeoutMs}ms")

        val screenRect = getScreenRect(service)
        val region = resolveRegion(regionSpec, screenRect)
        if (regionSpec.isNotBlank() && region == null) {
            context.variables["element_found"] = false
            context.variables["element_error"] = "无法解析 region '$regionSpec'"
            context.log("FindElement: bad region '$regionSpec'")
            return node.next
        }

        val foundNode = findElementWithTimeout(service, selector, searchMode, region, timeoutMs)

        if (foundNode != null) {
            try {
                val rect = Rect()
                foundNode.getBoundsInScreen(rect)
                val bounds = "${rect.left},${rect.top},${rect.right},${rect.bottom}"
                val text = foundNode.text?.toString() ?: ""
                val clickable = foundNode.isClickable
                val contentDesc = foundNode.contentDescription?.toString() ?: ""
                val className = foundNode.className?.toString() ?: ""
                val packageName = foundNode.packageName?.toString() ?: ""

                context.variables["element_found"] = true
                context.variables["element_bounds"] = bounds
                context.variables["element_text"] = text
                context.variables["element_clickable"] = clickable

                if (outputVar != null) {
                    context.putVariable(
                        outputVar,
                        mapOf(
                            "found" to true,
                            "bounds" to bounds,
                            "text" to text,
                            "clickable" to clickable,
                            "contentDescription" to contentDesc,
                            "className" to className,
                            "packageName" to packageName,
                        ),
                    )
                }

                context.log("FindElement: element found, bounds=$bounds, clickable=$clickable")
            } finally {
                foundNode.recycle()
            }
        } else {
            context.variables["element_found"] = false
            context.variables["element_bounds"] = ""
            context.variables["element_text"] = ""
            context.variables["element_clickable"] = false

            if (outputVar != null) {
                context.putVariable(outputVar, mapOf("found" to false))
            }

            context.log("FindElement: no element found for selector '$selector'${if (region != null) " in region $region" else ""}")
        }

        return node.next
    }

    /** 当前屏幕矩形（用于解析 region）。取活动窗口 bounds，失败则默认全屏。 */
    private fun getScreenRect(service: AccessibilityService): Rect {
        return try {
            val r = Rect()
            service.rootInActiveWindow?.getBoundsInScreen(r)
            if (r.isEmpty) Rect(0, 0, 1080, 2400) else r
        } catch (_: Exception) {
            Rect(0, 0, 1080, 2400)
        }
    }

    /**
     * 解析 region 描述为屏幕矩形。
     * - "top"/"bottom"/"left"/"right"/"center"：对应屏幕半区/中心区
     * - "x1,y1,x2,y2"：归一化 0-1 相对屏幕
     * - 空/无法解析：返回 null（全屏）
     */
    private fun resolveRegion(spec: String, screen: Rect): Rect? {
        if (spec.isBlank()) return null
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

    /** 判断节点中心是否落在区域内。 */
    private fun inRegion(node: AccessibilityNodeInfo, region: Rect?): Boolean {
        if (region == null) return true
        val r = Rect()
        node.getBoundsInScreen(r)
        val cx = (r.left + r.right) / 2
        val cy = (r.top + r.bottom) / 2
        return region.contains(cx, cy)
    }

    private suspend fun findElementWithTimeout(
        service: AccessibilityService,
        selector: String,
        searchMode: String,
        region: Rect?,
        timeoutMs: Long,
    ): AccessibilityNodeInfo? {
        val pollInterval = 100L
        val deadline = System.currentTimeMillis() + timeoutMs

        while (System.currentTimeMillis() < deadline) {
            val result = searchElement(service, selector, searchMode, region)
            if (result != null) return result
            delay(pollInterval)
        }

        return searchElement(service, selector, searchMode, region)
    }

    private fun searchElement(
        service: AccessibilityService,
        selector: String,
        searchMode: String,
        region: Rect?,
    ): AccessibilityNodeInfo? {
        val roots = mutableListOf<AccessibilityNodeInfo>()

        try {
            service.rootInActiveWindow?.let { roots.add(it) }
            service.windows.forEach { win ->
                win.root?.let { roots.add(it) }
            }

            for (root in roots) {
                val found = findNodeByMode(root, selector, searchMode, region)
                if (found != null) return found
            }

            return null
        } finally {
            roots.forEach { root ->
                try {
                    root.recycle()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun findNodeByMode(
        root: AccessibilityNodeInfo,
        selector: String,
        searchMode: String,
        region: Rect?,
    ): AccessibilityNodeInfo? {
        val primaryResult = searchBySingleMode(root, selector, searchMode, region)
        if (primaryResult != null) return primaryResult

        val fallbackModes = when (searchMode) {
            SEARCH_MODE_ID -> listOf(SEARCH_MODE_TEXT, SEARCH_MODE_CONTENT_DESC)
            SEARCH_MODE_CLASS -> listOf(SEARCH_MODE_TEXT, SEARCH_MODE_ID)
            SEARCH_MODE_CONTENT_DESC -> listOf(SEARCH_MODE_TEXT, SEARCH_MODE_ID)
            else -> listOf(SEARCH_MODE_ID, SEARCH_MODE_CONTENT_DESC)
        }

        for (fallbackMode in fallbackModes) {
            val result = searchBySingleMode(root, selector, fallbackMode, region)
            if (result != null) return result
        }

        return null
    }

    private fun searchBySingleMode(
        root: AccessibilityNodeInfo,
        selector: String,
        mode: String,
        region: Rect?,
    ): AccessibilityNodeInfo? {
        when (mode) {
            SEARCH_MODE_ID -> {
                val results = root.findAccessibilityNodeInfosByViewId(selector)
                return pickBestNode(results, region)
            }
            SEARCH_MODE_TEXT -> {
                val results = root.findAccessibilityNodeInfosByText(selector)
                return pickBestNode(results, region)
            }
            SEARCH_MODE_CLASS -> {
                val results = mutableListOf<AccessibilityNodeInfo>()
                collectNodesByClassName(root, selector.lowercase(), results)
                return if (results.isNotEmpty()) pickBestNode(results, region) else null
            }
            SEARCH_MODE_CONTENT_DESC -> {
                return findNodeByContentDescription(root, selector.lowercase(), region)
            }
            else -> {
                Log.w(TAG, "FindElement: unknown search mode '$mode'")
                return null
            }
        }
    }

    /**
     * 从 SDK 返回的节点列表中选出最佳节点：**优先"在区域内 + 可见"**，其次"在区域内"。
     * 未指定 region 时退回到旧逻辑（优先可见）。回收其余节点。
     */
    private fun pickBestNode(results: List<AccessibilityNodeInfo>?, region: Rect?): AccessibilityNodeInfo? {
        if (results == null || results.isEmpty()) return null
        val inRegion = results.filter { inRegion(it, region) }
        if (inRegion.isEmpty()) return null
        val best = inRegion.find { it.isVisibleToUser } ?: inRegion.first()
        results.forEach { if (it !== best) it.recycle() }
        return best
    }

    private fun collectNodesByClassName(
        node: AccessibilityNodeInfo,
        classNameLower: String,
        results: MutableList<AccessibilityNodeInfo>,
    ) {
        val nodeClass = node.className?.toString()?.lowercase() ?: ""
        if (nodeClass.contains(classNameLower)) {
            results.add(node)
            return
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                collectNodesByClassName(child, classNameLower, results)
            } catch (_: Exception) {
                try {
                    child.recycle()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun findNodeByContentDescription(
        node: AccessibilityNodeInfo,
        descriptionLower: String,
        region: Rect?,
    ): AccessibilityNodeInfo? {
        val contentDesc = node.contentDescription?.toString()?.lowercase() ?: ""
        if (contentDesc.contains(descriptionLower) && node.isVisibleToUser && inRegion(node, region)) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                val found = findNodeByContentDescription(child, descriptionLower, region)
                if (found != null) return found
            } catch (_: Exception) {
                try {
                    child.recycle()
                } catch (_: Exception) {
                }
            }
        }
        return null
    }

    companion object {
        private const val TAG = "HabitatFindElement"

        const val SEARCH_MODE_TEXT = "text"
        const val SEARCH_MODE_ID = "id"
        const val SEARCH_MODE_CLASS = "class"
        const val SEARCH_MODE_CONTENT_DESC = "content_desc"

        private const val DEFAULT_TIMEOUT_MS = 3000L
    }
}
