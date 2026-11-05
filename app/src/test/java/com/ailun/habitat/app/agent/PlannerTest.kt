package com.ailun.habitat.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 规划器输出校验、设备工具放行、导航栈 —— 都是纯逻辑，规划器/模型的输出一律按不可信处理。 */
class PlannerTest {

    private val apps = listOf(AppEntry("时钟", "com.android.BBKClock"), AppEntry("设置", "com.android.settings"))

    private fun el(
        index: String, text: String, y: Int, clickable: Boolean = true, checkable: Boolean = false,
        checked: Boolean? = null, range: Double? = null, selectable: Boolean = false,
    ) = ScreenElement(
        index, text, 720, y, clickable = clickable, editable = false, focused = false,
        selectable = selectable, bounds = ElementBounds(80, y - 60, 1360, y + 60),
        checkable = checkable, checked = checked, rangeFraction = range,
    )

    private fun screen(pkg: String = "com.android.settings", vararg elements: ScreenElement) = ScreenObservation(
        packageName = pkg, screenWidth = 1440, screenHeight = 3200,
        elements = elements.toList(), visibleText = elements.joinToString("\n") { it.text }, source = "a11y",
    )

    @Test
    fun validPlanKeepsToolStepsAndUiSteps() {
        val raw = """好的 {"summary":"开蓝牙","steps":[
            {"type":"tool","tool":"open_settings","args":{"page":"bluetooth"},"instruction":"打开蓝牙设置页","done_when":"页面标题是蓝牙"},
            {"type":"ui","instruction":"把蓝牙开关打开","done_when":"蓝牙开关显示已开启"}]}"""
        val plan = PlanPrompts.parsePlan(raw, "打开蓝牙", apps)!!
        assertEquals(2, plan.steps.size)
        assertEquals("open_settings", plan.steps[0].tool)
        assertEquals(mapOf("page" to "bluetooth"), plan.steps[0].args)
        assertNull(plan.steps[1].tool)
    }

    @Test
    fun invalidToolStepsDegradeToUiSteps() {
        val raw = """{"steps":[
            {"type":"tool","tool":"shell","args":{"cmd":"rm -rf /"},"instruction":"清理","done_when":"完成"},
            {"type":"tool","tool":"open_settings","args":{"page":"secret_page"},"instruction":"打开某页","done_when":"已打开"},
            {"type":"tool","tool":"set_wifi","args":{"state":"off"},"instruction":"关掉 WLAN","done_when":"WLAN 已关闭"}]}"""
        // 目标没提 WLAN：规划器自己"决定"关 WLAN 也执行不了
        val plan = PlanPrompts.parsePlan(raw, "打开设置看看电池", apps)!!
        assertTrue(plan.steps.all { it.tool == null })
        assertEquals(3, plan.steps.size)
    }

    @Test
    fun planTextMustComeFromTheGoal() {
        val raw = """{"steps":[
            {"type":"ui","instruction":"在搜索框输入","done_when":"出现结果","text":"电池"},
            {"type":"ui","instruction":"在搜索框输入","done_when":"出现结果","text":"我的银行卡密码"}]}"""
        val plan = PlanPrompts.parsePlan(raw, "打开设置，在搜索框输入「电池」并搜索", apps)!!
        assertEquals("电池", plan.steps[0].text)
        assertNull(plan.steps[1].text)
    }

    @Test
    fun malformedPlansReturnNullAndStepCountIsCapped() {
        assertNull(PlanPrompts.parsePlan("没有 JSON", "x", apps))
        assertNull(PlanPrompts.parsePlan("""{"steps":"no"}""", "x", apps))
        assertNull(PlanPrompts.parsePlan("""{"steps":[{"instruction":"","done_when":""}]}""", "x", apps))
        val many = (1..10).joinToString(",") { """{"type":"ui","instruction":"第${it}步","done_when":"好了"}""" }
        assertEquals(PlanPrompts.MAX_STEPS, PlanPrompts.parsePlan("""{"steps":[$many]}""", "x", apps)!!.steps.size)
    }

    @Test
    fun reviewParsing() {
        val ok = PlanPrompts.parseReview("""{"done":true,"reason":"标题是关于手机","answer":"版本 16"}""")!!
        assertTrue(ok.done)
        assertEquals("版本 16", ok.answer)
        assertNull(PlanPrompts.parseReview("""{"done":"yes"}"""))
        assertNull(PlanPrompts.parseReview("不是 JSON"))
    }

    @Test
    fun stateChangingToolsNeedTheUserGoalToNameThem() {
        assertNull(DeviceTools.resolve("set_bluetooth", mapOf("state" to "on"), "打开设置", apps))
        assertNotNull(DeviceTools.resolve("set_bluetooth", mapOf("state" to "on"), "打开蓝牙", apps))
        assertNull(DeviceTools.resolve("set_bluetooth", mapOf("state" to "maybe"), "打开蓝牙", apps))
        assertNull(DeviceTools.resolve("open_app", mapOf("package" to "com.evil.app"), "打开它", apps))
        assertNotNull(DeviceTools.resolve("open_app", mapOf("package" to "com.android.BBKClock"), "打开时钟", apps))
        assertEquals(
            "android.settings.DEVICE_INFO_SETTINGS",
            DeviceTools.resolve("open_settings", mapOf("page" to "about"), "随便", apps)!!.params["intent_action"],
        )
        val names = DeviceTools.available("打开设置").map { it.id }
        assertFalse("set_wifi" in names)
        assertTrue("open_settings" in names && "read_battery" in names)
    }

    @Test
    fun catalogOffersToolShortcutsSlidersAndSwitchState() {
        val obs = screen(
            "com.android.settings",
            el("1", "蓝牙", 500, checkable = true, checked = true, selectable = true),
            el("2", "亮度", 900, clickable = false, range = 0.3),
        )
        val catalog = Catalogs.build("打开设置，把亮度调到最高，再打开蓝牙", obs, apps)
        assertTrue(catalog.toolTargets.values.any { it.contains("蓝牙") })
        assertTrue(catalog.toolTargets.values.any { it.contains("亮度") })
        // 开关状态进候选描述：模型能看出已经是开的
        assertTrue(catalog.tapTargets.getValue("1").contains("[已开启]"))
        val slider = catalog.resolve("SET_SLIDER", "2:max")!!
        assertEquals("ACTION_SWIPE", slider.tool)
        assertEquals(80 + (1280 * 0.3).toInt(), slider.params["x1"])
        assertEquals(1359, slider.params["x2"])
    }

    @Test
    fun plannerStepTextCannotUnlockSensitiveButtons() {
        // 规划器在步骤说明里写「确认支付」，用户目标里没有：支付按钮仍然不能进候选
        val obs = screen("com.example.shop", el("1", "确认支付", 1800))
        val step = StepContext("看看订单", "点击确认支付完成购买", "支付成功", 0, 2)
        val catalog = Catalogs.build("看看订单", obs, apps, step = step)
        assertTrue(catalog.tapTargets.values.none { it.contains("支付") })
        assertTrue(catalog.operations.getValue("DONE").contains("支付成功"))
    }

    @Test
    fun stepDoneHeadIsParsed() {
        val text = """{"answers":{"operation":{"choice":"BACK","confidence":0.6},
            "step_status":{"choice":"DONE","confidence":0.9,"probabilities":{"DONE":0.85,"NOT_YET":0.15}}}}"""
        assertEquals(0.85, parseStepDone(text)!!, 1e-9)
        assertNull(parseStepDone("""{"answers":{"operation":{"choice":"BACK"}}}"""))
    }

    @Test
    fun navigationStackPushesPopsAndRemembersDeadEnds() {
        val nav = NavigationState()
        val home = screen("com.android.settings", el("1", "设置", 260, clickable = false), el("2", "系统管理与升级", 900))
        val sub = screen("com.android.settings", el("1", "系统管理与升级", 260, clickable = false), el("2", "恢复出厂", 900))
        nav.enter(home, "com.android.settings.Settings")
        nav.record(sub, "com.android.settings.Settings", "TAP", "系统管理与升级", changed = true, ok = true)
        assertEquals("设置 > 系统管理与升级", nav.snapshot().path)
        assertEquals("设置", nav.snapshot().parent)

        nav.record(sub, "com.android.settings.Settings", "SCROLL_DOWN", "向下滚动", changed = false, ok = true)
        assertTrue(nav.snapshot().atBottom)

        nav.record(home, "com.android.settings.Settings", "BACK", "返回", changed = true, ok = true)
        val back = nav.snapshot()
        assertEquals(1, back.depth)
        assertTrue(back.triedHere.any { it.contains("系统管理与升级") && it.contains(NavigationState.RETURNED) })
        assertTrue(nav.explored().any { it.contains("系统管理与升级") })
    }

    @Test
    fun pageTitleSkipsStatusBarClock() {
        val obs = screen(
            "com.android.settings",
            ScreenElement("1", "11:04", 158, 76, clickable = false, editable = false, focused = false),
            el("2", "蓝牙", 264, clickable = false),
        )
        assertEquals("蓝牙", MobilePriors.pageTitle(obs))
    }

    @Test
    fun englishPageTitleIsNotSplitOnSpaces() {
        // 文件管理器：左上是汉堡菜单（描述「Show roots」），标题「Files in Downloads」整段才是页面名
        val files = screen(
            "com.google.android.documentsui",
            ScreenElement("1", "Show roots", 80, 230, clickable = true, editable = false, focused = false),
            el("2", "Files in Downloads", 240, clickable = false),
        )
        assertEquals("Files in Downloads", MobilePriors.pageTitle(files))
        // 只整段相等才当工具栏按钮跳过：「Backup」不是「Back」
        val backup = screen("com.android.settings", el("1", "Backup", 240, clickable = false))
        assertEquals("Backup", MobilePriors.pageTitle(backup))
    }

    @Test
    fun taskStateCarriesNavEvenInSingleStepMode() {
        val nav = NavSnapshot("设置 > 蓝牙", 2, "设置", listOf("WLAN 无变化"), atBottom = true)
        val single = MobilePriors.taskState(StepContext("打开蓝牙", "打开蓝牙", "完成", 0, 1, nav = nav))
        assertFalse("current" in single)
        @Suppress("UNCHECKED_CAST")
        assertEquals(true, (single["nav"] as Map<String, Any>)["atBottom"])
        val multi = MobilePriors.taskState(StepContext("g", "第二步", "看到结果", 1, 3, completed = listOf("第一步")))
        assertEquals("2/3", multi["step"])
    }

    @Test
    fun jevProxyParsing() {
        assertEquals("127.0.0.1" to 7897, JevDecisionProvider.parseProxy("127.0.0.1:7897"))
        assertEquals("10.0.2.2" to 8080, JevDecisionProvider.parseProxy("http://10.0.2.2:8080/"))
        assertNull(JevDecisionProvider.parseProxy(""))
        assertNull(JevDecisionProvider.parseProxy("127.0.0.1"))
        assertNull(JevDecisionProvider.parseProxy("user@evil:80"))
        assertNull(JevDecisionProvider.parseProxy("host:99999"))
    }

    @Test
    fun viewClassNamesAreNotActivities() {
        assertNull(MobilePriors.shortActivity("android.widget.FrameLayout"))
        assertEquals("OriginDeviceSettingsActivity", MobilePriors.shortActivity("com.vivo.settings.deviceinfo.OriginDeviceSettingsActivity"))
    }

    @Test
    fun statusBarIslandIsNotATapTargetAndFailedToolsAreHidden() {
        val obs = screen(
            "com.android.settings",
            ScreenElement("1", "电话,8分钟18秒", 720, 76, clickable = true, editable = false, focused = false, system = true),
            el("2", "显示、亮度与护眼", 1500),
        )
        val catalog = Catalogs.build("把屏幕亮度调到最高", obs, apps, failedTools = setOf("set_brightness|{level=max}"))
        assertFalse(catalog.tapTargets.containsKey("1"))
        assertTrue(catalog.tapTargets.containsKey("2"))
        assertTrue(catalog.toolTargets.values.none { it.contains("最高") })
        assertTrue(catalog.toolTargets.values.any { it.contains("最低") })
    }

    @Test
    fun inputBoxIsDescribedAsInputNotAsTheEntryItContains() {
        val search = ScreenElement("1", "关于手机", 700, 535, clickable = true, editable = true, focused = false,
            viewId = "com.android.settings:id/vigour_search_edit")
        val row = el("2", "关于手机", 2000, selectable = true)
        val catalog = Catalogs.build("进入关于手机", screen("com.android.settings", search, row), apps)
        assertTrue(catalog.tapTargets.getValue("1").startsWith("输入框，当前内容：关于手机"))
        assertTrue(catalog.tapTargets.getValue("2").startsWith("关于手机（列表行）"))
    }

    @Test
    fun questionGoalsGetReadToolShortcuts() {
        val labels = DeviceTools.shortcuts("现在电量是多少？", "", apps).map { it.tool }
        assertTrue("read_battery" in labels)
        assertTrue("read_device_info" in DeviceTools.shortcuts("进入关于手机，告诉我安卓版本号", "", apps).map { it.tool })
    }

    @Test
    fun sliderOrSwitchChangeCountsAsScreenChange() {
        val before = screen("com.android.settings", el("1", "亮度", 900, clickable = false, range = 0.41))
        val after = screen("com.android.settings", el("1", "亮度", 900, clickable = false, range = 1.0))
        assertFalse(LoopPolicy.sameScreen(before, after))
        assertTrue(LoopPolicy.sameScreen(before, before))
    }

    @Test
    fun failedToolIsNotSwappedForAnotherLevel() {
        val catalog = Catalogs.build("把屏幕亮度调到最高", screen("com.android.settings"), apps)
        val max = catalog.actions.first { it.operation == "USE_TOOL" && it.label.contains("最高") }
        assertEquals("set_brightness|{level=max}", max.toolKey)
        // 可以换别的工具（打开显示设置页），不能换同一工具的另一档
        val retry = LoopPolicy.retryCandidate(catalog, max, "把屏幕亮度调到最高", emptyList())!!
        assertEquals("open_settings", retry.toolKey?.substringBefore('|'))
    }

    @Test
    fun focusedInputIsNotATapTargetAndSingleTextIgnoresWrongIndex() {
        val search = ScreenElement("2", "搜索设置项", 700, 535, clickable = true, editable = true, focused = true)
        val obs = screen("com.android.settings", search).copy(focusedIndex = "2")
        val catalog = Catalogs.build("在搜索框输入「电池」并搜索", obs, apps)
        assertFalse(catalog.tapTargets.containsKey("2"))
        val d = decisionFromChoice(catalog, "TYPE_TEXT", "2", 1.0, "")
        assertEquals("action", d.status)
        assertEquals("电池", d.action?.params?.get("text"))
    }

    @Test
    fun sliderAtMaxOffersNoMoreMaxAndReadToolDisappearsOnceKnown() {
        val atMax = screen("com.android.settings", el("1", "亮度", 900, clickable = false, range = 1.0))
        val catalog = Catalogs.build("把屏幕亮度调到最高", atMax, apps)
        assertFalse(catalog.sliderTargets.containsKey("1:max"))
        assertTrue(catalog.sliderTargets.containsKey("1:min"))
        assertTrue(Catalogs.stateOf(atMax.elements[0]).contains("已是最大"))

        val q = Catalogs.build("现在电量是多少？", screen("com.bbk.launcher2"), apps)
        assertTrue(q.toolTargets.values.any { it.contains("电量") })
        val known = Catalogs.build("现在电量是多少？", screen("com.bbk.launcher2"), apps, knownFacts = setOf("battery_level"))
        assertTrue(known.toolTargets.values.none { it.contains("电量") })
    }

    @Test
    fun reflectionParsesAndCapsAvoidList() {
        val r = PlanPrompts.parseReflection(
            """好的 {"cause":"长按后出现选择模式，应点删除图标","next":"点工具栏的删除","avoid":["长按「a.md」","长按「b.md」","x","y","z","w"]}""",
        )!!
        assertTrue(r.cause.contains("选择模式"))
        assertEquals("点工具栏的删除", r.next)
        assertEquals(5, r.avoid.size)
        // 原因和下一步都空：不算反思
        assertEquals(null, PlanPrompts.parseReflection("""{"cause":"","next":"","avoid":[]}"""))
        assertEquals(null, PlanPrompts.parseReflection("not json"))
    }

    @Test
    fun reviewPromptAsksForBareAnswer() {
        assertTrue(PlanPrompts.SYSTEM_REVIEW.contains("answer 只写答案本身"))
        val review = PlanPrompts.parseReview("""{"done":true,"reason":"屏幕上有 3 条待办","answer":"3"}""")!!
        assertEquals("3", review.answer)
    }

    @Test
    fun evalModeAcceptsTextReadFromTheScreen() {
        val raw = """{"steps":[{"type":"ui","instruction":"在金额框输入 47.25","done_when":"金额框里是 47.25","text":"47.25"}]}"""
        // 默认：47.25 不在目标里，输入文字被丢掉（防屏幕上的内容让 Agent 打别的字）
        assertEquals(null, PlanPrompts.parsePlan(raw, "Add the expense from the receipt", apps)!!.steps.single().text)
        // 评测模式：可以输入从屏幕读到的内容
        assertEquals("47.25", PlanPrompts.parsePlan(raw, "Add the expense from the receipt", apps, anyText = true)!!.steps.single().text)
    }

    @Test
    fun stepCheckParses() {
        assertEquals(true, PlanPrompts.parseStepCheck("""好的 {"done":true,"reason":"已在 Health 列表页"}""")!!.done)
        assertEquals(false, PlanPrompts.parseStepCheck("""{"done":false,"reason":"还在侧边栏"}""")!!.done)
        assertEquals("3 个待办", PlanPrompts.parseStepCheck("""{"done":true,"note":"3 个待办","reason":"x"}""")!!.note)
        assertEquals(null, PlanPrompts.parseStepCheck("""{"done":"maybe"}"""))
        assertEquals(null, PlanPrompts.parseStepCheck("no json"))
    }

    @Test
    fun replanPromptCarriesReflections() {
        val ctx = ReplanContext("删除笔记", "兜圈", emptyList(), reflections = listOf(Reflection("一直长按", "点删除图标")))
        val text = PlanPrompts.planUser("Delete the note", screen("net.gsantner.markor"), apps, ctx)
        assertTrue(text.contains("失败后的反思"))
        assertTrue(text.contains("点删除图标"))
    }

    @Test
    fun longInputTextIsKeptWhole() {
        // 40 字上限把 MarkorCreateNote 的正文截在 "without break" 上
        val goal = "Create a new note in Markor named fair_apple_V9Ao.txt with the following text: You can't make an omelette without breaking a few eggs."
        val body = "You can't make an omelette without breaking a few eggs."
        val raw = """{"steps":[{"type":"ui","instruction":"在正文输入","done_when":"正文是那句话","text":"$body"}]}"""
        assertEquals(body, PlanPrompts.parsePlan(raw, goal, apps)!!.steps[0].text)
    }

    @Test
    fun horizontalScrollerDetection() {
        val w = 1080
        val h = 2400
        val chips = ElementBounds(0, 420, 1080, 540)
        assertTrue(ScreenObserver.isHorizontalScroller("android.widget.HorizontalScrollView", chips, -1, -1, w, h))
        assertTrue(ScreenObserver.isHorizontalScroller("androidx.recyclerview.widget.RecyclerView", chips, 1, 8, w, h))
        // 又宽又扁、没有集合信息
        assertTrue(ScreenObserver.isHorizontalScroller("android.view.View", chips, -1, -1, w, h))
        // 竖着滚的列表、整屏翻页都不算
        assertFalse(ScreenObserver.isHorizontalScroller("androidx.recyclerview.widget.RecyclerView", ElementBounds(0, 300, 1080, 2200), 20, 1, w, h))
        assertFalse(ScreenObserver.isHorizontalScroller("android.widget.ScrollView", chips, -1, -1, w, h))
        assertFalse(ScreenObserver.isHorizontalScroller("androidx.viewpager.widget.ViewPager", ElementBounds(0, 200, 1080, 2300), -1, -1, w, h))
    }

    @Test
    fun horizontalRowGetsLeftAndRightScroll() {
        val row = ScrollRow(ElementBounds(0, 420, 1080, 540), "Food Income Housing")
        val obs = screen("com.arduia.expense", el("1", "Food", 480)).copy(screenWidth = 1080, screenHeight = 2400, hScrolls = listOf(row))
        val catalog = Catalogs.build("Add an Education expense", obs, apps)
        val right = catalog.resolve("SCROLL_RIGHT", "h1")!!
        // 露出右边的项：手指从右往左滑，只在这一排的高度上
        assertTrue((right.params["x1"] as Int) > (right.params["x2"] as Int))
        assertEquals(480, right.params["y1"])
        assertEquals(480, right.params["y2"])
        val left = catalog.resolve("SCROLL_LEFT", "h1")!!
        assertTrue((left.params["x1"] as Int) < (left.params["x2"] as Int))
        // 只有一排时，模型没写 target 也落到这一排
        assertEquals(right, decisionFromChoice(catalog, "SCROLL_RIGHT", null, 1.0, "").action)
        // 没有横排就不提供横向滚动
        assertNull(Catalogs.build("Add an Education expense", obs.copy(hScrolls = emptyList()), apps).operations["SCROLL_RIGHT"])
    }

    @Test
    fun replanAfterEndsThePlanAtThatStep() {
        val raw = """{"steps":[
            {"type":"ui","instruction":"打开 my_expenses.txt","done_when":"能看到文件内容","replan_after":true},
            {"type":"ui","instruction":"在 Pro Expense 里加一笔","done_when":"列表里有它"}]}"""
        val plan = PlanPrompts.parsePlan(raw, "Log the reimbursable transactions from my_expenses.txt in pro expense", apps)!!
        assertEquals(1, plan.steps.size)
        assertTrue(plan.steps[0].replanAfter)
        // 不写就是 false；写成字符串的不认
        val plain = PlanPrompts.parsePlan("""{"steps":[{"type":"ui","instruction":"a","done_when":"b","replan_after":"yes"}]}""", "x", apps)!!
        assertFalse(plain.steps[0].replanAfter)
        // 接着规划时告诉规划器"不是卡住了"，并带上记下的事实
        val ctx = ReplanContext("打开 my_expenses.txt", "", emptyList(), facts = mapOf("note_1" to "Tuition Fees 160.28 Education"), refresh = true)
        val text = PlanPrompts.planUser("Log the reimbursable transactions", screen("com.arduia.expense"), apps, ctx)
        assertTrue(text.contains("replan_after"))
        assertTrue(text.contains("Tuition Fees 160.28 Education"))
        assertFalse(text.contains("卡住了"))
    }
}
