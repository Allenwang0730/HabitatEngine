package com.ailun.habitat.app.handlers

import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode
import com.ailun.habitat.app.agent.ScreenObserver
import com.ailun.habitat.app.agent.WeChatOcr
import com.ailun.habitat.app.bridge.AppAccessibilityProvider
import com.ailun.habitat.paramString
import com.google.gson.Gson

/**
 * [ACTION_OCR]：截当前屏幕，用本机 ML Kit（中文模型，离线）识别文字。
 * 给无障碍树读不到字的界面用：图片里的字、游戏、Flutter/Unity、canvas 画的页面。
 *
 * params：`contains`（可选：只保留包含这段文字的块）、`output_var`（默认 ocr_text）
 *
 * 输出：`ocr_success`、`ocr_text`（各块文字按从上到下、从左到右拼接）、`ocr_blocks`（JSON 数组：text、left/top/right/bottom、center_x/center_y，
 * 屏幕像素坐标，可直接给 ACTION_CLICK 的坐标点击）、`ocr_count`、`ocr_error`。
 * 识别在本机完成，不上传。截图前会暂时藏起 Habitat 的小屏，不会把它认进去。
 */
class OcrNodeHandler : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        val outputVar = paramString(node, context, "output_var", "ocr_text")!!.trim().ifEmpty { "ocr_text" }
        context.putVariable("ocr_success", false)
        context.putVariable("ocr_error", null)
        context.putVariable("ocr_count", 0)
        val service = AppAccessibilityProvider.getService() ?: return fail(context, node, "需要开启 Habitat 无障碍服务")
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return fail(context, node, "需要 Android 11 及以上（系统截图接口）")
        if (ScreenObserver.screenState(context.appContext).locked) return fail(context, node, "屏幕已锁定")
        val (width, height) = ScreenObserver.screenSize(context.appContext)
        val blocks = WeChatOcr.readScreen(service, width, height) ?: return fail(context, node, "截图或识别失败（截图过于频繁时系统会拒绝，稍后重试）")
        val filter = paramString(node, context, "contains")?.trim()?.takeIf { it.isNotEmpty() }
        val kept = sortReadingOrder(blocks).filter { filter == null || it.text.contains(filter, ignoreCase = true) }
        context.putVariable(outputVar, kept.joinToString("\n") { it.text })
        context.putVariable("ocr_blocks", Gson().toJson(kept.map(::toMap)))
        context.putVariable("ocr_count", kept.size)
        context.putVariable("ocr_success", true)
        context.log("OCR：识别到 ${blocks.size} 段文字" + (filter?.let { "，其中 ${kept.size} 段包含「$it」" } ?: ""))
        return node.next
    }

    private fun fail(context: WorkflowContext, node: WorkflowNode, reason: String): String? {
        context.putVariable("ocr_error", reason)
        context.log("OCR：$reason")
        return node.next
    }

    companion object {
        private fun sortReadingOrder(blocks: List<WeChatOcr.Block>): List<WeChatOcr.Block> =
            readingOrder(blocks.map { intArrayOf(it.box.left, it.box.top, it.box.right, it.box.bottom) }).map { blocks[it] }

        /**
         * 阅读顺序：先按行从上到下（中心 y 相差不超过半个行高算同一行），行内从左到右。
         * 入参是 [left, top, right, bottom]，返回下标顺序（纯整数，便于 JVM 单测）。
         */
        internal fun readingOrder(boxes: List<IntArray>): List<Int> {
            val centerY = { i: Int -> (boxes[i][1] + boxes[i][3]) / 2 }
            val height = { i: Int -> boxes[i][3] - boxes[i][1] }
            val rows = mutableListOf<MutableList<Int>>()
            for (i in boxes.indices.sortedBy(centerY)) {
                val row = rows.lastOrNull()
                val anchor = row?.first()
                if (anchor != null && kotlin.math.abs(centerY(i) - centerY(anchor)) <= maxOf(height(i), height(anchor)) / 2) row.add(i)
                else rows.add(mutableListOf(i))
            }
            return rows.flatMap { row -> row.sortedBy { boxes[it][0] } }
        }

        private fun toMap(b: WeChatOcr.Block): Map<String, Any> = linkedMapOf(
            "text" to b.text,
            "left" to b.box.left, "top" to b.box.top, "right" to b.box.right, "bottom" to b.box.bottom,
            "center_x" to b.box.centerX(), "center_y" to b.box.centerY(),
        )
    }
}
