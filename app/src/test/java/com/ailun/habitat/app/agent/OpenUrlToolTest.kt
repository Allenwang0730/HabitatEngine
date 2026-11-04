package com.ailun.habitat.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** open_url：网址只能来自用户目标原文，规划器不能自己挑网站。 */
class OpenUrlToolTest {

    @Test
    fun findsUrlsInGoal() {
        assertEquals(listOf("https://www.baidu.com"), DeviceTools.urlsIn("用浏览器打开 www.baidu.com"))
        assertEquals(listOf("https://example.com/a?b=1"), DeviceTools.urlsIn("打开https://example.com/a?b=1看看"))
        assertEquals(listOf("https://github.com/x/y"), DeviceTools.urlsIn("打开 github.com/x/y。"))
        assertEquals(emptyList<String>(), DeviceTools.urlsIn("打开设置进入蓝牙页面"))
        assertEquals(emptyList<String>(), DeviceTools.urlsIn("打开 javascript:alert(1)"))
    }

    @Test
    fun availableOnlyWhenGoalHasUrl() {
        assertTrue("open_url" in DeviceTools.available("打开 www.baidu.com").map { it.id })
        assertFalse("open_url" in DeviceTools.available("打开百度").map { it.id })
    }

    @Test
    fun urlMustMatchGoal() {
        val goal = "用浏览器打开 www.baidu.com 搜索天气"
        val inv = DeviceTools.resolve("open_url", mapOf("url" to "https://www.baidu.com"), goal, emptyList())
        assertNotNull(inv)
        assertEquals("ACTION_OPEN_URL", inv!!.node)
        assertEquals("https://www.baidu.com", inv.params["url"])
        // 不写协议也等价
        assertNotNull(DeviceTools.resolve("open_url", mapOf("url" to "www.baidu.com"), goal, emptyList()))
        // 目标里没有的网站、换了路径、其它协议都拒绝
        assertNull(DeviceTools.resolve("open_url", mapOf("url" to "https://evil.example.com"), goal, emptyList()))
        assertNull(DeviceTools.resolve("open_url", mapOf("url" to "https://www.baidu.com/s?wd=x"), goal, emptyList()))
        assertNull(DeviceTools.resolve("open_url", mapOf("url" to "javascript:alert(1)"), goal, emptyList()))
        assertNull(DeviceTools.resolve("open_url", emptyMap(), goal, emptyList()))
    }
}
