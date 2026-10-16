package com.ailun.habitat.app.handlers

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode
import com.ailun.habitat.app.miniscreen.MiniCommand
import com.ailun.habitat.app.miniscreen.MiniImage
import com.ailun.habitat.app.miniscreen.MiniPosition
import com.ailun.habitat.app.miniscreen.MiniScreen
import com.ailun.habitat.handlers.FilePathGuard
import com.ailun.habitat.paramIntOrNull
import com.ailun.habitat.paramLong
import com.ailun.habitat.paramString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * [ACTION_MINI_SCREEN]：悬浮小屏，给用户实时显示文字、图片、进度。
 *
 * params：
 * - `action`：show（替换内容，默认）/ append（追加一段）/ progress（只更新进度）/ hide（隐藏，保留内容）/ clear（隐藏并清空）
 * - `title`、`text`（支持 `${var}`，最多 4000 字）
 * - `image`：图片文件路径（经路径守卫）或 base64（可带 `data:image/...;base64,` 前缀）
 * - `progress`：0–100
 * - `position`：top / center / bottom（用户拖动过就按用户的位置）
 * - `duration_ms`：大于 0 时到时自动隐藏
 *
 * 输出：`mini_screen_success`、`mini_screen_shown`（false = 用户本次运行已关掉小屏，只更新了内容）、`mini_screen_error`。
 * 来源标签取自 [WorkflowContext.origin]，不能由参数指定。
 */
class MiniScreenNodeHandler : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        context.putVariable("mini_screen_success", false)
        context.putVariable("mini_screen_shown", false)
        context.putVariable("mini_screen_error", null)
        val action = paramString(node, context, "action", "show")!!.trim().lowercase()
        val source = context.origin.ifEmpty { "workflow:" }
        val runKey = if (context.origin == WorkflowContext.ORIGIN_MCP) "mcp" else context.effectiveRunKey
        val title = paramString(node, context, "title")
        val text = paramString(node, context, "text")
        val progress = paramIntOrNull(node, context, "progress")
        val position = node.params?.get("position")?.let { MiniPosition.parse(paramString(node, context, "position")) }

        var bitmap: Bitmap? = null
        val command: MiniCommand = when (action) {
            "show", "append" -> {
                val imageParam = paramString(node, context, "image")?.trim()?.takeIf { it.isNotEmpty() }
                if (imageParam != null) {
                    bitmap = decodeImage(context, imageParam) ?: return fail(context, node, "图片无法读取或解码")
                }
                if (text.isNullOrEmpty() && bitmap == null && title.isNullOrEmpty() && progress == null) {
                    return fail(context, node, "没有要显示的内容（text / image / title / progress 至少给一个）")
                }
                MiniCommand.Write(
                    append = action == "append",
                    source = source,
                    runKey = runKey,
                    title = title,
                    text = text,
                    image = bitmap?.let { MiniImage(UUID.randomUUID().toString(), it.width, it.height) },
                    progress = progress,
                    position = position,
                )
            }
            "progress" -> MiniCommand.Progress(
                source, runKey,
                progress ?: return fail(context, node, "progress 需要 0–100 的数字"),
                title,
            )
            "hide" -> MiniCommand.Hide
            "clear" -> MiniCommand.Clear
            else -> return fail(context, node, "未知 action: $action（show/append/progress/hide/clear）")
        }

        val autoHide = paramLong(node, context, "duration_ms", 0L).coerceIn(0L, 24 * 3600_000L)
        val result = MiniScreen.apply(context.appContext, command, bitmap, autoHide)
        context.putVariable("mini_screen_success", result.ok)
        context.putVariable("mini_screen_shown", result.shown)
        if (result.error != null) {
            context.putVariable("mini_screen_error", result.error)
            context.log("小屏：${result.error}")
        } else if (!result.shown && command is MiniCommand.Write) {
            context.log("小屏：用户已关闭本次运行的小屏，只更新了内容")
        }
        return node.next
    }

    private fun fail(context: WorkflowContext, node: WorkflowNode, reason: String): String? {
        context.putVariable("mini_screen_error", reason)
        context.log("小屏：$reason")
        return node.next
    }

    /** 路径（经 [FilePathGuard]）或 base64；按宽度缩到不超过 [MAX_WIDTH]。 */
    private suspend fun decodeImage(context: WorkflowContext, value: String): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val bytes: ByteArray? = when {
                value.startsWith("data:") -> Base64.decode(value.substringAfter(','), Base64.DEFAULT)
                value.startsWith("/") -> {
                    val reason = FilePathGuard.check(context.appContext, value, FilePathGuard.Access.READ)
                    if (reason != null) {
                        context.log("小屏：$reason")
                        null
                    } else {
                        File(value).takeIf { it.isFile && it.length() <= MAX_BYTES }?.readBytes()
                    }
                }
                value.length <= MAX_BYTES * 4 / 3 + 4 -> Base64.decode(value, Base64.DEFAULT)
                else -> null
            }
            bytes?.let(::decodeScaled)
        }.getOrNull()
    }

    private fun decodeScaled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_WIDTH) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        if (decoded.width <= MAX_WIDTH) return decoded
        val height = (decoded.height * MAX_WIDTH.toFloat() / decoded.width).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(decoded, MAX_WIDTH, height, true).also { if (it !== decoded) decoded.recycle() }
    }

    private companion object {
        const val MAX_WIDTH = 1080
        const val MAX_BYTES = 12L * 1024 * 1024
    }
}
