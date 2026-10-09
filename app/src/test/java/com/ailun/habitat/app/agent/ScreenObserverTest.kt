package com.ailun.habitat.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 读屏保真度：可点击容器聚合、真实 clickable 语义、悬浮球排除、锁屏短路。
 *
 * 锁屏判定的分支在 [ScreenObserver.screenState]（需要 Context），这里只覆盖它的纯数据形态
 * [ScreenObserver.ScreenState] / [ScreenObservation.locked]；真机行为由 debug_screen 验证。
 */
class ScreenObserverTest {

    private fun node(
        text: String,
        x: Int,
        y: Int,
        pkg: String = "com.android.settings",
        clickable: Boolean = false,
        editable: Boolean = false,
        focused: Boolean = false,
        rank: Int = ScreenObserver.RANK_ACTIVE,
        bounds: ElementBounds? = null,
        className: String = "android.widget.TextView",
        ancestorClickable: Boolean = false,
        descendantClickable: Boolean = false,
        viewId: String = "",
        password: Boolean = false,
        aggregated: Boolean = false,
    ) = ScreenObserver.RawNode(
        text = text, x = x, y = y, clickable = clickable, editable = editable, focused = focused,
        viewId = viewId, pkg = pkg, password = password, rank = rank,
        bounds = bounds, className = className,
        ancestorClickable = ancestorClickable, descendantClickable = descendantClickable,
        selectable = ScreenObserver.isSelectableRow(clickable, ancestorClickable, descendantClickable, className),
        aggregated = aggregated,
    )

    /** 列表行容器：自身可点，标签是后代拼出来的。 */
    private fun row(text: String, y: Int, viewId: String = "") = node(
        text = text, x = 720, y = y, clickable = true, bounds = ElementBounds(80, y - 100, 1359, y + 100),
        className = "android.widget.FrameLayout", viewId = viewId, aggregated = true,
    )

    /** 行内文字子节点：不可点，落在容器的矩形里。 */
    private fun rowChild(text: String, y: Int) = node(
        text = text, x = 500, y = y, clickable = false, bounds = ElementBounds(140, y - 60, 900, y + 60),
        className = "android.widget.TextView",
    )

    @Test
    fun clickableContainerAggregatesChildTextIntoOneElement() {
        val raw = listOf(row("电池", 300, "com.android.settings:id/title"), rowChild("电池", 300))
        val obs = ScreenObserver.select(raw, 1440, 3200, "a11y")
        assertEquals(1, obs.elements.size)
        val el = obs.elements.single()
        assertEquals("电池", el.text)
        assertTrue(el.clickable)
        assertTrue(el.selectable)
        assertEquals("com.android.settings:id/title", el.viewId)
        assertEquals(720, el.centerX)
    }

    @Test
    fun nonClickableTextNodeIsNotMarkedClickable() {
        val obs = ScreenObserver.select(listOf(rowChild("隐私政策说明", 400)), 1440, 3200, "a11y")
        assertEquals(1, obs.elements.size)
        assertFalse(obs.elements.single().clickable)
        assertFalse(obs.elements.single().selectable)
    }

    @Test
    fun editableChildSurvivesInsideClickableRow() {
        val container = row("", 500, "com.android.settings:id/row")
        val field = node(
            text = "", x = 500, y = 500, editable = true, focused = true,
            bounds = ElementBounds(140, 460, 900, 540), className = "android.widget.EditText",
        )
        val obs = ScreenObserver.select(listOf(container, field), 1440, 3200, "a11y")
        assertEquals(2, obs.elements.size)
        assertTrue(obs.elements.any { it.editable && it.focused })
        assertEquals(obs.elements.first { it.editable }.index, obs.focusedIndex)
    }

    @Test
    fun selfFloatWindowNodesAreExcludedWhenForeignNodesExist() {
        val float = node(
            text = "H", x = 1200, y = 1200, pkg = "com.ailun.habitat", clickable = true,
            rank = ScreenObserver.RANK_SYSTEM, bounds = ElementBounds(1150, 1150, 1250, 1250),
            className = "android.view.View", viewId = "com.ailun.habitat:id/bubble",
        )
        val obs = ScreenObserver.select(listOf(row("电池", 300), row("隐私", 600), float), 1440, 3200, "a11y")
        assertTrue(obs.elements.none { it.text == "H" })
        assertTrue(obs.elements.none { it.viewId.startsWith("com.ailun.habitat") })
        assertEquals(2, obs.elements.size)
        assertEquals("com.android.settings", obs.packageName)
    }

    @Test
    fun selfNodesKeptWhenNoForeignWindowExists() {
        val only = node("H", 1200, 1200, pkg = "com.ailun.habitat", clickable = true, className = "android.view.View")
        val obs = ScreenObserver.select(listOf(only), 1440, 3200, "a11y")
        assertEquals(1, obs.elements.size)
        assertEquals("com.ailun.habitat", obs.packageName)
    }

    @Test
    fun childTextOfClickableAncestorIsClickable() {
        val container = row("清空缓存", 700)
        val child = rowChild("清空缓存", 700).copy(ancestorClickable = true)
        val obs = ScreenObserver.select(listOf(container, child), 1440, 3200, "a11y")
        assertEquals(1, obs.elements.size)
        assertTrue(obs.elements.single().clickable)
    }

    @Test
    fun elementWithClickableDescendantCountsAsClickable() {
        val wrapper = node(
            text = "关于手机", x = 720, y = 900, clickable = false, descendantClickable = true,
            bounds = ElementBounds(80, 800, 1359, 1000), className = "android.widget.LinearLayout",
        )
        val obs = ScreenObserver.select(listOf(wrapper), 1440, 3200, "a11y")
        assertTrue(obs.elements.single().clickable)
    }

    @Test
    fun longListKeepsEveryWindowQuotaInsteadOfOneWindowEatingAll() {
        val statusBar = (0 until 60).map {
            node("icon$it", 100 + it, it, pkg = "com.android.systemui", clickable = true, rank = ScreenObserver.RANK_SYSTEM)
        }
        val settings = (0 until 20).map { row("项$it", 500 + it * 100) }
        val obs = ScreenObserver.select(statusBar + settings, 1440, 3200, "a11y")
        assertTrue(obs.elements.count { it.text.startsWith("项") } >= 6)
        assertTrue(obs.elements.size <= 60)
        assertEquals((1..obs.elements.size).map { it.toString() }, obs.elements.map { it.index })
    }

    @Test
    fun selectableRowSemantics() {
        fun row(className: String) = ScreenObserver.isSelectableRow(
            clickable = true, ancestorClickable = false, descendantClickable = false, className = className,
        )
        assertTrue(row("android.widget.FrameLayout"))
        assertTrue(row("android.widget.LinearLayout"))
        assertTrue(row("android.widget.RelativeLayout"))
        assertFalse(row("android.widget.Switch"))
        assertFalse(row("android.widget.CheckBox"))
        assertFalse(row("android.widget.Button"))
        assertFalse(row("android.widget.RadioButton"))
        // 自己就在可点祖先里：不算独立候选（避免同一行报两层）
        assertFalse(
            ScreenObserver.isSelectableRow(
                clickable = true, ancestorClickable = true, descendantClickable = false, className = "android.widget.FrameLayout",
            ),
        )
        // 完全不可点也不是候选
        assertFalse(
            ScreenObserver.isSelectableRow(
                clickable = false, ancestorClickable = false, descendantClickable = false, className = "android.widget.TextView",
            ),
        )
    }

    @Test
    fun lockedScreenObservationIsEmpty() {
        val locked = ScreenObservation.locked(1440, 3200)
        assertTrue(locked.locked)
        assertTrue(locked.elements.isEmpty())
        assertTrue(locked.visibleText.isEmpty())
        assertEquals("locked", locked.source)
        assertFalse(ScreenObservation.empty(1440, 3200).locked)
    }

    @Test
    fun describeMentionsWindowsAndElements() {
        val obs = ScreenObserver.select(listOf(row("电池", 300)), 1440, 3200, "a11y").copy(
            windows = listOf(
                WindowInfo(
                    packageName = "com.android.settings", rank = ScreenObserver.RANK_ACTIVE,
                    active = true, type = 1, focused = true, nodeCount = 12, text = "电池",
                ),
            ),
        )
        val text = ScreenObserver.describe(obs)
        assertTrue(text.contains("locked=false"))
        assertTrue(text.contains("active=true"))
        assertTrue(text.contains("com.android.settings"))
        assertTrue(text.contains("「电池」"))
    }

    @Test
    fun aggregatedElementsDoNotCountTowardTreeTextLength() {
        // 容器把后代文字复制了一份：textLen 只看非聚合元素，避免"看起来文字够了"而跳过 OCR
        val obs = ScreenObservation(
            packageName = "com.android.settings",
            screenWidth = 1440,
            screenHeight = 3200,
            elements = listOf(
                ScreenElement("1", "电池", 720, 300, clickable = true, editable = false, focused = false, aggregated = true),
            ),
            visibleText = "电池",
            source = "a11y",
        )
        assertTrue(obs.elements.all { it.aggregated })
    }

    @Test
    fun statusBarNotificationIconsDoNotCrowdOutPage() {
        // 真机：通知图标一条一个节点（"微信通知："…），页面节点少时把名额填满
        val icons = (0 until 40).map {
            node("App${it}通知：", 600, 75, pkg = "com.android.systemui", rank = ScreenObserver.RANK_SYSTEM)
        }
        val clock = node("11:04", 158, 76, pkg = "com.android.systemui", clickable = true, rank = ScreenObserver.RANK_SYSTEM)
        val page = (0 until 5).map { row("项$it", 500 + it * 200) }
        val obs = ScreenObserver.select(icons + clock + page, 1440, 3200, "a11y")
        assertTrue(obs.elements.none { it.text.endsWith("通知：") })
        assertEquals(5, obs.elements.count { it.text.startsWith("项") })
        assertTrue(obs.elements.size <= 5 + 6)
    }

    @Test
    fun ocrOnlyWhenTreeHasNoClickableText() {
        val sparseButClickable = ScreenObserver.select(listOf(row("返回", 200)), 1440, 3200, "a11y")
        assertFalse(ScreenObserver.needsOcr(sparseButClickable))
        val empty = ScreenObservation.empty(1440, 3200)
        assertTrue(ScreenObserver.needsOcr(empty))
    }

    @Test
    fun fullScreenClickableContainerDoesNotSwallowIcons() {
        // 真机：桌面图标网格是一个全屏可点容器，59 个图标被聚合成 1 个元素
        val grid = node(
            text = "时钟天气 时钟天气 日历", x = 720, y = 1600, clickable = true, aggregated = true,
            bounds = ElementBounds(0, 0, 1440, 3200), className = "android.widget.FrameLayout",
            pkg = "com.bbk.launcher2",
        )
        val icons = listOf("时钟", "天气", "日历").mapIndexed { i, t ->
            node(t, 200 + i * 400, 1000, pkg = "com.bbk.launcher2", clickable = true,
                bounds = ElementBounds(100 + i * 400, 900, 300 + i * 400, 1100), className = "android.widget.TextView")
        }
        val obs = ScreenObserver.select(listOf(grid) + icons, 1440, 3200, "a11y")
        assertTrue(listOf("时钟", "天气", "日历").all { t -> obs.elements.any { it.text == t } })
        assertTrue(ScreenObserver.isPageSized(ElementBounds(0, 0, 1440, 3200), 1440, 3200))
        assertFalse(ScreenObserver.isPageSized(ElementBounds(80, 200, 1359, 400), 1440, 3200))
    }
}
