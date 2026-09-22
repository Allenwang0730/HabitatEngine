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
}