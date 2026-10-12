package com.ailun.habitat.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 输入链路、拨号/发送门控、固定做法、代码判定的完成条件。 */
class AgentIntentTest {

    private val apps = listOf(
        AppEntry("QQ", "com.tencent.mobileqq"),
        AppEntry("中国移动", "com.greenpoint.android.mc10086.activity"),
        AppEntry("设置", "com.android.settings"),
    )
    private val mobile = Callee("中国移动", "10086")

    private fun el(
        index: String, text: String, y: Int,
        editable: Boolean = false, focused: Boolean = false, clickable: Boolean = true,
    ) = ScreenElement(index, text, 540, y, clickable = clickable, editable = editable, focused = focused)

    /** QQ 聊天页：标题、历史消息、没报告焦点的输入框、发送按钮。 */
    private fun chat(input: String = "", vararg history: String) = ScreenObservation(
        packageName = "com.tencent.mobileqq", screenWidth = 1080, screenHeight = 2400,
        elements = listOf(el("1", "我的电脑", 150)) +
            history.mapIndexed { i, t -> el("${i + 2}", t, 600 + i * 100, clickable = false) } +
            listOf(el("8", input, 2200, editable = true), el("9", "发送", 2200)),
        visibleText = "", source = "a11y",
    )

    @Test
    fun unfocusedSingleInputStillGetsTypeText() {
        val c = Catalogs.build("打开qq给我的电脑发消息说你好", chat(), apps)
        assertEquals("你好", c.textValues["1"])
        val type = c.resolve("TYPE_TEXT", "1")!!
        // 按坐标写入那个输入框，不依赖焦点
        assertEquals("540,2200", type.params["target"])
        // 有字可输时不再把输入框列成点击候选
        assertFalse(c.tapTargets.containsKey("8"))
    }

    @Test
    fun twoUnfocusedInputsAreNotGuessed() {
        val login = ScreenObservation(
            "com.example", 1080, 2400,
            listOf(el("1", "账号", 800, editable = true), el("2", "手机号", 1000, editable = true)),
            "", "a11y",
        )
        val c = Catalogs.build("在输入框输入「abc」", login, emptyList())
        assertTrue(c.textValues.isEmpty())
        assertTrue(c.tapTargets.containsKey("1"))
    }

    @Test
    fun typedTextIsNotOfferedAgainAndSendComesFirst() {
        val c = Catalogs.build("打开qq给我的电脑发消息说你好", chat("你好"), apps, stepText = "你好")
        assertTrue(c.textValues.isEmpty())
        assertEquals("9", c.actions.first { it.operation == "TAP" }.target)
    }

    @Test
    fun sendButtonNeedsTheGoalToAskForIt() {
        val asked = Catalogs.build("打开qq给我的电脑发消息说你好", chat("你好"), apps)
        assertTrue(asked.tapTargets.containsKey("9"))
        assertTrue("PRESS_ENTER" in asked.operations)

        val notAsked = Catalogs.build("打开qq看看我的电脑的聊天记录", chat("你好"), apps)
        assertFalse(notAsked.tapTargets.containsKey("9"))
        // 聊天页回车就是发送：没解锁时也不给回车
        assertFalse("PRESS_ENTER" in notAsked.operations)

        val remote = Catalogs.build("打开qq给我的电脑发消息说你好", chat("你好"), apps, allowGoalUnlock = false)
        assertFalse(remote.tapTargets.containsKey("9"))
        assertFalse("PRESS_ENTER" in remote.operations)
    }

    @Test
    fun callContactIsNotOfferedAsAnApp() {
        val home = ScreenObservation("com.bbk.launcher2", 1080, 2400, emptyList(), "", "a11y")
        val c = Catalogs.build("打电话给中国移动", home, apps, scope = ToolScope(mobile, allowDirectCall = true))
        assertTrue(c.apps.isEmpty())
        val call = c.actions.single { it.operation == "USE_TOOL" }
        assertEquals("ACTION_CALL_DIRECT", call.tool)
        assertEquals("10086", call.params["phone_number"])
        // 规划器提示词里也不再列这个应用
        assertFalse(PlanPrompts.planUser("打电话给中国移动", home, apps, null).contains("目标里点名的已安装应用"))
        // 「打开中国移动」照常能打开应用
        assertEquals(1, Catalogs.build("打开中国移动", home, apps).apps.size)
    }

    @Test
    fun callToolGates() {
        // 没查到号码：没有 call 工具，规划器给的也不认
        assertNull(DeviceTools.resolve("call", emptyMap(), "打电话给中国移动", apps))
        // 目标没写打电话：查到号码也不能拨
        assertNull(DeviceTools.resolve("call", emptyMap(), "打开中国移动", apps, ToolScope(mobile, true)))
        // 远程发起 / 没有拨号权限：只填拨号盘
        assertEquals("ACTION_CALL_PHONE", DeviceTools.resolve("call", emptyMap(), "打电话给中国移动", apps, ToolScope(mobile, false))?.node)
        // 紧急号码永远不自动拨出
        val police = ToolScope(Callee("110", "110"), allowDirectCall = true)
        assertEquals("ACTION_CALL_PHONE", DeviceTools.resolve("call", emptyMap(), "拨打110", apps, police)?.node)
        // 号码不进规划器提示词
        assertFalse(PlanPrompts.planUser("打电话给中国移动", chat(), apps, null, ToolScope(mobile, true)).contains("10086"))
    }

    @Test
    fun skillPlanForCallAndMessage() {
        val call = SkillPlans.build(IntentParser.parse("打电话给中国移动"), "打电话给中国移动", apps, ToolScope(mobile, true), true, true)
        val callPlan = (call as SkillPlans.Result.Ready).plan
        assertEquals("call", callPlan.steps.single().tool)
        assertEquals("", callPlan.note)

        val dialOnly = SkillPlans.build(IntentParser.parse("打电话给中国移动"), "打电话给中国移动", apps, ToolScope(mobile, false), true, false)
        assertTrue((dialOnly as SkillPlans.Result.Ready).plan.note.contains("手动拨出"))

        val unknown = SkillPlans.build(IntentParser.parse("打给王五"), "打给王五", apps, ToolScope(), false, true)
        assertTrue((unknown as SkillPlans.Result.Refuse).reason.contains("通讯录权限"))

        val goal = "打开qq给我的电脑发消息说你好"
        val msg = (SkillPlans.build(IntentParser.parse(goal), goal, apps, ToolScope(), true, true) as SkillPlans.Result.Ready).plan
        assertEquals(listOf("open_app", null, null, null), msg.steps.map { it.tool })
        assertEquals(listOf(null, "我的电脑", "你好", "你好"), msg.steps.map { it.text })
        // 每步的输入都来自目标原文
        msg.steps.mapNotNull { it.text }.forEach { assertTrue(goal.contains(it)) }

        val remote = (SkillPlans.build(IntentParser.parse(goal), goal, apps, ToolScope(), true, false) as SkillPlans.Result.Ready).plan
        assertEquals(3, remote.steps.size)
        assertTrue(remote.note.contains("不自动发送"))

        val empty = SkillPlans.build(IntentParser.parse("打开qq给我的电脑发消息"), "打开qq给我的电脑发消息", apps, ToolScope(), true, true)
        assertTrue(empty is SkillPlans.Result.Refuse)
        // 没说渠道：交给规划器
        assertEquals(SkillPlans.Result.None, SkillPlans.build(IntentParser.parse("给张三发消息说你好"), "给张三发消息说你好", apps, ToolScope(), true, true))
    }

    @Test
    fun stepChecksReadTheScreen() {
        val chatWith = StepCheck.ChatWith("我的电脑")
        assertTrue(chatWith.satisfied(chat()))
        // 搜索页：顶部搜索框里是「我的电脑」，还没进会话
        val search = ScreenObservation(
            "com.tencent.mobileqq", 1080, 2400,
            listOf(el("1", "我的电脑", 150, editable = true), el("2", "我的电脑", 500)), "", "a11y",
        )
        assertFalse(chatWith.satisfied(search))

        assertFalse(StepCheck.InputHas("你好").satisfied(chat("", "你好")))
        assertTrue(StepCheck.InputHas("你好").satisfied(chat("你好")))

        assertFalse(StepCheck.Sent("你好").satisfied(chat("你好")))
        assertTrue(StepCheck.Sent("你好").satisfied(chat("", "你好")))
    }

    @Test
    fun plannerTextStillMustComeFromGoal() {
        val raw = """{"steps":[{"type":"ui","instruction":"输入","done_when":"好了","text":"你好"},
            {"type":"tool","tool":"call","args":{},"instruction":"打电话","done_when":"通话中"}]}"""
        // 没查到号码时 call 工具步退回界面步
        val plan = PlanPrompts.parsePlan(raw, "打开qq给我的电脑发消息说你好", apps)!!
        assertEquals("你好", plan.steps[0].text)
        assertNull(plan.steps[1].tool)
        assertNotNull(PlanPrompts.parsePlan(raw, "打电话给中国移动", apps, ToolScope(mobile, true))!!.steps[1].tool)
    }
}
