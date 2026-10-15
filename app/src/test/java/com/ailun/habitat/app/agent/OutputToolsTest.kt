package com.ailun.habitat.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** show_on_screen / save_note：只按用户目标原文放行，自由文字有长度上限，规划里不合法就丢掉。 */
class OutputToolsTest {

    private val apps = listOf(AppEntry("设置", "com.android.settings"))

    @Test
    fun gatedByGoalWords() {
        assertNotNull(DeviceTools.resolve("show_on_screen", mapOf("text" to "80%"), "查电量并显示在小屏上", apps))
        assertNotNull(DeviceTools.resolve("save_note", mapOf("text" to "16"), "看下安卓版本号，记下来", apps))
        // 「显示与亮度」「悬浮窗权限」「保存图片」「通话记录」「打开工作区」不能解锁
        assertNull(DeviceTools.resolve("show_on_screen", mapOf("text" to "x"), "打开显示与亮度", apps))
        assertNull(DeviceTools.resolve("show_on_screen", mapOf("text" to "x"), "打开设置里的悬浮窗权限", apps))
        assertNull(DeviceTools.resolve("save_note", mapOf("text" to "x"), "打开相册保存图片", apps))
        assertNull(DeviceTools.resolve("save_note", mapOf("text" to "x"), "看一下通话记录", apps))
        assertNull(DeviceTools.resolve("save_note", mapOf("text" to "x"), "打开 Habitat 的工作区", apps))
        assertNotNull(DeviceTools.resolve("save_note", mapOf("text" to "x"), "把电量存到工作区", apps))
        val ids = DeviceTools.available("打开设置").map { it.id }
        assertFalse("show_on_screen" in ids)
        assertFalse("save_note" in ids)
    }

    @Test
    fun textRequiredAndCapped() {
        val goal = "查电量显示在小屏上"
        assertNull(DeviceTools.resolve("show_on_screen", emptyMap(), goal, apps))
        assertNull(DeviceTools.resolve("show_on_screen", mapOf("text" to "  "), goal, apps))
        assertNull(DeviceTools.resolve("show_on_screen", mapOf("text" to "字".repeat(DeviceTools.MAX_TOOL_TEXT + 1)), goal, apps))
        val inv = DeviceTools.resolve("show_on_screen", mapOf("text" to " 电量 80% ", "title" to "电量"), goal, apps)!!
        assertEquals("ACTION_MINI_SCREEN", inv.node)
        assertEquals("电量 80%", inv.params["text"])
        assertEquals("电量", inv.params["title"])
    }

    @Test
    fun saveNoteWritesUniqueAgentNote() {
        val inv = DeviceTools.resolve("save_note", mapOf("text" to "版本 16"), "看下安卓版本号，记下来", apps)!!
        assertEquals("ACTION_WORKSPACE", inv.node)
        assertEquals("write", inv.params["action"])
        assertEquals(false, inv.params["overwrite"])
        assertEquals("看下安卓版本号，记下来", inv.params["name"])
        assertEquals("agent", inv.params["tags"])
    }

    @Test
    fun fillsFactPlaceholders() {
        val facts = mapOf("battery_level" to "80", "battery_charging" to "false")
        assertEquals("电量 80%，充电 false", DeviceTools.fillFacts("电量 {battery_level}%，充电 {battery_charging}", facts))
        assertEquals("没读到的保留原样", "{unknown} x", DeviceTools.fillFacts("{unknown} x", facts))
        assertEquals("不是读数名的花括号不动", "{ 中文 }", DeviceTools.fillFacts("{ 中文 }", facts))
    }

    @Test
    fun plannerDropsInvalidOutputStepsInsteadOfHandingThemToUi() {
        val raw = """{"steps":[
            {"type":"tool","tool":"read_battery","args":{},"instruction":"读电量","done_when":"读到电量"},
            {"type":"tool","tool":"show_on_screen","args":{"text":"电量 {battery_level}%"},"instruction":"显示到小屏","done_when":"小屏显示电量"}]}"""
        val ok = PlanPrompts.parsePlan(raw, "查电量并显示在小屏上", apps)!!
        assertEquals(listOf("read_battery", "show_on_screen"), ok.steps.map { it.tool })

        // 目标没要求显示：这一步丢掉，不变成"在界面上找小屏"
        val dropped = PlanPrompts.parsePlan(raw, "现在电量多少", apps)!!
        assertEquals(listOf("read_battery"), dropped.steps.map { it.tool })
    }

    @Test
    fun plannerPromptDescribesFreeText() {
        val text = DeviceTools.describeFor("查电量并显示在小屏上")
        assertTrue(text.contains("show_on_screen"))
        assertTrue(text.contains("{读数名}"))
        assertFalse(DeviceTools.describeFor("打开蓝牙").contains("show_on_screen"))
    }
}
