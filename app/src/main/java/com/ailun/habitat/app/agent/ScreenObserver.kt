package com.ailun.habitat.app.agent

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.ailun.habitat.app.bridge.AppAccessibilityProvider

/** 读当前屏幕。微信树为空时改走伪装服务；仍然为空才 OCR。 */
object ScreenObserver {
    const val WECHAT = "com.tencent.mm"
    const val PASSWORD_PLACEHOLDER = "（密码框）"
    private const val SELF = "com.ailun.habitat"
    private const val MAX_ELEMENTS = 40
    internal const val RANK_ACTIVE = 0
    internal const val RANK_APP = 1
    internal const val RANK_IME = 2
    internal const val RANK_SYSTEM = 3

    fun launcherApps(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .map { AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.packageName }
    }

    suspend fun observe(context: Context): ScreenObservation {
        val (width, height) = screenSize(context)
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

    /**
     * 整块屏幕的像素尺寸。无障碍坐标和截图都是整屏坐标；displayMetrics 在部分机型上
     * 不含导航栏，会让 OCR 坐标换算和滑动位置偏移。
     */
    private fun screenSize(context: Context): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wm = context.getSystemService(WindowManager::class.java)
            val bounds = wm?.maximumWindowMetrics?.bounds
            if (bounds != null && bounds.width() > 0 && bounds.height() > 0) return bounds.width() to bounds.height()
        }
        val dm = context.resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }

    internal fun walk(service: AccessibilityService, width: Int, height: Int, source: String): ScreenObservation {
        val raw = mutableListOf<RawNode>()
        val windows = runCatching { service.windows }.getOrNull().orEmpty()
        if (windows.isEmpty()) {
            val root = runCatching { service.rootInActiveWindow }.getOrNull()
            if (root != null) {
                try {
                    collect(root, raw, 0, intArrayOf(0), RANK_ACTIVE)
                } finally {
                    runCatching { root.recycle() }
                }
            }
        } else {
            for (window in windows) {
                val root = window.root ?: continue
                try {
                    collect(root, raw, 0, intArrayOf(0), rankOf(window))
                } finally {
                    runCatching { root.recycle() }
                }
            }
        }
        return select(raw, width, height, source)
    }

    /**
     * 窗口优先级：当前活动窗口 > 其它应用窗口 > 输入法 > 状态栏/导航栏等系统窗口。
     * 不分优先级时，按 y 排序截前 40 个会先装满状态栏图标，真正的页面内容被截掉，前台包也被认成 systemui。
     */
    private fun rankOf(window: AccessibilityWindowInfo): Int = when {
        window.isActive -> RANK_ACTIVE
        window.type == AccessibilityWindowInfo.TYPE_APPLICATION -> RANK_APP
        window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD -> RANK_IME
        else -> RANK_SYSTEM
    }

    internal fun select(raw: List<RawNode>, width: Int, height: Int, source: String): ScreenObservation {
        val foreign = raw.any { it.pkg.isNotEmpty() && it.pkg != SELF }
        val kept = raw.filter { !foreign || it.pkg != SELF }
            .sortedWith(compareBy({ it.rank }, { it.y }, { it.x }))
            .distinctBy { "${it.text}|${it.x / 16}|${it.y / 16}" }
            .take(MAX_ELEMENTS)
            .sortedWith(compareBy({ it.y }, { it.x }))
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
                password = node.password,
            )
        }
        val pkg = kept.filter { it.pkg.isNotEmpty() && it.pkg != SELF }.minByOrNull { it.rank }?.pkg
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

    private fun collect(node: AccessibilityNodeInfo, out: MutableList<RawNode>, depth: Int, visited: IntArray, rank: Int) {
        if (depth > 25 || visited[0] > 400) return
        visited[0]++
        if (node.isVisibleToUser) {
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            // 密码框的 text 在部分 ROM 上是明文，不能进入发给决策器的屏幕文字
            val label = if (node.isPassword) PASSWORD_PLACEHOLDER else text.ifEmpty { desc }
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
                            password = node.isPassword,
                            rank = rank,
                        ),
                    )
                }
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                collect(child, out, depth + 1, visited, rank)
            } finally {
                runCatching { child.recycle() }
            }
        }
    }

    internal data class RawNode(
        val text: String,
        val x: Int,
        val y: Int,
        val clickable: Boolean,
        val editable: Boolean,
        val focused: Boolean,
        val viewId: String,
        val pkg: String,
        val password: Boolean,
        val rank: Int = RANK_ACTIVE,
    )
}
