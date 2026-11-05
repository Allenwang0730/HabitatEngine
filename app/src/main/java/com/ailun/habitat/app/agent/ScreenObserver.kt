package com.ailun.habitat.app.agent

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.ailun.habitat.app.bridge.AppAccessibilityProvider
import kotlinx.coroutines.delay

/** 读当前屏幕。微信树为空时改走伪装服务；树文字不足才 OCR 兜底。 */
object ScreenObserver {
    const val WECHAT = "com.tencent.mm"
    const val PASSWORD_PLACEHOLDER = "（密码框）"
    private const val TAG = "ScreenObserver"
    private const val SELF = "com.ailun.habitat"
    private const val MAX_ELEMENTS = 60
    private const val MIN_RANK_QUOTA = 6
    private const val MAX_SYSTEM_ELEMENTS = 6
    private const val PAGE_AREA_RATIO = 0.35
    /** 横排最高占屏高的比例：类别芯片行、标签栏都很扁；更高的横向容器多半是整屏翻页。 */
    private const val HSCROLL_MAX_HEIGHT = 0.2
    private const val MAX_HSCROLL_ROWS = 3

    /** 无障碍树可用文字低于这个长度就考虑 OCR 兜底（微信仍优先走伪装服务）。 */
    private const val OCR_MIN_TEXT = 12
    private const val OCR_SETTLE_MS = 600L

    /** OCR 参与程度：fallback=只在树几乎没字时兜底（旧行为）；hybrid=图片/画布多或字少时补读图片文字；always=每轮都补。 */
    const val OCR_FALLBACK = "fallback"
    const val OCR_HYBRID = "hybrid"
    const val OCR_ALWAYS = "always"
    private const val PREFS = "agent_loop_prefs"

    /** hybrid：图片类控件占屏 ≥ 这个比例，或树里文字少于 [HYBRID_MIN_TEXT] 字，就补一次 OCR。 */
    private const val HYBRID_IMAGE_FRACTION = 0.15
    private const val HYBRID_MIN_TEXT = 40
    /** 小于屏幕面积 2% 的图片（图标）不算。 */
    private const val MIN_IMAGE_AREA = 0.02
    private const val MAX_OCR_EXTRA = 20
    /** 屏幕顶部这一条是状态栏（时间、信号），OCR 读到的不进观测。 */
    private const val STATUS_BAR_FRACTION = 0.045
    /** 开发者选项「指针位置」叠加层那一行读数，例如「P:0/1 dX:0.0 dY:0.0 Xv:0.0 Yv:0.0 Prs:1.0」。 */
    private val POINTER_OVERLAY = Regex("""\b(P:\s*\d+\s*/\s*\d+|d[XY]:\s*-?\d|[XY]v:\s*-?\d|Prs:\s*\d|Size:\s*\d)""")
    /** 同一屏（包名 + 树文字不变）几秒内复用上次 OCR 结果：一步里会读好几次屏，不必每次都截图识别。 */
    private const val OCR_CACHE_MS = 4_000L
    private val IMAGE_CLASS_HINTS = listOf("ImageView", "WebView", "SurfaceView", "TextureView", "VideoView", "Image")

    fun getOcrMode(context: Context): String =
        context.getSharedPreferences(PREFS, 0).getString("ocr_mode", OCR_FALLBACK) ?: OCR_FALLBACK

    fun setOcrMode(context: Context, mode: String) {
        if (mode in setOf(OCR_FALLBACK, OCR_HYBRID, OCR_ALWAYS)) context.getSharedPreferences(PREFS, 0).edit().putString("ocr_mode", mode).apply()
    }

    private data class OcrCache(val key: String, val at: Long, val blocks: List<OcrText>)
    @Volatile private var ocrCache: OcrCache? = null

    /** 与 Android 无关的 OCR 结果，便于单测合并逻辑。 */
    data class OcrText(val text: String, val bounds: ElementBounds)
    private const val DESC_DEPTH = 3
    private const val DESC_MAX_PARTS = 3
    private const val DESC_MAX_LEN = 80

    internal const val RANK_ACTIVE = 0
    internal const val RANK_APP = 1
    internal const val RANK_IME = 2
    internal const val RANK_SYSTEM = 3

    /** 按钮/开关等"本身就是控件"的类名；列表行容器不会带这些类名。 */
    private val CONTROL_CLASS_HINTS = listOf(
        "Button", "ImageButton", "CheckBox", "RadioButton", "Switch", "ToggleButton",
        "CompoundButton", "Checkable", "Chip", "SeekBar", "ImageView", "ImageSpan",
    )

    fun launcherApps(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        // 不能用 MATCH_DEFAULT_ONLY：桌面入口 Activity 大多不声明 CATEGORY_DEFAULT，会被整批滤掉。
        // 真机实测只剩「设置」等少数几个，「打开时钟」之类的目标永远拿不到 OPEN_APP 候选。
        return pm.queryIntentActivities(intent, 0)
            .map { AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.packageName }
    }

    suspend fun observe(context: Context): ScreenObservation {
        val (width, height) = screenSize(context)
        val state = screenState(context)
        if (state.locked) {
            // 锁屏时读到的"屏幕"只有 systemui 和验证指纹页，任何据此做出的决策都必然失败
            Log.i(TAG, "屏幕已锁定（interactive=${state.interactive}），不读树也不决策")
            return ScreenObservation.locked(width, height)
        }
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
        val named = chosen.copy(packageName = chosen.packageName.ifEmpty { foreground })
        // 微信：树为空说明伪装服务也没读到，OCR 兜底
        if (wechat && textLen(chosen) < 8) {
            val host = reader ?: primary ?: return named
            val ocr = WeChatOcr.recognize(host, width, height, packageName = WECHAT)
            return ocr ?: named
        }
        if (wechat) return named
        // 其它 App：树可用文字太少也要能看见画面（亮屏且未锁屏已由上面保证）
        if (needsOcr(named)) {
            // 文字少多半是页面正在切换：等一下再读一次树，树读到了就不用 OCR。
            // 真机实测：转场中途 OCR 出的坐标是猜的，模型点下去进了错误的 App（vivo 使用手册）。
            delay(OCR_SETTLE_MS)
            val again = primary?.let { walk(it, width, height, "a11y") }
                ?.let { it.copy(packageName = it.packageName.ifEmpty { foreground }) }
            if (again != null && !needsOcr(again)) return again
            val host = primary ?: reader ?: return again ?: named
            Log.i(TAG, "无障碍树可用文字仅 ${textLen(again ?: named)} 字符且无可点控件，走 OCR 兜底")
            val ocr = WeChatOcr.recognize(host, width, height, packageName = (again ?: named).packageName)
            return ocr ?: again ?: named
        }
        val mode = getOcrMode(context)
        if (mode == OCR_FALLBACK) return named
        val host = primary ?: reader ?: return named
        return hybrid(host, named, mode, width, height)
    }

    /**
     * 混合读屏：树是好的，但图片 / WebView / 画布占了大块屏幕（或树里字很少）时，把 OCR 读到、树里没有的文字补进来。
     * 补进来的元素标 [ScreenElement.ocr]，不参与"屏幕变没变"的判断（[LoopPolicy.sameScreen]），OCR 抖动不会骗过判重。
     */
    private suspend fun hybrid(host: AccessibilityService, tree: ScreenObservation, mode: String, width: Int, height: Int): ScreenObservation {
        val wanted = mode == OCR_ALWAYS || tree.imageFraction >= HYBRID_IMAGE_FRACTION || textLen(tree) < HYBRID_MIN_TEXT
        if (!wanted) return tree
        val key = "${tree.packageName}|${tree.visibleText.hashCode()}|${tree.elements.size}"
        val now = System.currentTimeMillis()
        val cached = ocrCache?.takeIf { it.key == key && now - it.at < OCR_CACHE_MS }
        val blocks = cached?.blocks ?: WeChatOcr.readScreenThrottled(host, width, height)
            ?.map { OcrText(it.text, ElementBounds(it.box.left, it.box.top, it.box.right, it.box.bottom)) }
            ?.also { ocrCache = OcrCache(key, now, it) }
            ?: return tree
        return mergeOcr(tree, blocks)
    }

    /**
     * 把 OCR 块并进树的观测（纯逻辑，单测覆盖）：
     * - 树里已经有的文字不重复加（互相包含即算有）；
     * - 状态栏、输入法键盘、开发者选项「指针位置」那一行读数（P:0/1 dX:… Xv:…）是噪声，丢掉；
     * - 落在可点控件里的才可点（点图片卡片），其余只给模型"看"；
     * - 最多补 [MAX_OCR_EXTRA] 块，序号接在树元素后面。
     */
    internal fun mergeOcr(tree: ScreenObservation, blocks: List<OcrText>): ScreenObservation {
        val h = tree.screenHeight.coerceAtLeast(1)
        val ime = tree.windows.filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }.mapNotNull { it.bounds }
        val treeTexts = tree.elements.map { fold(it.text) }.filter { it.isNotEmpty() }
        val extra = blocks.asSequence()
            .filter { it.bounds.centerY > h * STATUS_BAR_FRACTION }
            .filter { b -> ime.none { it.contains(b.bounds) } }
            .filterNot { POINTER_OVERLAY.containsMatchIn(it.text) }
            .filter { b ->
                val t = fold(b.text)
                t.length >= 2 && treeTexts.none { it.contains(t) || (it.length >= 4 && t.contains(it)) }
            }
            .take(MAX_OCR_EXTRA)
            .toList()
        if (extra.isEmpty()) return tree
        val start = tree.elements.size
        val added = extra.mapIndexed { i, b ->
            val inClickable = tree.elements.any { el ->
                el.clickable && !el.system && el.bounds?.let { bb ->
                    b.bounds.centerX in bb.left..bb.right && b.bounds.centerY in bb.top..bb.bottom
                } == true
            }
            ScreenElement(
                index = (start + i + 1).toString(),
                text = b.text.take(80),
                centerX = b.bounds.centerX,
                centerY = b.bounds.centerY,
                clickable = inClickable,
                editable = false,
                focused = false,
                bounds = b.bounds,
                ocr = true,
            )
        }
        return tree.copy(
            elements = tree.elements + added,
            visibleText = tree.visibleText + "\n" + added.joinToString("\n") { it.text },
            source = tree.source + "+ocr",
        )
    }

    private fun fold(text: String): String = text.lowercase().filterNot { it.isWhitespace() }

    /** 树里文字太少、且没有任何可点控件，才值得 OCR；有可点控件说明树是好的，只是这页字少。 */
    internal fun needsOcr(obs: ScreenObservation): Boolean =
        textLen(obs) < OCR_MIN_TEXT && obs.elements.none { it.clickable && it.text.isNotBlank() }

    /** 只统计非容器聚合来的文字，避免容器把后代文字复制一份导致"看起来够了"。 */
    private fun textLen(obs: ScreenObservation?): Int = obs?.elements
        ?.filterNot { it.aggregated }
        ?.sumOf { it.text.length }
        ?: 0

    /**
     * 整块屏幕的像素尺寸。无障碍坐标和截图都是整屏坐标；displayMetrics 在部分机型上
     * 不含导航栏，会让 OCR 坐标换算和滑动位置偏移。
     */
    internal fun screenSize(context: Context): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wm = context.getSystemService(WindowManager::class.java)
            val bounds = wm?.maximumWindowMetrics?.bounds
            if (bounds != null && bounds.width() > 0 && bounds.height() > 0) return bounds.width() to bounds.height()
        }
        val dm = context.resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }

    /** 亮屏 / 锁屏判定。抽成可测函数，锁屏判定不依赖无障碍树。 */
    internal fun screenState(context: Context, pm: PowerManager? = null, km: KeyguardManager? = null): ScreenState {
        val power = pm ?: context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val keyguard = km ?: context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return ScreenState(
            interactive = power?.isInteractive ?: true,
            locked = keyguard?.isKeyguardLocked ?: false,
        )
    }

    internal data class ScreenState(val interactive: Boolean, val locked: Boolean)

    internal fun walk(service: AccessibilityService, width: Int, height: Int, source: String): ScreenObservation {
        val raw = mutableListOf<RawNode>()
        val infos = mutableListOf<WindowInfo>()
        val images = mutableListOf<ElementBounds>()
        val hScrolls = mutableListOf<ScrollRow>()
        val windows = runCatching { service.windows }.getOrNull().orEmpty()
        if (windows.isEmpty()) {
            val root = runCatching { service.rootInActiveWindow }.getOrNull()
            if (root != null) {
                try {
                    val before = raw.size
                    collect(
                        root, raw, 0, intArrayOf(0), RANK_ACTIVE, ancestorClickable = false, screenW = width, screenH = height,
                        images = images, hScrolls = hScrolls,
                    )
                    infos.add(windowInfo(root, RANK_ACTIVE, active = true, type = AccessibilityWindowInfo.TYPE_APPLICATION, nodes = raw.size - before))
                } finally {
                    runCatching { root.recycle() }
                }
            }
        } else {
            for (window in windows) {
                val root = window.root ?: continue
                val rank = rankOf(window)
                try {
                    val before = raw.size
                    val page = rank == RANK_ACTIVE || rank == RANK_APP
                    collect(
                        root, raw, 0, intArrayOf(0), rank, ancestorClickable = false, screenW = width, screenH = height,
                        images = if (page) images else null, hScrolls = if (page) hScrolls else null,
                    )
                    infos.add(windowInfo(root, rank, window.isActive, window.type, raw.size - before, window.isFocused))
                } finally {
                    runCatching { root.recycle() }
                }
            }
        }
        return select(raw, width, height, source).copy(
            windows = infos.filter { it.packageName != SELF },
            imageFraction = imageFraction(images, width, height),
            hScrolls = hScrolls.distinctBy { it.bounds }.take(MAX_HSCROLL_ROWS),
        )
    }

    /** 图片类控件（去掉重叠前）占屏幕面积的比例，封顶 1。 */
    internal fun imageFraction(images: List<ElementBounds>, width: Int, height: Int): Double {
        if (width <= 0 || height <= 0 || images.isEmpty()) return 0.0
        val area = images.sumOf { b ->
            val w = (minOf(b.right, width) - maxOf(b.left, 0)).coerceAtLeast(0)
            val h = (minOf(b.bottom, height) - maxOf(b.top, 0)).coerceAtLeast(0)
            w.toLong() * h
        }
        return (area.toDouble() / (width.toLong() * height)).coerceAtMost(1.0)
    }

    private fun windowInfo(
        root: AccessibilityNodeInfo,
        rank: Int,
        active: Boolean,
        type: Int,
        nodes: Int,
        focused: Boolean = false,
    ): WindowInfo = WindowInfo(
        packageName = root.packageName?.toString().orEmpty(),
        rank = rank,
        active = active,
        type = type,
        focused = focused,
        nodeCount = nodes,
        text = collectText(root).joinToString("\n").take(4000),
        bounds = Rect().also { root.getBoundsInScreen(it) }.takeIf { it.width() > 0 && it.height() > 0 }
            ?.let { ElementBounds(it.left, it.top, it.right, it.bottom) },
    )

    /** 窗口内的可见文字，用于 debug 快照对比"文字实际在哪个窗口"。 */
    private fun collectText(node: AccessibilityNodeInfo, out: MutableList<String> = mutableListOf()): List<String> {
        if (!node.isVisibleToUser) return out
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                collectText(child, out)
            } finally {
                runCatching { child.recycle() }
            }
        }
        return out
    }

    /**
     * 窗口优先级：当前活动窗口 > 其它应用窗口 > 输入法 > 状态栏/导航栏等系统窗口。
     * 不分优先级时，按 y 排序截前 N 个会先装满状态栏图标，真正的页面内容被截掉，前台包也被认成 systemui。
     */
    private fun rankOf(window: AccessibilityWindowInfo): Int = when {
        window.isActive -> RANK_ACTIVE
        window.type == AccessibilityWindowInfo.TYPE_APPLICATION -> RANK_APP
        window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD -> RANK_IME
        else -> RANK_SYSTEM
    }

    internal fun select(raw: List<RawNode>, width: Int, height: Int, source: String): ScreenObservation {
        val foreign = raw.any { it.pkg.isNotEmpty() && it.pkg != SELF }
        // 本 App 自己的节点一律排除（悬浮球是 com.ailun.habitat 的 overlay 窗口，点它就是点自己）；
        // 没有任何外部节点时不排除，否则本 App 前台页面会读成空屏。
        val kept = raw.filter { !foreign || it.pkg != SELF }
        // 非活动的系统窗口（状态栏、通知图标、灵动岛）设上限：真机上通知图标一条一个节点，
        // 页面节点少时会把剩余名额全部填满（实测 53 个元素里 41 个是"xx通知："），模型看到的全是噪声。
        // 只留可点的那几个；通知栏被拉下来时它是活动窗口（RANK_ACTIVE），不受此限。
        val systemKept = kept.filter { it.rank == RANK_SYSTEM }
            .filter { it.clickable || it.ancestorClickable }
            .sortedWith(compareBy({ it.y }, { it.x }))
            .take(MAX_SYSTEM_ELEMENTS)
            .toSet()
        val ordered = kept.filter { it.rank != RANK_SYSTEM || it in systemKept }
            .sortedWith(compareBy({ it.rank }, { it.y }, { it.x }))
        val byRank = ordered.groupBy { it.rank }
        val quota = mutableListOf<RawNode>()
        byRank.forEach { (_, group) ->
            group.take(MIN_RANK_QUOTA).forEach { quota.add(it) }
        }
        // 先按窗口优先级，再按 y/x：单窗口页面读全，多窗口页面也不会被状态栏吃光名额
        val selected = (quota + ordered).distinct()
            .distinctBy { "${it.text}|${it.x / 16}|${it.y / 16}" }
            .take(MAX_ELEMENTS)
            .sortedWith(compareBy({ it.y }, { it.x }))
        val elements = dropShadowed(selected, width, height).mapIndexed { i, node ->
            ScreenElement(
                index = (i + 1).toString(),
                text = node.text,
                centerX = node.x,
                centerY = node.y,
                clickable = node.clickable || node.ancestorClickable || node.descendantClickable,
                editable = node.editable,
                focused = node.focused,
                viewId = node.viewId,
                password = node.password,
                selectable = node.selectable,
                bounds = node.bounds,
                aggregated = node.aggregated,
                checkable = node.checkable,
                checked = node.checked,
                enabled = node.enabled,
                rangeFraction = node.rangeFraction,
                system = node.rank == RANK_SYSTEM,
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

    /**
     * 同一个列表行里，容器（可点）和它的文字子节点（不可点）都会被收到。
     * 只留容器：容器的点击面积更大、点中心更稳，而且候选里不会出现"看起来能点其实点不动"的文字。
     * 例外：可编辑（输入框）子节点保留，否则打字会失去目标。
     */
    /** 面积超过屏幕 35% 的容器视为"整页"，不是列表行。 */
    internal fun isPageSized(b: ElementBounds?, width: Int, height: Int): Boolean {
        if (b == null || width <= 0 || height <= 0) return false
        return b.width.toLong() * b.height > width.toLong() * height * PAGE_AREA_RATIO
    }

    private fun dropShadowed(nodes: List<RawNode>, width: Int, height: Int): List<RawNode> {
        // 只有"行"大小的容器才去重。整屏/半屏的可点容器（桌面图标网格、整页 WebView）不能把里面的控件全吞掉：
        // 真机实测桌面 59 个图标被一个全屏可点容器聚合成了 1 个元素「时钟天气 时钟天气 日历」。
        val rowSized = nodes.filter { it.clickable && it.bounds != null && !isPageSized(it.bounds, width, height) }
        // 包着输入框的容器是"输入栏"（输入框 + 发送/表情按钮），不是列表行：里面的按钮各自有用，不能被它吞掉。
        // 真机（QQ 聊天页）：整条输入栏可点，标签借了子按钮的「发送」，中心却落在输入框上——点「发送」等于点输入框。
        val inputBars = rowSized.filter { c -> nodes.any { it !== c && it.editable && it.bounds != null && c.bounds!!.contains(it.bounds) } }
            .toSet()
        // 按钮组（工具栏、多选模式的操作栏）：一条容器里有 2 个以上各自可点的按钮。里面每个按钮都有用，不能被容器吞掉。
        // AndroidWorld（Markor 多选模式）：顶部操作栏被聚合成一行「Favourite Rename Details」，点它的中心什么都没发生，
        // 真正的「删除」按钮就在里面，模型却一直看不到。开关不算按钮（行里一个开关是"开关行"，由 singleCheckableDescendant 处理）。
        val buttonGroups = rowSized.filter { c -> isButtonGroup(c, nodes, width, height) }.toSet()
        val containers = rowSized.filter { it !in inputBars && it !in buttonGroups }
        val kept = nodes.filter { node ->
            val bounds = node.bounds
            val shadowed = bounds != null && containers.any { it !== node && it.bounds!!.contains(bounds) }
            // 滑块即使在可点的行里也要留下：它要用 SET_SLIDER 拖，不是点行
            !shadowed || node.editable || node.focused || node.rangeFraction != null
        }
        // 输入栏 / 按钮组的标签只是借来的子控件文字：子控件本身留下了，容器就不再重复出现
        return kept.filterNot { bar ->
            if (!bar.aggregated) return@filterNot false
            val children = kept.filter { it !== bar && !it.editable && it.bounds != null && bar.bounds!!.contains(it.bounds) }
            when (bar) {
                in inputBars -> children.any { it.text == bar.text }
                in buttonGroups -> children.any { it.text.isNotEmpty() && bar.text.contains(it.text) }
                else -> false
            }
        }
    }

    /**
     * 能横向滚动的一排：HorizontalScrollView，或者只有一行、多列的集合（横向 RecyclerView），或者又宽又扁的滚动容器。
     * 太高的（横向翻页的整屏 ViewPager）不算：那是换页，不是一排里还有项没露出来。
     */
    internal fun isHorizontalScroller(className: String, b: ElementBounds, rows: Int, columns: Int, width: Int, height: Int): Boolean {
        if (b.width <= 0 || b.height <= 0 || b.height > height * HSCROLL_MAX_HEIGHT) return false
        if (className.contains("HorizontalScrollView")) return true
        // 竖着滚的：ScrollView / ListView，或者多行一列的集合
        if (className.endsWith("ScrollView") || className.endsWith("ListView") || (rows > 1 && columns <= 1)) return false
        if (columns > 1 && rows in 0..1) return true
        return b.width >= b.height * 3 && b.width >= width / 2
    }

    /** 容器里（不含与它同大小的自身包装层）有 ≥2 个可点、非开关、非输入框的子控件；而且容器像一条横栏，或者按钮 ≥3 个。 */
    internal fun isButtonGroup(c: RawNode, nodes: List<RawNode>, width: Int, height: Int): Boolean {
        val cb = c.bounds ?: return false
        val buttons = nodes.count { n ->
            n !== c && n.clickable && !n.checkable && !n.editable && n.bounds != null && n.bounds != cb && cb.contains(n.bounds)
        }
        if (buttons < 2) return false
        val bar = cb.width >= width * 0.6 && cb.height <= height * 0.12
        return bar || buttons >= 3
    }

    private fun collect(
        node: AccessibilityNodeInfo,
        out: MutableList<RawNode>,
        depth: Int,
        visited: IntArray,
        rank: Int,
        ancestorClickable: Boolean,
        screenW: Int,
        screenH: Int,
        /** 非空时收集图片/WebView/画布类控件的范围（混合 OCR 的触发依据）。 */
        images: MutableList<ElementBounds>? = null,
        /** 非空时收集能横向滚动的一排控件。 */
        hScrolls: MutableList<ScrollRow>? = null,
    ) {
        if (depth > 25 || visited[0] > 400) return
        visited[0]++
        val clickable = node.isClickable
        val pageSizedNode = clickable && isPageSized(
            Rect().also { node.getBoundsInScreen(it) }.let { ElementBounds(it.left, it.top, it.right, it.bottom) },
            screenW, screenH,
        )
        if (hScrolls != null && node.isScrollable && node.isVisibleToUser) {
            val r = Rect().also { node.getBoundsInScreen(it) }
            val b = ElementBounds(r.left, r.top, r.right, r.bottom)
            val info = node.collectionInfo
            if (isHorizontalScroller(node.className?.toString().orEmpty(), b, info?.rowCount ?: -1, info?.columnCount ?: -1, screenW, screenH)) {
                hScrolls.add(ScrollRow(b, aggregatedText(node)))
            }
        }
        if (images != null && node.isVisibleToUser) {
            val cls = node.className?.toString().orEmpty()
            if (IMAGE_CLASS_HINTS.any { cls.contains(it) }) {
                val r = Rect().also { node.getBoundsInScreen(it) }
                if (r.width().toLong() * r.height() >= MIN_IMAGE_AREA * screenW * screenH) {
                    images.add(ElementBounds(r.left, r.top, r.right, r.bottom))
                }
            }
        }
        if (node.isVisibleToUser) {
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            // 密码框的 text 在部分 ROM 上是明文，不能进入发给决策器的屏幕文字
            val password = node.isPassword
            val own = if (password) PASSWORD_PLACEHOLDER else text.ifEmpty { desc }
            val editable = node.isEditable
            val focused = node.isFocused
            // 容器自身没文字时，把后代文字拼进来：模型看到「电池」而不是空白行
            val aggregated = own.isEmpty() && clickable && !password && !editable && !pageSizedNode
            val label = if (aggregated) aggregatedText(node) else own
            val range = rangeFractionOf(node)
            // 开关常是列表行里的子控件：行被点，状态在子节点上。行里恰好一个开关时，把它的状态挂到行上，
            // 模型才看得出「蓝牙 已开启」这一行是个处于"开"的开关，不会把已经打开的再关掉。
            val ownCheckable = node.isCheckable
            val rowCheck = if (!ownCheckable && clickable && !pageSizedNode) singleCheckableDescendant(node) else null
            val checkable = ownCheckable || rowCheck != null
            val checked = when {
                ownCheckable -> node.isChecked
                rowCheck != null -> rowCheck
                else -> null
            }
            if (label.isNotEmpty() || clickable || editable || range != null) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (bounds.width() > 0 && bounds.height() > 0) {
                    val descendantClickable = if (clickable) false else hasClickableDescendant(node)
                    out.add(
                        RawNode(
                            text = label.take(DESC_MAX_LEN),
                            x = bounds.centerX(),
                            y = bounds.centerY(),
                            clickable = clickable,
                            editable = editable,
                            focused = focused,
                            viewId = node.viewIdResourceName ?: "",
                            pkg = node.packageName?.toString().orEmpty(),
                            password = password,
                            rank = rank,
                            bounds = ElementBounds(bounds.left, bounds.top, bounds.right, bounds.bottom),
                            className = node.className?.toString().orEmpty(),
                            ancestorClickable = ancestorClickable,
                            descendantClickable = descendantClickable,
                            selectable = isSelectableRow(clickable, ancestorClickable, descendantClickable, node.className?.toString().orEmpty()),
                            aggregated = aggregated,
                            checkable = checkable,
                            checked = checked,
                            enabled = node.isEnabled,
                            rangeFraction = range,
                        ),
                    )
                }
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                // 整页大小的可点容器不算"可点祖先"：否则桌面上每个图标都被当成"某个大容器的一部分"
                collect(
                    child, out, depth + 1, visited, rank,
                    ancestorClickable = ancestorClickable || (clickable && !pageSizedNode),
                    screenW = screenW, screenH = screenH, images = images, hScrolls = hScrolls,
                )
            } finally {
                runCatching { child.recycle() }
            }
        }
    }

    /** 容器自身无文字：拿后代文字/描述拼一个标签，深度、条数、总长都限死。 */
    private fun aggregatedText(node: AccessibilityNodeInfo): String {
        val parts = ArrayList<String>(DESC_MAX_PARTS)
        var total = 0
        fun scan(current: AccessibilityNodeInfo, depth: Int) {
            if (depth > DESC_DEPTH || parts.size >= DESC_MAX_PARTS || total >= DESC_MAX_LEN) return
            if (current.isVisibleToUser && !current.isPassword) {
                val text = current.text?.toString()?.trim().orEmpty()
                val desc = current.contentDescription?.toString()?.trim().orEmpty()
                val label = text.ifEmpty { desc }
                if (label.isNotEmpty() && parts.size < DESC_MAX_PARTS && total < DESC_MAX_LEN) {
                    total += label.length + 1
                    parts.add(label)
                }
            }
            for (i in 0 until current.childCount) {
                if (parts.size >= DESC_MAX_PARTS || total >= DESC_MAX_LEN) return
                val child = current.getChild(i) ?: continue
                try {
                    scan(child, depth + 1)
                } finally {
                    runCatching { child.recycle() }
                }
            }
        }
        for (i in 0 until node.childCount) {
            if (parts.size >= DESC_MAX_PARTS || total >= DESC_MAX_LEN) break
            val child = node.getChild(i) ?: continue
            try {
                scan(child, 1)
            } finally {
                runCatching { child.recycle() }
            }
        }
        return parts.joinToString(" ").take(DESC_MAX_LEN)
    }

    /** 滑块/进度条：当前值在 [min,max] 中的位置。取不到、区间为 0 时返回 null。 */
    private fun rangeFractionOf(node: AccessibilityNodeInfo): Double? {
        val info = node.rangeInfo ?: return null
        val span = info.max - info.min
        if (span <= 0f) return null
        return ((info.current - info.min) / span).toDouble().coerceIn(0.0, 1.0)
    }

    /** 行里（3 层内）恰好一个可勾选子控件时返回它的勾选状态；没有或不止一个返回 null。 */
    private fun singleCheckableDescendant(node: AccessibilityNodeInfo): Boolean? {
        var found: Boolean? = null
        var count = 0
        fun scan(current: AccessibilityNodeInfo, depth: Int) {
            if (depth > 3 || count > 1) return
            for (i in 0 until current.childCount) {
                val child = current.getChild(i) ?: continue
                try {
                    if (child.isCheckable) {
                        count++
                        found = child.isChecked
                    }
                    scan(child, depth + 1)
                } finally {
                    runCatching { child.recycle() }
                }
            }
        }
        scan(node, 1)
        return if (count == 1) found else null
    }

    private fun hasClickableDescendant(node: AccessibilityNodeInfo): Boolean {
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                if (child.isClickable || hasClickableDescendant(child)) return true
            } finally {
                runCatching { child.recycle() }
            }
        }
        return false
    }

    /** 容器/列表行：可点但本身不是按钮类控件，或自己不在可点祖先里。 */
    internal fun isSelectableRow(
        clickable: Boolean,
        ancestorClickable: Boolean,
        descendantClickable: Boolean,
        className: String,
    ): Boolean {
        if (!clickable && !descendantClickable) return false
        if (ancestorClickable) return false
        return CONTROL_CLASS_HINTS.none { className.contains(it) }
    }

    /** 供 AgentService 的 debug_screen 使用的人读快照。 */
    internal fun describe(observation: ScreenObservation): String = buildString {
        append("locked=").append(observation.locked)
        append(" source=").append(observation.source)
        append(" pkg=").append(observation.packageName.ifEmpty { "-" })
        append(' ').append(observation.screenWidth).append('x').append(observation.screenHeight)
        append(" windows=").append(observation.windows.size)
        append(" elements=").append(observation.elements.size)
        append('\n')
        observation.windows.forEachIndexed { i, w ->
            append("- [").append(i).append("] rank=").append(w.rank)
            append(" active=").append(w.active)
            append(" focused=").append(w.focused)
            append(" type=").append(w.type)
            append(" pkg=").append(w.packageName)
            append(" nodes=").append(w.nodeCount)
            append(" textLen=").append(w.text.length)
            append('\n')
        }
        observation.elements.take(60).forEach { el ->
            append(el.index).append(". ")
            append(if (el.clickable) "TAP " else "    ")
            append(if (el.selectable) "[row] " else "      ")
            append(if (el.editable) "EDIT " else "     ")
            append(if (el.focused) "FOCUS " else "      ")
            append(if (el.password) "PWD " else "    ")
            if (el.checkable) append(if (el.checked == true) "[ON] " else "[OFF] ")
            if (!el.enabled) append("[DISABLED] ")
            el.rangeFraction?.let { append("[RANGE ${(it * 100).toInt()}%] ") }
            append('(').append(el.centerX).append(',').append(el.centerY).append(") ")
            append(if (el.text.isEmpty()) "「」" else "「${el.text}」")
            if (el.viewId.isNotEmpty()) append(" id=").append(el.viewId)
            append('\n')
        }
        if (observation.elements.size > 60) append("… 共 ${observation.elements.size} 个\n")
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
        val bounds: ElementBounds? = null,
        val className: String = "",
        /** 存在可点击祖先：容器上的点击由坐标命中，所以祖先可点也算"可点"。 */
        val ancestorClickable: Boolean = false,
        /** 自身不可点但有可点后代：点击它一样能命中那个后代。 */
        val descendantClickable: Boolean = false,
        val selectable: Boolean = false,
        /** text 是从后代拼接来的（容器聚合），不是节点自身文字。 */
        val aggregated: Boolean = false,
        val checkable: Boolean = false,
        val checked: Boolean? = null,
        val enabled: Boolean = true,
        /** 滑块当前值在 [min,max] 中的位置 0~1；不是滑块为 null。 */
        val rangeFraction: Double? = null,
    )
}
