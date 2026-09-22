package com.ailun.habitat.app.agent

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.ailun.habitat.app.bridge.AppAccessibilityProvider

/** 读当前屏幕。微信树为空时改走伪装服务；仍然为空才 OCR。 */
object ScreenObserver {
    const val WECHAT = "com.tencent.mm"

    fun launcherApps(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .map { AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.packageName }
    }

    suspend fun observe(context: Context): ScreenObservation {
        val dm = context.resources.displayMetrics
        val width = dm.widthPixels
        val height = dm.heightPixels
        val primary = AppAccessibilityProvider.getService()
        val reader = AppAccessibilityProvider.getScreenReader()
        val foreground = AppAccessibilityProvider.foregroundPackage.orEmpty()
        val primaryObs = primary?.let { walk(it, width, height, "a11y") }
        val readerObs = if (reader != null && reader !== primary) walk(reader, width, height, "wechat-reader") else null
        val wechat = foreground == WECHAT ||
            primaryObs?.packageName == WECHAT ||
            readerObs?.packageName == WECHAT
        val chosen = when {
            wechat && textLen(readerObs) > textLen(primaryObs) -> readerObs
            textLen(primaryObs) >= 8 -> primaryObs
            textLen(readerObs) > textLen(primaryObs) -> readerObs
            else -> primaryObs ?: readerObs
        } ?: ScreenObservation.empty(width, height).copy(packageName = foreground)
        if (!wechat || chosen.visibleText.length >= 8) return chosen.copy(packageName = chosen.packageName.ifEmpty { foreground })
        val host = reader ?: primary ?: return chosen
        val ocr = WeChatOcr.recognize(host, width, height)
        return ocr ?: chosen.copy(packageName = WECHAT)
    }

    private fun textLen(obs: ScreenObservation?) = obs?.visibleText?.length ?: 0

    internal fun walk(service: AccessibilityService, width: Int, height: Int, source: String): ScreenObservation {
        val raw = mutableListOf<RawNode>()
        val windows = runCatching { service.windows }.getOrNull().orEmpty()
        if (windows.isEmpty()) {
            val root = runCatching { service.rootInActiveWindow }.getOrNull()
            if (root != null) {
                try {
                    collect(root, raw, 0, intArrayOf(0))
                } finally {
                    runCatching { root.recycle() }
                }
            }
        } else {
            for (window in windows) {
                val root = window.root ?: continue
                try {
                    collect(root, raw, 0, intArrayOf(0))
                } finally {
                    runCatching { root.recycle() }
                }
            }
        }
        val foreign = raw.any { it.pkg.isNotEmpty() && it.pkg != "com.ailun.habitat" }
        val kept = raw.filter { !foreign || it.pkg != "com.ailun.habitat" }
            .sortedWith(compareBy({ it.y }, { it.x }))
            .distinctBy { "${it.text}|${it.x / 16}|${it.y / 16}" }
            .take(40)
        val elements = kept.mapIndexed { i, node ->
            ScreenElement(
                index = (i + 1).toString(),
                text = node.text,
                centerX = node.x,
                centerY = node.y,
                clickable = node.clickable || node.text.isNotEmpty(),
                editable = node.editable,
                focused = node.focused,
                viewId = node.viewId,
            )
        }
        val pkg = kept.firstOrNull { it.pkg.isNotEmpty() && it.pkg != "com.ailun.habitat" }?.pkg
            ?: kept.firstOrNull()?.pkg
            ?: ""
        val focused = elements.firstOrNull { it.focused && it.editable }?.index
        return ScreenObservation(
            packageName = pkg,
            screenWidth = width,
            screenHeight = height,
            elements = elements,
            visibleText = elements.joinToString("\n") { it.text }.take(4000),
            source = source,
            focusedIndex = focused,
        )
    }

    private fun collect(node: AccessibilityNodeInfo, out: MutableList<RawNode>, depth: Int, visited: IntArray) {
        if (depth > 25 || visited[0] > 400) return
        visited[0]++
        if (node.isVisibleToUser) {
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val label = text.ifEmpty { desc }
            if (label.isNotEmpty()) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (bounds.width() > 0 && bounds.height() > 0) {
                    out.add(
                        RawNode(
                            text = label.take(80),
                            x = bounds.centerX(),
                            y = bounds.centerY(),
                            clickable = node.isClickable,
                            editable = node.isEditable,
                            focused = node.isFocused,
                            viewId = node.viewIdResourceName ?: "",
                            pkg = node.packageName?.toString().orEmpty(),
                        ),
                    )
                }
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                collect(child, out, depth + 1, visited)
            } finally {
                runCatching { child.recycle() }
            }
        }
    }

    private data class RawNode(
        val text: String,
        val x: Int,
        val y: Int,
        val clickable: Boolean,
        val editable: Boolean,
        val focused: Boolean,
        val viewId: String,
        val pkg: String,
    )
}
