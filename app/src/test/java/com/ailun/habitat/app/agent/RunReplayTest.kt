package com.ailun.habitat.app.agent

import com.ailun.habitat.HabitatJson
import com.ailun.habitat.app.workspace.RunRecord
import com.ailun.habitat.app.workspace.RunRecords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunReplayTest {

    private val actions = listOf(
        RecordedAction("ACTION_LAUNCH_APP", mapOf("intent_action" to "android.settings.SETTINGS"), "直接打开「设置首页」设置页", successFlag = "launch_success"),
        RecordedAction("ACTION_GET_BATTERY", emptyMap(), "读取电量", successFlag = "battery_success"),
        RecordedAction("ACTION_CLICK", mapOf("target" to "720,1150"), "点击「关于手机」(列表行)", matchText = "关于手机", successFlag = "click_success"),
        RecordedAction("ACTION_CLICK", mapOf("target" to "100,200"), "点击图标", successFlag = "click_success"),
        RecordedAction("ACTION_MINI_SCREEN", mapOf("text" to "x"), "显示到小屏", successFlag = "mini_screen_success"),
    )

    @Test
    fun buildsParseableWorkflowWithStopOnError() {
        val json = RunReplay.toWorkflowJson(actions)!!
        val graph = HabitatJson.fromJson(json)
        val nodes = graph.nodes!!
        assertEquals("s1", graph.startNodeId)
        // 读电量、写小屏被去掉：3 个动作 + 2 个间隔
        assertEquals(listOf("ACTION_LAUNCH_APP", "ACTION_DELAY", "ACTION_CLICK", "ACTION_DELAY", "ACTION_CLICK"), walk(graph))
        val first = nodes.getValue("s1")
        assertEquals("stop", first.params!!["on_error"])
        assertEquals("launch_success", first.params!!["require"])
        assertTrue("结构检查通过", HabitatJson.lint(graph, null).none { it.severity == "error" })
    }

    @Test
    fun clicksUseTextWhenAvailable() {
        assertEquals("关于手机", RunReplay.paramsFor(actions[2])["target"])
        assertEquals("没有文字才用坐标", "100,200", RunReplay.paramsFor(actions[3])["target"])
    }

    @Test
    fun nothingReplayableGivesNull() {
        assertNull(RunReplay.toWorkflowJson(listOf(actions[1], actions[4])))
        assertNull(RunReplay.toWorkflowJson(emptyList()))
    }

    @Test
    fun actionsSurviveRecordRoundTrip() {
        val r = RunRecord("agent", "打开关于手机", "done", "完成", 0, 1, emptyList(), actions = actions)
        val back = RunRecords.parse(RunRecords.toJson(r))!!
        assertEquals(actions.size, back.actions.size)
        assertEquals("关于手机", back.actions[2].matchText)
        // 旧记录没有 actions 字段
        assertTrue(RunRecords.parse("""{"type":"agent","title":"旧"}""")!!.actions.isEmpty())
    }

    private fun walk(graph: com.ailun.habitat.WorkflowGraph): List<String> {
        val out = mutableListOf<String>()
        var id: String? = graph.startNodeId
        while (id != null) {
            val n = graph.nodes!!.getValue(id)
            out += n.type!!
            id = n.next
        }
        return out
    }
}
