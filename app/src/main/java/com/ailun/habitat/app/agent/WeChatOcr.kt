package com.ailun.habitat.app.agent

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.view.Display
import androidx.annotation.RequiresApi
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
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
        val blocks = readScreen(service, screenWidth, screenHeight) ?: run {
            backoff()
            return null
        }
        backoffMs = 1_000L
        nextAllowedAt = System.currentTimeMillis() + 1_000L
        val elements = blocks.take(24).mapIndexed { i, block ->
            ScreenElement(
                index = (i + 1).toString(),
                text = block.text.take(80),
                centerX = block.box.centerX(),
                centerY = block.box.centerY(),
                clickable = true,
                editable = false,
                focused = false,
                bounds = ElementBounds(block.box.left, block.box.top, block.box.right, block.box.bottom),
            )
        }
        return if (elements.isEmpty()) null
        else ScreenObservation(
            packageName = packageName,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            elements = elements,
            visibleText = elements.joinToString("\n") { it.text },
            source = "ocr",
        )
    }

    /** 识别出的一段文字，坐标已换算到屏幕像素。 */
    data class Block(val text: String, val box: Rect)

    /** 与 [recognize] 同一套限频和退避（系统截图接口约 1 秒一次），返回原始文字块；被限频时返回 null。 */
    suspend fun readScreenThrottled(service: AccessibilityService, screenWidth: Int, screenHeight: Int): List<Block>? {
        if (System.currentTimeMillis() < nextAllowedAt) return null
        val blocks = readScreen(service, screenWidth, screenHeight) ?: run {
            backoff()
            return null
        }
        backoffMs = 1_000L
        nextAllowedAt = System.currentTimeMillis() + 1_000L
        return blocks
    }

    /**
     * 截屏并识别，不限频（ACTION_OCR 节点用；Agent 读屏走 [recognize]，带限频和退避）。
     * 截图或识别失败返回 null；屏幕上没有字返回空列表。需要 Android 11+。
     */
    suspend fun readScreen(service: AccessibilityService, screenWidth: Int, screenHeight: Int): List<Block>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        // 小屏截图前先藏起来：别把它认成屏幕上的文字
        val bitmap = com.ailun.habitat.OverlayGuard.aroundCapture { capture(service) } ?: return null
        return try {
            val result: Text = suspendCancellableCoroutine<Text?> { cont ->
                recognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                    .addOnFailureListener { if (cont.isActive) cont.resume(null) }
            } ?: return null
            val scaleX = if (bitmap.width > 0) screenWidth.toFloat() / bitmap.width else 1f
            val scaleY = if (bitmap.height > 0) screenHeight.toFloat() / bitmap.height else 1f
            result.textBlocks.mapNotNull { block ->
                val box = block.boundingBox ?: return@mapNotNull null
                val text = block.text.trim()
                if (text.isEmpty()) return@mapNotNull null
                Block(
                    text,
                    Rect((box.left * scaleX).toInt(), (box.top * scaleY).toInt(), (box.right * scaleX).toInt(), (box.bottom * scaleY).toInt()),
                )
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun backoff() {
        nextAllowedAt = System.currentTimeMillis() + backoffMs
        backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    internal suspend fun capture(service: AccessibilityService): Bitmap? = suspendCancellableCoroutine { cont ->
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
