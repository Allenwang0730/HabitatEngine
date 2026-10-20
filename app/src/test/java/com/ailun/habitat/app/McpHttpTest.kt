package com.ailun.habitat.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpHttpTest {

    @Test
    fun negotiatesProtocolVersion() {
        assertEquals("2025-03-26", McpHttp.negotiate("2025-03-26"))
        assertEquals("2024-11-05", McpHttp.negotiate("2024-11-05"))
        assertEquals("不认识的版本回最新版", McpHttp.LATEST, McpHttp.negotiate("2099-01-01"))
        assertEquals(McpHttp.LATEST, McpHttp.negotiate(null))
    }

    @Test
    fun originCheckBlocksBrowsersOnOtherSites() {
        assertTrue("命令行客户端不带 Origin", McpHttp.originAllowed(null))
        assertTrue(McpHttp.originAllowed(""))
        assertTrue(McpHttp.originAllowed("http://localhost:6274"))
        assertTrue(McpHttp.originAllowed("http://127.0.0.1:3000"))
        assertFalse("DNS 重绑定：别的网站的页面", McpHttp.originAllowed("https://evil.example.com"))
        assertFalse(McpHttp.originAllowed("null"))
        assertFalse(McpHttp.originAllowed("http://192.168.1.5"))
    }

    @Test
    fun splitsSingleAndBatch() {
        assertTrue(McpHttp.split("""{"jsonrpc":"2.0","id":1,"method":"ping"}""") is McpHttp.Body.Single)
        val batch = McpHttp.split("""[{"jsonrpc":"2.0","id":1,"method":"ping"},{"jsonrpc":"2.0","method":"notifications/initialized"}]""")
        assertEquals(2, (batch as McpHttp.Body.Batch).messages.size)
        assertEquals("Parse error", (McpHttp.split("{坏") as McpHttp.Body.Invalid).reason)
        assertEquals("Invalid Request", (McpHttp.split("[]") as McpHttp.Body.Invalid).reason)
        assertEquals("Invalid Request", (McpHttp.split("42") as McpHttp.Body.Invalid).reason)
    }

    @Test
    fun batchRepliesSkipNotifications() {
        assertNull("全是通知 → 202", McpHttp.joinReplies(listOf(null, null)))
        assertEquals("""[{"id":1}]""", McpHttp.joinReplies(listOf("""{"id":1}""", null)))
    }
}
