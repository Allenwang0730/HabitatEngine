package com.ailun.habitat.app.agent

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPolicyTest {

    private fun screen(): ScreenObservation = ScreenObservation(
        packageName = "com.example.app",
        screenWidth = 1080,
        screenHeight = 2400,
        elements = listOf(
            ScreenElement("1", "设置", 100, 200, clickable = true, editable = false, focused = false),
            ScreenElement("2", "确认支付", 100, 400, clickable = true, editable = false, focused = false),
            ScreenElement("3", "搜索", 100, 80, clickable = true, editable = true, focused = true),
        ),
        visibleText = "设置\n确认支付\n搜索",
        source = "a11y",
        focusedIndex = "3",
    )

    /** 真机设置页的样子：列表行是 selectable 容器，文字在子 TextView 上。 */
    private fun listScreen(): ScreenObservation = ScreenObservation(
        packageName = "com.android.settings",
        screenWidth = 1080,
        screenHeight = 2400,
        elements = listOf(
            ScreenElement(
                "1", "已连接的设备", 540, 600,
                clickable = true, editable = false, focused = false,
                viewId = "com.android.settings:id/homepage_title",
            ),
            ScreenElement(
                "2", "蓝牙", 540, 800,
                clickable = true, editable = false, focused = false,
                viewId = "com.android.settings:id/title", selectable = true,
            ),
            ScreenElement(
                "3", "WLAN", 540, 700,
                clickable = true, editable = false, focused = false,
                viewId = "com.android.settings:id/title", selectable = true,
            ),
            ScreenElement(
                "4", "蓝牙已关闭", 540, 1000,
                clickable = false, editable = false, focused = false,
            ),
        ),
        visibleText = "已连接的设备\n蓝牙\nWLAN\n蓝牙已关闭",
        source = "a11y",
    )

    @Test
    fun catalogOmitsPaymentUnlessGoalAsks() {
        val plain = Catalogs.build("打开设置", screen(), emptyList())
        assertTrue(plain.tapTargets.values.none { it.contains("支付") })
        assertTrue(plain.tapTargets.values.any { it.contains("设置") })

        val pay = Catalogs.build("去确认支付", screen(), emptyList())
        assertTrue(pay.tapTargets.values.any { it.contains("确认支付") })
    }

    @Test
    fun localPolicyTapsMatchingLabel() {
        val catalog = Catalogs.build("打开设置", screen(), emptyList())
        val decision = decideLocal("打开设置", catalog, emptyList())
        assertEquals("action", decision.status)
        assertEquals("TAP", decision.operation)
        assertEquals("设置", decision.action?.matchText)
    }

    @Test
    fun localPolicyOpensNamedAppBeforeScrolling() {
        val catalog = Catalogs.build(
            "打开微信",
            screen().copy(elements = emptyList(), visibleText = "", focusedIndex = null),
            listOf(AppEntry("微信", "com.tencent.mm")),
        )
        val decision = decideLocal("打开微信", catalog, emptyList())
        assertEquals("OPEN_APP", decision.operation)
        assertEquals("com.tencent.mm", decision.action?.params?.get("package_name"))
    }

    @Test
    fun localPolicyStopsAfterTwoScrolls() {
        val catalog = Catalogs.build("找一个不存在的按钮", screen().copy(elements = emptyList(), visibleText = ""), emptyList())
        val history = listOf(
            StepNote("scroll_down", "SCROLL_DOWN", "向下滚动"),
            StepNote("scroll_down", "SCROLL_DOWN", "向下滚动"),
        )
        assertEquals("blocked", decideLocal("找一个不存在的按钮", catalog, history).status)
    }

    @Test
    fun parseChoiceRejectsUnknownAndLowerProbability() {
        val allowed = setOf("TAP", "BACK")
        val bad = JsonObject().apply {
            addProperty("type", "choice")
            addProperty("choice", "FLY")
            addProperty("confidence", 0.9)
        }
        assertNull(parseChoice(bad, allowed))

        val skewed = JsonObject().apply {
            addProperty("type", "choice")
            addProperty("choice", "TAP")
            addProperty("confidence", 0.2)
            add("probabilities", JsonObject().apply {
                addProperty("TAP", 0.2)
                addProperty("BACK", 0.8)
            })
        }
        assertNull(parseChoice(skewed, allowed))
    }

    @Test
    fun quotedTextBecomesTypeCandidateWhenThereIsAnInput() {
        val values = Catalogs.textCandidates("在搜索框输入「你好」然后发送")
        assertEquals(listOf("你好"), values)
        val catalog = Catalogs.build("在搜索框输入「你好」", screen(), emptyList())
        assertEquals("你好", catalog.textValues["1"])
        // 只有一个输入框时，没报告焦点也给输入（QQ/微信的输入框常常不报焦点）
        val unreported = screen().copy(
            focusedIndex = null,
            elements = screen().elements.map { it.copy(focused = false) },
        )
        assertEquals("你好", Catalogs.build("在搜索框输入「你好」", unreported, emptyList()).textValues["1"])
        val noInput = screen().copy(focusedIndex = null, elements = screen().elements.filter { !it.editable })
        assertTrue(Catalogs.build("在搜索框输入「你好」", noInput, emptyList()).textValues.isEmpty())
    }

    @Test
    fun remoteGoalCannotUnlockSensitiveTap() {
        val remote = Catalogs.build("去确认支付", screen(), emptyList(), allowGoalUnlock = false)
        assertTrue(remote.tapTargets.values.none { it.contains("支付") })
        assertTrue(remote.tapTargets.values.any { it.contains("设置") })
    }

    @Test
    fun paymentPasswordScreenAllowsOnlyNavigation() {
        val pin = screen().copy(
            elements = (1..9).map { ScreenElement("$it", "$it", 100 * it, 1800, clickable = true, editable = false, focused = false) },
            visibleText = "请输入支付密码\n1\n2\n3",
            focusedIndex = null,
        )
        val catalog = Catalogs.build("给张三转账 1 元并确认支付", pin, emptyList())
        assertTrue(catalog.guard != null)
        assertTrue(catalog.tapTargets.isEmpty())
        assertTrue("TAP" !in catalog.operations)
        assertTrue("BACK" in catalog.operations)
    }

    @Test
    fun permissionDialogNeedsExplicitLocalGoal() {
        val dialog = screen().copy(
            packageName = "com.android.permissioncontroller",
            elements = listOf(ScreenElement("1", "允许", 540, 1600, clickable = true, editable = false, focused = false)),
            visibleText = "要允许 X 访问位置信息吗？\n允许",
            focusedIndex = null,
        )
        assertTrue(Catalogs.build("打开地图导航", dialog, emptyList()).tapTargets.isEmpty())
        assertTrue(Catalogs.build("给地图授权定位并允许", dialog, emptyList(), allowGoalUnlock = false).tapTargets.isEmpty())
        assertTrue(Catalogs.build("给地图授权定位并允许", dialog, emptyList()).tapTargets["1"]!!.contains("允许"))
    }

    @Test
    fun passwordFieldIsNeverATypeTarget() {
        val pwd = screen().copy(
            elements = listOf(ScreenElement("1", ScreenObserver.PASSWORD_PLACEHOLDER, 100, 80, clickable = true, editable = true, focused = true, password = true)),
            visibleText = ScreenObserver.PASSWORD_PLACEHOLDER,
            focusedIndex = "1",
        )
        assertTrue(Catalogs.build("输入「hunter2」", pwd, emptyList()).textValues.isEmpty())
    }

    @Test
    fun activeWindowWinsOverStatusBar() {
        fun node(text: String, y: Int, pkg: String, rank: Int) = ScreenObserver.RawNode(
            text = text, x = 100 + y % 7, y = y, clickable = true, editable = false, focused = false,
            viewId = "", pkg = pkg, password = false, rank = rank,
        )
        val statusBar = (0 until 50).map { node("icon$it", it, "com.android.systemui", ScreenObserver.RANK_SYSTEM) }
        val settings = listOf("隐私", "电池", "关于手机").mapIndexed { i, t ->
            node(t, 500 + i * 200, "com.android.settings", ScreenObserver.RANK_ACTIVE)
        }
        val obs = ScreenObserver.select(statusBar + settings, 1440, 3200, "a11y")
        assertEquals("com.android.settings", obs.packageName)
        assertTrue(obs.elements.map { it.text }.containsAll(listOf("隐私", "电池", "关于手机")))
        assertEquals((1..obs.elements.size).map { it.toString() }, obs.elements.map { it.index })
    }

    @Test
    fun jevAnswerWithWrongFieldTypesIsRejectedNotThrown() {
        val catalog = Catalogs.build("打开设置", screen(), emptyList())
        assertEquals("error", parseJevAnswer("""{"answers":[]}""", catalog).status)
        assertEquals("error", parseJevAnswer("""{"answers":{"operation":null}}""", catalog).status)
        assertEquals("error", parseJevAnswer("""{"answers":{"operation":{"choice":null}}}""", catalog).status)
        assertEquals("error", parseJevAnswer("""{"answers":{"operation":{"choice":"TAP"},"tap_target":"1"}}""", catalog).status)
        assertEquals("error", parseJevAnswer("not json", catalog).status)
        assertEquals("error", parseJevAnswer("""{"answers":{"operation":{"choice":"TAP","confidence":"high"}}}""", catalog).status)
    }

    @Test
    fun jevAnswerResolvesTapAndKeepsConfidence() {
        val catalog = Catalogs.build("打开设置", screen(), emptyList())
        // 解析结果必须是"该序号对应的那个候选"，不写死具体文字：
        // 候选排序（真按钮 → 列表行 → 文字节点）会随排序规则调整。
        val d = parseJevAnswer(
            """{"answers":{"operation":{"type":"choice","choice":"TAP","confidence":0.8},"tap_target":{"choice":"1"}}}""",
            catalog,
        )
        assertEquals("action", d.status)
        assertEquals(catalog.tapTargets["1"], catalog.tapTargets[d.action?.target])
        assertTrue(d.action?.target == "1")
        assertEquals(0.8, d.confidence, 1e-9)
    }

    @Test
    fun jevAnswerResolvesLongPressTarget() {
        val catalog = Catalogs.build("打开设置，进入蓝牙页面", listScreen(), emptyList())
        assertTrue(catalog.longPressTargets.isNotEmpty())
        val d = parseJevAnswer(
            """{"answers":{"operation":{"choice":"LONG_PRESS","confidence":0.7},"long_press_target":{"choice":"1"}}}""",
            catalog,
        )
        assertEquals("action", d.status)
        assertEquals("LONG_PRESS", d.operation)
        assertEquals("ACTION_LONG_PRESS", d.action?.tool)
    }

    @Test
    fun modelDecisionWithNullTargetDoesNotThrow() {
        val catalog = Catalogs.build("打开设置", screen(), emptyList())
        assertEquals("error", parseModelDecision("""{"operation":"TAP","target":null}""", catalog).status)
        assertEquals("action", parseModelDecision("""好的 {"operation":"BACK"}""", catalog).status)
    }

    // ---------- B：候选生成 ----------

    @Test
    fun selectableListRowsBecomeTapCandidates() {
        val catalog = Catalogs.build("打开设置，进入蓝牙页面", listScreen(), emptyList())
        // 列表行（selectable）必须进候选——真机上 19 个可点行里只有 2 个自带文字
        assertTrue(catalog.tapTargets.values.any { it.contains("蓝牙") })
        assertTrue(catalog.tapTargets.values.any { it.contains("WLAN") })
        // 不可点的纯文字节点不该当成按钮
        val tappedTexts = catalog.actions.filter { it.operation == "TAP" }.mapNotNull { it.matchText }
        assertFalse(tappedTexts.contains("蓝牙已关闭"))
    }

    @Test
    fun buttonsRankBeforeListRowsAndTextNodes() {
        val elements = listOf(
            // 纯文字、可点但没有 selectable 标记：只进"文字节点"层
            ScreenElement("1", "纯文字标签", 540, 300, clickable = true, editable = false, focused = false),
            ScreenElement("2", "蓝牙行", 540, 800, clickable = true, editable = false, focused = false, selectable = true),
            ScreenElement("3", "搜索框", 540, 100, clickable = true, editable = true, focused = false),
        )
        val obs = listScreen().copy(elements = elements, visibleText = elements.joinToString("\n") { it.text })
        val ordered = Catalogs.orderedTaps("打开设置", obs)
        // 按钮/输入框（tier 0）在前，列表行（tier 1）在后，纯文字（tier 2）最后
        assertEquals(listOf("3", "1", "2"), ordered.map { it.index })
        assertEquals(0, Catalogs.tierOf(elements[2]))
        assertEquals(0, Catalogs.tierOf(elements[0]))
        assertEquals(1, Catalogs.tierOf(elements[1]))
    }

    @Test
    fun nonClickableTextNodeIsNotATapTarget() {
        // 纯文字节点（clickable=false 且不可编辑）不该进候选，否则模型会去点一行说明文字
        val obs = listScreen().copy(
            elements = listOf(
                ScreenElement("1", "蓝牙已关闭", 540, 1000, clickable = false, editable = false, focused = false),
            ),
            visibleText = "蓝牙已关闭",
        )
        assertTrue(Catalogs.build("打开设置，进入蓝牙页面", obs, emptyList()).tapTargets.isEmpty())
    }

    @Test
    fun tapTargetsCarryTypeAndViewId() {
        val catalog = Catalogs.build("打开设置，进入蓝牙页面", listScreen(), emptyList())
        val shelf = catalog.tapTargets.values.first { it.contains("蓝牙") }
        assertTrue("viewId 应写进候选以便区分同名控件：$shelf", shelf.contains("id/title"))
        assertEquals("id/title", Catalogs.shortViewId("com.android.settings:id/title"))
        // 类型分类：设置页列表行不能被 id/title 骗成"标签"
        val row = listScreen().elements.first { it.text == "蓝牙" }
        assertEquals("列表行", Catalogs.typeOf(row))
        assertEquals("输入框", Catalogs.typeOf(screen().elements.first { it.editable }))
        assertEquals("按钮", Catalogs.typeOf(listScreen().elements.first { it.text == "已连接的设备" }))
        // 候选标签里也要带上类型，模型据此知道"这是行还是按钮"
        assertTrue(catalog.actions.any { it.operation == "TAP" && it.label.contains("(列表行)") })
    }

    @Test
    fun ownOverlayPackageYieldsNoTaps() {
        val overlay = listScreen().copy(packageName = "com.ailun.habitat")
        assertTrue(Catalogs.build("打开设置", overlay, emptyList()).tapTargets.isEmpty())
    }

    @Test
    fun enterAndClearOfferedOnlyWithFocusedEditable() {
        val focused = Catalogs.build("打开设置，进入蓝牙页面", listScreen(), emptyList())
        assertFalse("没有焦点输入框时不该给提交/清空", "PRESS_ENTER" in focused.operations)

        val withInput = Catalogs.build("搜索「蓝牙」", screen(), emptyList())
        assertTrue("PRESS_ENTER" in withInput.operations)
        assertTrue("CLEAR_TEXT" in withInput.operations)
        assertEquals(true, withInput.resolve("PRESS_ENTER", null)?.params?.get("submit"))
        assertEquals(true, withInput.resolve("CLEAR_TEXT", null)?.params?.get("allow_empty"))
    }

    // ---------- B：循环策略 ----------

    @Test
    fun lowConfidenceIsExecutedNotDropped() {
        // 真机日志：Jev 给 0.22，旧规则 0.3 直接丢 → 原地打转
        assertEquals(LoopPolicy.Verdict.EXECUTE_LOW, LoopPolicy.confidenceVerdict(0.22))
        assertEquals(LoopPolicy.Verdict.EXECUTE_LOW, LoopPolicy.confidenceVerdict(0.29))
        assertEquals(LoopPolicy.Verdict.EXECUTE, LoopPolicy.confidenceVerdict(0.8))
        assertEquals(LoopPolicy.Verdict.DROP, LoopPolicy.confidenceVerdict(0.1))
    }

    @Test
    fun lockedScreenStopsImmediately() {
        val locked = ScreenObservation.locked(1080, 2400)
        assertEquals("屏幕已锁定，请先解锁", LoopPolicy.screenVerdict(locked))
        assertTrue(locked.locked)
        assertTrue(locked.elements.isEmpty())
        // 锁屏页不给候选，避免模型对空屏幕瞎猜
        val catalog = Catalogs.build("打开设置，进入蓝牙页面", locked, emptyList())
        assertTrue(catalog.tapTargets.isEmpty())
    }

    @Test
    fun signaturePrefersViewIdOverText() {
        val obs = listScreen()
        val withId = CatalogAction(
            id = "tap_2", operation = "TAP", target = "1", label = "点击蓝牙",
            matchText = "蓝牙", viewId = "com.android.settings:id/title",
        )
        val noId = withId.copy(viewId = "")
        assertEquals("TAP|com.android.settings:id/title|com.android.settings", LoopPolicy.signatureOf(withId, obs))
        // 没有 viewId 才退回文字
        assertEquals("TAP|蓝牙|com.android.settings", LoopPolicy.signatureOf(noId, obs))
        // 同一文字在不同包里不算重复
        assertTrue(
            LoopPolicy.signatureOf(noId, obs) != LoopPolicy.signatureOf(noId, obs.copy(packageName = "com.tencent.mm")),
        )
    }

    @Test
    fun repeatCountStopsAtTwoConsecutive() {
        val signature = "TAP|id/title|com.android.settings"
        val one = listOf(StepNote("a", "TAP", "点蓝牙", signature = signature))
        assertEquals(1, LoopPolicy.repeatCount(one, signature))
        val two = one + StepNote("a", "TAP", "点蓝牙", signature = signature)
        assertTrue(LoopPolicy.repeatCount(two, signature) >= LoopPolicy.MAX_SAME_ACTION)
        // 中间隔了别的动作就不算连续
        val broken = one + StepNote("b", "SCROLL_DOWN", "滚动") + StepNote("a", "TAP", "点蓝牙", signature = signature)
        assertEquals(1, LoopPolicy.repeatCount(broken, signature))
    }

    @Test
    fun sameScreenDetectsChangeBeyondText() {
        val a = listScreen()
        assertTrue(LoopPolicy.sameScreen(a, a))
        assertTrue("前台包变了就是变了", !LoopPolicy.sameScreen(a, a.copy(packageName = "com.tencent.mm")))
        assertTrue("控件数变了就是变了", !LoopPolicy.sameScreen(a, a.copy(elements = a.elements.take(2))))
        assertTrue("焦点变了就是变了", !LoopPolicy.sameScreen(a, a.copy(focusedIndex = "2")))
    }

    @Test
    fun retryPicksSecondCandidateOnFailure() {
        val catalog = Catalogs.build("打开设置，进入蓝牙页面", listScreen(), emptyList())
        val first = catalog.actions.first { it.operation == "TAP" && it.matchText == "蓝牙" }
        val retry = LoopPolicy.retryCandidate(catalog, first, "打开设置，进入蓝牙页面", emptyList())
        // 同名的其它候选（WLAN 等）可以再试，但不能还是自己
        assertTrue(retry != null && retry.id != first.id)

        // 已经失败过的锚点不再试
        val failed = listOf(
            StepNote(first.id, "TAP", first.label, changed = false, signature = LoopPolicy.signatureOf(first, listScreen())),
        )
        val afterFailure = LoopPolicy.retryCandidate(catalog, first, "打开设置，进入蓝牙页面", failed)
        assertTrue(afterFailure?.matchText != "蓝牙")
    }

    @Test
    fun recoveryEscalatesScrollThenBack() {
        val catalog = Catalogs.build("打开设置，进入蓝牙页面", listScreen(), emptyList())
        assertEquals("SCROLL_DOWN", LoopPolicy.recoveryAction(catalog, emptyList())?.operation)
        assertEquals(
            "BACK",
            LoopPolicy.recoveryAction(catalog, listOf(StepNote("s", "SCROLL_DOWN", "向下滚动")))?.operation,
        )
        val all = listOf("SCROLL_DOWN", "BACK", "SCROLL_UP").map { StepNote(it, it, it) }
        assertEquals("HOME", LoopPolicy.recoveryAction(catalog, all)?.operation)
    }

    @Test
    fun cycleBetweenTwoActionsIsDetected() {
        val open = StepNote("open_settings", "OPEN_APP", "打开 设置", changed = true, signature = "OPEN_APP|设置|launcher")
        val tap = StepNote("tap_3", "TAP", "点击「关于手机」", changed = true, signature = "TAP|关于手机|com.android.settings")
        val history = listOf(open, tap, open, tap, open)
        // 末尾不是连续重复，但最近 6 步里 open 出现了 3 次
        assertEquals(1, LoopPolicy.repeatCount(history, open.signature))
        assertEquals(3, LoopPolicy.recentCount(history, open.signature))
        assertTrue(LoopPolicy.recentCount(history, open.signature) >= LoopPolicy.MAX_IN_WINDOW)
    }

    @Test
    fun pageTitleIsTopTextSkippingToolbarButtons() {
        val page = screen().copy(
            elements = listOf(
                ScreenElement("1", "返回", 116, 264, clickable = true, editable = false, focused = false),
                ScreenElement("2", "关于手机", 400, 270, clickable = false, editable = false, focused = false),
                ScreenElement("3", "关于手机", 720, 900, clickable = true, editable = false, focused = false),
            ),
            screenHeight = 3200,
        )
        assertEquals("关于手机", MobilePriors.pageTitle(page))
        assertEquals("BluetoothSettingsActivity", MobilePriors.shortActivity("com.android.settings.Settings\$BluetoothSettingsActivity"))
    }

    @Test
    fun repeatedScrollThatMovesTheScreenIsNotACycle() {
        val moving = StepNote("scroll_down", "SCROLL_DOWN", "向下滚动", changed = true, signature = "SCROLL_DOWN||com.android.settings")
        val history = listOf(moving, moving.copy(actionId = "tap_9", operation = "TAP", signature = "TAP|x|p"), moving, moving)
        assertEquals(0, LoopPolicy.recentCount(history, moving.signature))
        val stuck = moving.copy(changed = false)
        assertEquals(3, LoopPolicy.recentCount(listOf(stuck, stuck, stuck), stuck.signature))
    }

    @Test
    fun recoveryFromRepeatedScrollGoesBackFirst() {
        val catalog = Catalogs.build("找到关于手机", screen(), emptyList())
        val scrolls = listOf(StepNote("scroll_down", "SCROLL_DOWN", "向下滚动", changed = false, signature = "s"))
        assertEquals("BACK", LoopPolicy.recoveryAction(catalog, scrolls, avoid = "SCROLL_DOWN")?.operation)
        assertEquals("SCROLL_DOWN", LoopPolicy.recoveryAction(catalog, emptyList())?.operation)
    }

    @Test
    fun deadEndsExcludeIneffectiveSubmitAndBounceOutTaps() {
        val enter = StepNote("press_enter", "PRESS_ENTER", "按回车提交", changed = false, signature = "PRESS_ENTER||com.android.settings")
        val manual = StepNote("tap_2", "TAP", "点击「关于手机 使用手册」", changed = true, signature = "TAP|关于手机 使用手册|com.android.settings", leftApp = true)
        val reopen = StepNote("open_settings", "OPEN_APP", "打开 设置", changed = true, signature = "OPEN_APP|设置|com.vivo.space")
        val dead = LoopPolicy.deadEnds(listOf(enter, manual, reopen))
        assertTrue(enter.signature in dead)
        assertTrue(manual.signature in dead)
        assertFalse(reopen.signature in dead)
        // 跳出后没被拉回（目标就是去另一个 App）不算死路
        assertFalse(manual.signature in LoopPolicy.deadEnds(listOf(manual)))
    }

    @Test
    fun catalogWithoutDropsDeadEndsButKeepsEscapeHatches() {
        val catalog = Catalogs.build("打开设置", screen(), emptyList())
        val tap = catalog.resolve("TAP", catalog.tapTargets.entries.first { it.value.startsWith("设置") }.key)!!
        val sig: (CatalogAction) -> String = { "${it.operation}|${it.matchText.orEmpty()}" }
        val pruned = catalog.without(setOf(sig(tap))) { sig(it) }
        assertTrue(pruned.actions.none { it.id == tap.id })
        assertFalse(pruned.tapTargets.containsKey(tap.target))
        assertTrue(listOf("BACK", "HOME", "DONE", "WAIT", "BLOCKED").all { it in pruned.operations })
    }

    @Test
    fun tapTargetKeysMatchElementIndexShownToModel() {
        // 控件一览与候选必须同一套编号：一览里的 3 号就是候选里的 3 号
        val obs = screen()
        val catalog = Catalogs.build("打开设置", obs, emptyList())
        catalog.tapTargets.keys.forEach { key ->
            val el = obs.elements.first { it.index == key }
            assertTrue(catalog.tapTargets.getValue(key).contains(el.text))
        }
        assertEquals("设置", catalog.resolve("TAP", "1")?.matchText)
        // 3 号是已聚焦的输入框：点它不会有变化，不进 TAP 候选（应当 TYPE_TEXT）
        assertNull(catalog.resolve("TAP", "3"))
    }

    @Test
    fun enteringSubmenuThenBackingOutMarksItExplored() {
        val enter = StepNote("tap_7", "TAP", "点击「系统管理与升级」", changed = true, signature = "TAP|系统管理与升级|com.android.settings")
        val scroll = StepNote("scroll_down", "SCROLL_DOWN", "向下滚动", changed = false, signature = "SCROLL_DOWN||com.android.settings")
        val back = StepNote("back", "BACK", "返回", changed = true, signature = "BACK||com.android.settings")
        assertTrue(enter.signature in LoopPolicy.deadEnds(listOf(enter, scroll, back)))
        // 没退回来：这条路还在走，不算死路
        assertFalse(enter.signature in LoopPolicy.deadEnds(listOf(enter, scroll)))
    }
}
