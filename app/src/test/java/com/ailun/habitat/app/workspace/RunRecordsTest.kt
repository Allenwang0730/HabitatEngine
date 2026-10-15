package com.ailun.habitat.app.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunRecordsTest {

    @Test
    fun filtersSensitiveAndInternalVariables() {
        val out = RunRecords.filterOutputs(
            mapOf(
                "battery_level" to 80,
                "api_key" to "sk-xxx",
                "AUTH_TOKEN" to "t",
                "user_password" to "p",
                "screenshot_base64" to "iVBOR…",
                "__origin" to "x",
                "empty" to null,
                "long" to "a".repeat(5000),
            ),
        )
        assertEquals(setOf("battery_level", "long"), out.keys)
        assertEquals("80", out["battery_level"])
        assertEquals(RunRecords.MAX_OUTPUT_VALUE, out.getValue("long").length)
    }

    @Test
    fun capsOutputCount() {
        val many = (1..200).associate { "v$it" to it }
        assertEquals(RunRecords.MAX_OUTPUTS, RunRecords.filterOutputs(many).size)
    }

    @Test
    fun jsonRoundTripTrimsLines() {
        val r = RunRecord(
            type = "agent", title = "查电量", status = "done", result = "80%",
            startedAt = 1_000, endedAt = 4_500,
            lines = (1..400).map { "第 $it 行" + "x".repeat(400) },
            meta = mapOf("provider" to "deepseek"),
        )
        val back = RunRecords.parse(RunRecords.toJson(r))!!
        assertEquals("查电量", back.title)
        assertEquals(RunRecords.MAX_LINES, back.lines.size)
        assertTrue(back.lines.all { it.length <= RunRecords.MAX_LINE })
        assertTrue("保留最后的行", back.lines.last().startsWith("第 400 行"))
        assertEquals(3_500, back.durationMs)
        assertTrue(back.ok)
    }

    @Test
    fun parseIsLenient() {
        val minimal = RunRecords.parse("""{"type":"workflow","title":"天气"}""")
        assertNotNull(minimal)
        assertEquals("", minimal!!.status)
        assertTrue(minimal.lines.isEmpty())
        assertTrue(minimal.outputs.isEmpty())
        assertFalse(minimal.ok)
        assertNull(RunRecords.parse("""{"title":"缺类型"}"""))
        assertNull(RunRecords.parse("不是 JSON"))
    }

    @Test
    fun entryNameHasTitleAndTime() {
        val r = RunRecord("agent", "很长的目标".repeat(20), "done", "", 0, 0, emptyList())
        val name = RunRecords.entryName(r)
        assertTrue(name.contains(" · "))
        assertTrue(name.length < 60)
    }
}
