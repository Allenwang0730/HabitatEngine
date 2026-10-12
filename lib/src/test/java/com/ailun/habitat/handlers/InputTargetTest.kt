package com.ailun.habitat.handlers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InputTargetTest {

    @Test
    fun parsesOnlyPlainCoordinates() {
        assertEquals(540 to 2200, InputTarget.parsePoint("540,2200"))
        assertEquals(1 to 2, InputTarget.parsePoint(" 1 , 2 "))
        assertNull(InputTarget.parsePoint(null))
        assertNull(InputTarget.parsePoint("搜索"))
        assertNull(InputTarget.parsePoint("1,2,3"))
        assertNull(InputTarget.parsePoint("a,2"))
    }

    @Test
    fun picksInnermostEditableAtPoint() {
        val outer = intArrayOf(0, 2000, 1080, 2400)
        val inner = intArrayOf(100, 2100, 900, 2300)
        val elsewhere = intArrayOf(0, 0, 1080, 200)
        assertEquals(1, InputTarget.smallestContaining(listOf(outer, inner, elsewhere), 500, 2200))
        assertEquals(0, InputTarget.smallestContaining(listOf(outer, inner, elsewhere), 50, 2050))
        assertEquals(-1, InputTarget.smallestContaining(listOf(outer, inner), 500, 100))
        // 右/下边界不算在内
        assertEquals(-1, InputTarget.smallestContaining(listOf(intArrayOf(0, 0, 10, 10)), 10, 5))
    }

    @Test
    fun readBackIgnoresWhitespaceAndRejectsUnchangedField() {
        assertTrue(InputTarget.applied("13800138000", "138 0013 8000"))
        assertTrue(InputTarget.applied("你好", "你好"))
        assertFalse(InputTarget.applied("你好", ""))
        assertFalse(InputTarget.applied("你好", null))
        assertFalse(InputTarget.applied("你好", "输入消息"))
        assertTrue(InputTarget.applied("", ""))
        assertFalse(InputTarget.applied("", "旧内容"))
    }
}
