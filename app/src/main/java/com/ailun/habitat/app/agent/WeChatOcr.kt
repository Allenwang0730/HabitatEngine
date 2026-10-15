package com.ailun.habitat.app.agent

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import androidx.annotation.RequiresApi
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * 无障碍树读不到文字时的离线 OCR 兜底（微信前台、或任意 App 树文字不足）。
 * 只在读屏协程调用，限频 + 指数退避，不把识别文字打进日志。
 */
object WeChatOcr {
    private val executor = Executors.newSingleThreadExecutor()
    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }
    @Volatile private var nextAllowedAt = 0L
    @Volatile private var backoffMs = 1_000L

    suspend fun recognize(
        service: AccessibilityService,
        screenWidth: Int,
        screenHeight: Int,
        packageName: String = ScreenObserver.WECHAT,
    ): ScreenObservation? {
        val now = System.currentTimeMillis()
        if (now < nextAllowedAt) return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        // 小屏截图前先藏起来：别把它认成屏幕上的文字
        val bitmap = com.ailun.habitat.OverlayGuard.aroundCapture { capture(service) } ?: run {
            backoff()
            return null
        }
        return try {
            val result = suspendCancellableCoroutine { cont ->
                recognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                    .addOnFailureListener { if (cont.isActive) cont.resume(null) }
            }
            if (result == null) {
                backoff()
                return null
            }
            backoffMs = 1_000L
            nextAllowedAt = System.currentTimeMillis() + 1_000L
            if (result.textBlocks.isEmpty()) return null
            val scaleX = if (bitmap.width > 0) screenWidth.toFloat() / bitmap.width else 1f
            val scaleY = if (bitmap.height > 0) screenHeight.toFloat() / bitmap.height else 1f
            val elements = result.textBlocks.mapNotNull { block ->
                val box = block.boundingBox ?: return@mapNotNull null
                val text = block.text.trim()
                if (text.isEmpty()) null else box to text
            }.take(24).mapIndexed { i, (box, text) ->
                ScreenElement(
                    index = (i + 1).toString(),
                    text = text.take(80),
                    centerX = ((box.centerX()) * scaleX).toInt(),
                    centerY = ((box.centerY()) * scaleY).toInt(),
                    clickable = true,
                    editable = false,
                    focused = false,
                    bounds = ElementBounds(
                        left = (box.left * scaleX).toInt(),
                        top = (box.top * scaleY).toInt(),
                        right = (box.right * scaleX).toInt(),
                        bottom = (box.bottom * scaleY).toInt(),
                    ),
                )
            }
            if (elements.isEmpty()) null
            else ScreenObservation(
                packageName = packageName,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                elements = elements,
                visibleText = elements.joinToString("\n") { it.text },
                source = "ocr",
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun backoff() {
        nextAllowedAt = System.currentTimeMillis() + backoffMs
        backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun capture(service: AccessibilityService): Bitmap? = suspendCancellableCoroutine { cont ->
        service.takeScreenshot(
            Display.DEFAULT_DISPLAY,
            executor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    val buffer = screenshot.hardwareBuffer
                    val soft = try {
                        val hardware = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                        val copy = hardware?.copy(Bitmap.Config.ARGB_8888, false)
                        hardware?.recycle()
                        copy
                    } catch (_: Exception) {
                        null
                    } finally {
                        buffer.close()
                    }
                    // 协程已取消时回调才到：直接回收，不然这张整屏位图就泄漏了
                    if (cont.isActive) cont.resume(soft) else soft?.recycle()
                }

                override fun onFailure(errorCode: Int) {
                    if (cont.isActive) cont.resume(null)
                }
            },
        )
    }
}
