package com.ailun.habitat.handlers

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import android.util.Base64
import android.util.Log
import com.ailun.habitat.INodeHandler
import com.ailun.habitat.OverlayGuard
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode
import com.ailun.habitat.api.IShellExecutor
import com.ailun.habitat.paramInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

class NodeScreenshotHandler(
    private val shellExecutor: IShellExecutor? = null
) : INodeHandler {

    /** 执行 screencap；失败返回错误文案，成功返回 null。 */
    private suspend fun capture(outFile: File, context: WorkflowContext): String? {
        if (shellExecutor != null) {
            val cmd = "screencap -p ${outFile.absolutePath}"
            context.log("Screenshot: exec '$cmd'")
            val result = shellExecutor.exec(cmd, asRoot = false)
            context.log("Screenshot: shell result='${result.take(200)}' (len=${result.length})")

            if (result.contains("Error", ignoreCase = true) ||
                result.contains("denied", ignoreCase = true) ||
                result.contains("not found", ignoreCase = true)
            ) {
                Log.e(TAG, "Screenshot shell error: $result")
                return "Shell: $result"
            }
        } else {
            context.log("Screenshot: no shell executor, trying Runtime.exec")
            val out = LocalShell.run(arrayOf("screencap", "-p", outFile.absolutePath), 15_000)
            if (out.startsWith("Error:")) {
                context.log("Screenshot: local exec failed: ${out.take(200)}")
                return "exec: $out"
            }
            if (out.isNotBlank()) context.log("Screenshot: output='${out.take(200)}'")
        }
        return null
    }

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        val params = node.params ?: return node.next

        val outputVar = params["output_var"]?.toString()?.trim()?.ifEmpty { null }
        val format = params["format"]?.toString()?.trim()?.lowercase() ?: "base64"
        val quality = paramInt(node, context, "quality", 80).coerceIn(1, 100)
        context.putVariable("screenshot_success", false)

        // Use a world-writable path so the shell process (uid=2000) can write to it.
        // App private dirs like cacheDir are NOT writable by shell.
        val fileName = "habitat_screenshot_${System.currentTimeMillis()}.png"
        val extDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
        if (!extDir.exists()) extDir.mkdirs()
        val outFile = File(extDir, fileName)
        context.log("Screenshot: output=$outFile")

        try {
            // 宿主的悬浮窗（小屏）截图前先藏起来，不把它截进去
            val captureError: String? = OverlayGuard.aroundCapture { capture(outFile, context) }
            if (captureError != null) {
                context.putVariable("screenshot_success", false)
                context.putVariable("screenshot_error", captureError)
                return node.next
            }

            // Small delay for file system sync
            kotlinx.coroutines.delay(200)

            val decoded = withContext(Dispatchers.IO) { decode(outFile, format, quality) }
            if (decoded == null) {
                Log.e(TAG, "Screenshot: no output file at $outFile")
                context.putVariable("screenshot_error", "文件未生成 ($outFile)")
                return node.next
            }
            val (width, height, result) = decoded
            context.log("Screenshot: decoded ${width}x${height}")
            context.putVariable("screenshot_width", width)
            context.putVariable("screenshot_height", height)

            context.putVariable(outputVar ?: "screenshot_base64", result)
            context.putVariable("screenshot_success", true)
            context.log("Screenshot: success, ${width}x${height}, format=$format")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Screenshot failed: ${e.message}", e)
            context.putVariable("screenshot_success", false)
            context.putVariable("screenshot_error", e.message ?: "Unknown")
            context.log("Screenshot: exception ${e.message}")
        }

        return node.next
    }

    /** 读取并编码截图文件；文件不存在/为空返回 null。 */
    private fun decode(outFile: File, format: String, quality: Int): Triple<Int, Int, String>? {
        val exists = outFile.exists()
        val size = if (exists) outFile.length() else -1L
        if (!exists || size == 0L) return null

        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(outFile.absolutePath, options)
        val width = options.outWidth
        val height = options.outHeight

        val result: String = when (format) {
            "file" -> outFile.absolutePath
            else -> {
                val bitmap = BitmapFactory.decodeFile(outFile.absolutePath)
                if (bitmap != null) {
                    val baos = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
                    val bytes = baos.toByteArray()
                    val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    bitmap.recycle()
                    baos.close()
                    base64
                } else {
                    val bytes = outFile.readBytes()
                    Base64.encodeToString(bytes, Base64.NO_WRAP)
                }
            }
        }
        return Triple(width, height, result)
    }

    companion object {
        private const val TAG = "HabitatScreenshot"
    }
}
