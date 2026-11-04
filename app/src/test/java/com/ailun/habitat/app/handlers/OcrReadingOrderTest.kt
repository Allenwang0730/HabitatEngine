package com.ailun.habitat.app.handlers

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrReadingOrderTest {

    @Test
    fun sortsRowsTopToBottomThenLeftToRight() {
        val boxes = listOf(
            intArrayOf(600, 100, 800, 140), // 0 右上
            intArrayOf(100, 300, 300, 340), // 1 第二行
            intArrayOf(100, 104, 300, 144), // 2 左上：和「右上」差 4px，算同一行
        )
        assertEquals(listOf(2, 0, 1), OcrNodeHandler.readingOrder(boxes))
    }

    @Test
    fun emptyInput() {
        assertEquals(emptyList<Int>(), OcrNodeHandler.readingOrder(emptyList()))
    }
}
