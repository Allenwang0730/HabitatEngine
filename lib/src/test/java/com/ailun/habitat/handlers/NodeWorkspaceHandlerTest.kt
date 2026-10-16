package com.ailun.habitat.handlers

import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode
import com.ailun.habitat.workspace.WorkspaceStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class NodeWorkspaceHandlerTest {

    private lateinit var dir: File
    private lateinit var store: WorkspaceStore
    private lateinit var handler: NodeWorkspaceHandler

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("wsh").toFile()
        store = WorkspaceStore(dir)
        handler = NodeWorkspaceHandler { store }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun run(ctx: WorkflowContext, vararg params: Pair<String, Any>): WorkflowContext {
        val node = WorkflowNode().apply {
            id = "ws"
            type = "ACTION_WORKSPACE"
            this.params = params.toMap()
        }
        runBlocking { handler.handle(node, ctx) }
        return ctx
    }

    private fun ctx(origin: String = "workflow:测试") = WorkflowContext.detached().also { it.origin = origin }

    @Test
    fun writeUsesContextOriginAsSource() {
        val c = ctx()
        c.putVariable("city", "杭州")
        run(c, "action" to "write", "name" to "天气", "content" to "\${city} 晴", "source" to "agent")
        assertEquals(true, c.getVariable("workspace_success"))
        val entry = store.list().single()
        assertEquals("参数里的 source 不算数，来源只看调用方", "workflow:测试", entry.source)
        assertEquals("杭州 晴", store.readText(entry.id))
        assertEquals(entry.id, c.getVariable("workspace_id"))
    }

    @Test
    fun readAndListOutputs() {
        store.writeText("a", "内容A", "agent", tags = listOf("t"))
        val c = run(ctx(), "action" to "read", "name" to "a", "output_var" to "out")
        assertEquals("内容A", c.getVariable("out"))
        assertEquals("text", c.getVariable("workspace_kind"))

        val l = run(ctx(), "action" to "list", "tag" to "t")
        val json = l.getVariable("workspace_list") as String
        assertTrue(json.contains("\"name\":\"a\""))
        assertTrue("列表不带正文", !json.contains("内容A"))
        assertEquals(1, l.getVariable("workspace_count"))
    }

    @Test
    fun failuresReportErrors() {
        val missing = run(ctx(), "action" to "read", "name" to "没有")
        assertEquals(false, missing.getVariable("workspace_success"))
        assertTrue((missing.getVariable("workspace_error") as String).contains("找不到"))

        val noName = run(ctx(), "action" to "write", "content" to "x")
        assertEquals(false, noName.getVariable("workspace_success"))

        val badJson = run(ctx(), "action" to "write", "name" to "j", "kind" to "json", "content" to "{x")
        assertEquals("内容不是合法 JSON", badJson.getVariable("workspace_error"))

        val badKind = run(ctx(), "action" to "write", "name" to "r", "kind" to "record", "content" to "{}")
        assertEquals("工作流不能写运行记录", false, badKind.getVariable("workspace_success"))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun deleteByIdOrName() {
        val e = store.writeText("x", "1", "agent")
        val c = run(ctx(), "action" to "delete", "id" to e.id)
        assertEquals(true, c.getVariable("workspace_success"))
        assertNull(store.get(e.id))
    }

    @Test
    fun sniffsImageHeaders() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A)
        assertEquals("image/png", NodeWorkspaceHandler.sniffImage(png))
        assertEquals("image/jpeg", NodeWorkspaceHandler.sniffImage(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
        assertNull(NodeWorkspaceHandler.sniffImage("hello".toByteArray()))
    }
}
