package com.ailun.habitat.app.agent

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
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

    @Test
    fun catalogOmitsPaymentUnlessGoalAsks() {
        val plain = Catalogs.build("打开设置", screen(), emptyList())
        assertTrue(plain.tapTargets.values.none { it.contains("支付") })
        assertTrue(plain.tapTargets.values.contains("设置"))

        val pay = Catalogs.build("去确认支付", screen(), emptyList())
        assertTrue(pay.tapTargets.values.contains("确认支付"))
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
    fun quotedTextBecomesTypeCandidateOnlyWhenFocused() {
        val values = Catalogs.textCandidates("在搜索框输入「你好」然后发送")
        assertEquals(listOf("你好"), values)
        val catalog = Catalogs.build("在搜索框输入「你好」", screen(), emptyList())
        assertEquals("你好", catalog.textValues["1"])
        val unfocused = Catalogs.build("在搜索框输入「你好」", screen().copy(focusedIndex = null), emptyList())
        assertTrue(unfocused.textValues.isEmpty())
    }

    @Test
    fun remoteGoalCannotUnlockSensitiveTap() {
        val remote = Catalogs.build("去确认支付", screen(), emptyList(), allowGoalUnlock = false)
        assertTrue(remote.tapTargets.values.none { it.contains("支付") })
        assertTrue(remote.tapTargets.values.contains("设置"))
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
        assertEquals("允许", Catalogs.build("给地图授权定位并允许", dialog, emptyList()).tapTargets["1"])
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
        assertEquals(40, obs.elements.size)
        assertEquals((1..40).map { it.toString() }, obs.elements.map { it.index })
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
        val d = parseJevAnswer(
            """{"answers":{"operation":{"type":"choice","choice":"TAP","confidence":0.8},"tap_target":{"choice":"1"}}}""",
            catalog,
        )
        assertEquals("action", d.status)
        assertEquals("设置", d.action?.matchText)
        assertEquals(0.8, d.confidence, 1e-9)
    }

    @Test
    fun modelDecisionWithNullTargetDoesNotThrow() {
        val catalog = Catalogs.build("打开设置", screen(), emptyList())
        assertEquals("error", parseModelDecision("""{"operation":"TAP","target":null}""", catalog).status)
        assertEquals("action", parseModelDecision("""好的 {"operation":"BACK"}""", catalog).status)
    }
}