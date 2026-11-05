package com.ailun.habitat.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 混合读屏：只补树里没有的图片文字；噪声丢掉；补进来的不影响"屏幕变没变"。 */
class OcrMergeTest {

    private val card = ScreenElement(
        "1", "", 540, 900, clickable = true, editable = false, focused = false,
        bounds = ElementBounds(40, 600, 1040, 1200),
    )
    private val title = ScreenElement(
        "2", "Receipts", 300, 300, clickable = false, editable = false, focused = false,
        bounds = ElementBounds(40, 260, 560, 340),
    )
    private val tree = ScreenObservation(
        packageName = "com.simplemobiletools.gallery.pro", screenWidth = 1080, screenHeight = 2400,
        elements = listOf(title, card), visibleText = "Receipts", source = "a11y", imageFraction = 0.3,
    )

    private fun block(text: String, l: Int, t: Int, r: Int, b: Int) = ScreenObserver.OcrText(text, ElementBounds(l, t, r, b))

    @Test
    fun addsOnlyNewTextAndDropsNoise() {
        val merged = ScreenObserver.mergeOcr(
            tree,
            listOf(
                block("Receipts", 40, 260, 560, 340), // 树里已有
                block("Total: $42.10", 100, 800, 700, 860), // 图片里的字，在可点卡片内
                block("Bakery Items", 100, 1500, 700, 1560), // 图片里的字，不在可点控件内
                block("12:30", 30, 20, 200, 80), // 状态栏
                block("P:0/1 dX:0.0 dY:0.0", 0, 120, 600, 160), // 指针位置叠加层
            ),
        )
        val added = merged.elements.filter { it.ocr }
        assertEquals(listOf("Total: $42.10", "Bakery Items"), added.map { it.text })
        assertTrue(added[0].clickable)
        assertFalse(added[1].clickable)
        assertEquals(listOf("3", "4"), added.map { it.index })
        assertEquals("a11y+ocr", merged.source)
        assertTrue(merged.visibleText.contains("Total: $42.10"))
    }

    @Test
    fun nothingNewKeepsTheTreeAsIs() {
        val merged = ScreenObserver.mergeOcr(tree, listOf(block("Receipts", 40, 260, 560, 340)))
        assertEquals(tree, merged)
    }

    @Test
    fun ocrJitterDoesNotCountAsAScreenChange() {
        val a = ScreenObserver.mergeOcr(tree, listOf(block("Total: $42.10", 100, 800, 700, 860)))
        val b = ScreenObserver.mergeOcr(tree, listOf(block("Tota1: $42.1O", 100, 800, 700, 860)))
        assertTrue(LoopPolicy.sameScreen(a, b))
        assertTrue(LoopPolicy.sameScreen(a, tree))
        // 树本身变了照样算变化
        assertFalse(LoopPolicy.sameScreen(a, tree.copy(visibleText = "Receipts\nNew", elements = tree.elements + title.copy(index = "3", text = "New"))))
    }

    @Test
    fun ocrTextIsLabelledAndNotUsedForStalenessCheck() {
        val merged = ScreenObserver.mergeOcr(tree, listOf(block("Total: $42.10", 100, 800, 700, 860)))
        val catalog = Catalogs.build("Open the receipt", merged, emptyList())
        val tap = catalog.actions.firstOrNull { it.operation == "TAP" && it.label.contains("Total") }
        if (tap != null) assertEquals(null, tap.matchText)
        assertEquals("图片文字·可点", Catalogs.typeOf(merged.elements.first { it.ocr }))
    }

    @Test
    fun imageFractionClampsToScreen() {
        val f = ScreenObserver.imageFraction(listOf(ElementBounds(-100, 0, 540, 1200)), 1080, 2400)
        assertEquals(0.25, f, 1e-9)
        assertEquals(1.0, ScreenObserver.imageFraction(listOf(ElementBounds(0, 0, 1080, 2400), ElementBounds(0, 0, 1080, 2400)), 1080, 2400), 1e-9)
    }
}
