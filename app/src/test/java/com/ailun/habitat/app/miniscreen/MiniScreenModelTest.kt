package com.ailun.habitat.app.miniscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniScreenModelTest {

    private fun write(
        text: String? = null,
        append: Boolean = false,
        source: String = "workflow:天气",
        run: String = "r1",
        title: String? = null,
        image: MiniImage? = null,
        progress: Int? = null,
    ) = MiniCommand.Write(append, source, run, title, text, image, progress)

    private fun reduce(state: MiniScreenState, cmd: MiniCommand, now: Long = 1_000) = MiniScreenModel.reduce(state, cmd, now)

    private fun texts(s: MiniScreenState) = s.blocks.filterIsInstance<MiniBlock.Text>().map { it.text }

    @Test
    fun showReplacesAndAppendAdds() {
        var s = reduce(MiniScreenState(), write("第一段", title = "天气")).state
        assertTrue(s.visible)
        assertEquals("天气", s.title)
        s = reduce(s, write("第二段", append = true)).state
        assertEquals(listOf("第一段", "第二段"), texts(s))
        assertEquals("追加不改标题", "天气", s.title)
        s = reduce(s, write("新内容")).state
        assertEquals(listOf("新内容"), texts(s))
        assertEquals("替换且没给标题时清空标题", "", s.title)
    }

    @Test
    fun appendFromAnotherSourceReplaces() {
        var s = reduce(MiniScreenState(), write("本机内容", title = "本机")).state
        s = reduce(s, write("远程内容", append = true, source = "mcp", run = "mcp")).state
        assertEquals(listOf("远程内容"), texts(s))
        assertEquals("mcp", s.source)
        assertEquals("来源换了，旧标题不能留在远程内容上", "", s.title)
    }

    @Test
    fun keepsAtMostMaxBlocksAndReportsDroppedImages() {
        var state = reduce(MiniScreenState(), write(image = MiniImage("img-a", 1, 1))).state
        val dropped = mutableListOf<String>()
        repeat(MiniScreenModel.MAX_BLOCKS) {
            val r = reduce(state, write("x$it", append = true))
            dropped += r.droppedImages
            state = r.state
        }
        assertEquals(MiniScreenModel.MAX_BLOCKS, state.blocks.size)
        assertEquals("x${MiniScreenModel.MAX_BLOCKS - 1}", texts(state).last())
        // 图片在最早的那块，被挤掉时要报告，调用方回收位图
        assertEquals(listOf("img-a"), dropped)
    }

    @Test
    fun replacingReportsOldImages() {
        val s = reduce(MiniScreenState(), write(image = MiniImage("old", 1, 1))).state
        val r = reduce(s, write("文字"))
        assertEquals(listOf("old"), r.droppedImages)
    }

    @Test
    fun clipsLongTextAndTitle() {
        val s = reduce(MiniScreenState(), write("字".repeat(5000), title = "标".repeat(100) + "\n多余")).state
        val text = texts(s).single()
        assertEquals(MiniScreenModel.MAX_TEXT, text.length)
        assertTrue(text.endsWith("…"))
        assertEquals(MiniScreenModel.MAX_TITLE, s.title.length)
        assertFalse(s.title.contains('\n'))
    }

    @Test
    fun dismissSuppressesSameRunOnly() {
        var s = reduce(MiniScreenState(), write("a", run = "r1")).state
        s = reduce(s, MiniCommand.Dismiss, now = 2_000).state
        assertFalse(s.visible)

        val same = reduce(s, write("b", run = "r1"), now = 3_000)
        assertTrue(same.suppressed)
        assertFalse(same.state.visible)
        assertEquals("内容照样更新", listOf("b"), texts(same.state))

        val other = reduce(same.state, write("c", run = "r2"), now = 4_000)
        assertFalse(other.suppressed)
        assertTrue(other.state.visible)
        assertNull(other.state.dismissedRunKey)
    }

    @Test
    fun dismissExpires() {
        var s = reduce(MiniScreenState(), write("a", source = "mcp", run = "mcp")).state
        s = reduce(s, MiniCommand.Dismiss, now = 0).state
        assertTrue(reduce(s, write("b", source = "mcp", run = "mcp"), now = 60_000).suppressed)
        assertFalse(reduce(s, write("b", source = "mcp", run = "mcp"), now = MiniScreenModel.DISMISS_TTL_MS + 1).suppressed)
    }

    @Test
    fun hideKeepsContentClearDropsIt() {
        var s = reduce(MiniScreenState(), write("a", image = MiniImage("i", 1, 1))).state
        s = reduce(s, MiniCommand.Hide).state
        assertFalse(s.visible)
        assertEquals(2, s.blocks.size)
        s = reduce(s, write("b", append = true)).state
        assertTrue(s.visible)
        assertEquals(listOf("a", "b"), texts(s))

        val cleared = reduce(s, MiniCommand.Clear)
        assertTrue(cleared.state.blocks.isEmpty())
        assertFalse(cleared.state.visible)
        assertEquals(listOf("i"), cleared.droppedImages)
    }

    @Test
    fun progressKeepsBlocksOfSameSourceAndClamps() {
        var s = reduce(MiniScreenState(), write("下载中", title = "下载")).state
        s = reduce(s, MiniCommand.Progress("workflow:天气", "r1", 150)).state
        assertEquals(100, s.progress)
        assertEquals(listOf("下载中"), texts(s))
        assertEquals("下载", s.title)

        val other = reduce(s, MiniCommand.Progress("agent", "a1", 30, "任务")).state
        assertTrue("别的来源只更新进度时不能沿用旧正文", other.blocks.isEmpty())
        assertEquals("任务", other.title)
        assertEquals(30, other.progress)
    }

    @Test
    fun sourceLabels() {
        assertEquals("Agent", MiniScreenModel.sourceLabel("agent"))
        assertEquals("MCP", MiniScreenModel.sourceLabel("mcp"))
        assertEquals("天气", MiniScreenModel.sourceLabel("workflow:天气"))
        assertEquals("工作流", MiniScreenModel.sourceLabel("workflow:"))
        assertEquals("Habitat", MiniScreenModel.sourceLabel(""))
        assertEquals("Agent · 远程", MiniScreenModel.sourceLabel("agent:remote"))
        assertTrue(MiniScreenModel.isRemote("mcp"))
        assertTrue(MiniScreenModel.isRemote("agent:remote"))
        assertFalse(MiniScreenModel.isRemote("agent"))
        assertFalse(MiniScreenModel.isRemote("workflow:remote 工具"))
    }

    @Test
    fun dodgeMovesAwayFromGesture() {
        // 屏幕 2400 高，状态栏 80，小屏在顶部 100..500
        // 点在 300 → 挪到底部
        assertEquals(2400 - 400 - 16, MiniScreenModel.dodge(100, 400, 290, 310, 2400, 80, 16))
        // 点在底部 2200 → 留在顶部（离原位置最近）
        assertEquals(96, MiniScreenModel.dodge(1900, 400, 2190, 2210, 2400, 80, 16))
        // 从上到下的整屏滑动：哪都放不下
        assertNull(MiniScreenModel.dodge(100, 400, 200, 2300, 2400, 80, 16))
    }

    @Test
    fun parsesPosition() {
        assertEquals(MiniPosition.TOP, MiniPosition.parse(null))
        assertEquals(MiniPosition.CENTER, MiniPosition.parse("center"))
        assertEquals(MiniPosition.BOTTOM, MiniPosition.parse("底部"))
        assertEquals(MiniPosition.TOP, MiniPosition.parse("left"))
    }
}
