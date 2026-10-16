package com.ailun.habitat

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayGuardTest {

    @Test
    fun boxIntersection() {
        val panel = OverlayGuard.Box(100, 100, 500, 400)
        assertTrue(panel.intersects(OverlayGuard.Box(300, 300, 301, 301)))
        assertFalse("右下开区间：贴边不算相交", panel.intersects(OverlayGuard.Box(500, 100, 600, 400)))
        assertFalse(panel.intersects(OverlayGuard.Box(0, 0, 50, 50)))
        assertTrue(panel.intersects(OverlayGuard.Box(495, 50, 496, 51).inflate(60)))
    }

    @Test
    fun noHooksRunsBlockDirectly() = runBlocking {
        // 没有注册任何 Hook 时不切主线程（JVM 单测里没有 Main 调度器，切了会抛异常）
        assertEquals(42, OverlayGuard.aroundGesture(OverlayGuard.Box(0, 0, 1, 1)) { 42 })
        assertEquals("ok", OverlayGuard.aroundCapture { "ok" })
    }
}
