package com.ailun.habitat.app.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class PlanChecklistTest {

    private fun step(text: String, tool: String? = null) = PlanStep(text, "完成", tool = tool)

    @Test
    fun replanKeepsDoneItemsAndMarksByOffset() {
        var items = PlanChecklist.replace(emptyList(), listOf(step("读电量", "read_battery"), step("进设置"), step("找关于手机")))
        items = PlanChecklist.mark(items, 0, 0, PlanStatus.RUNNING)
        items = PlanChecklist.mark(items, 0, 0, PlanStatus.DONE)
        items = PlanChecklist.mark(items, 0, 1, PlanStatus.FAILED)
        assertEquals(listOf(PlanStatus.DONE, PlanStatus.FAILED, PlanStatus.PENDING), items.map { it.status })
        assertEquals(true, items[0].tool)

        // 重规划：已完成的「读电量」留下，其余换成新计划
        val replanned = PlanChecklist.replace(items, listOf(step("搜索关于手机"), step("点进去")))
        assertEquals(listOf("读电量", "搜索关于手机", "点进去"), replanned.map { it.text })
        val offset = replanned.size - 2
        val running = PlanChecklist.mark(replanned, offset, 0, PlanStatus.RUNNING)
        assertEquals(PlanStatus.RUNNING, running[1].status)
        assertEquals("越界不动", running, PlanChecklist.mark(running, offset, 5, PlanStatus.DONE))
    }

    @Test
    fun finishSettlesRunningItem() {
        val items = listOf(PlanItem("a", PlanStatus.DONE), PlanItem("b", PlanStatus.RUNNING), PlanItem("c", PlanStatus.PENDING))
        assertEquals(PlanStatus.DONE, PlanChecklist.finish(items, done = true)[1].status)
        assertEquals(PlanStatus.FAILED, PlanChecklist.finish(items, done = false)[1].status)
        assertEquals(PlanStatus.PENDING, PlanChecklist.finish(items, done = true)[2].status)
    }
}
