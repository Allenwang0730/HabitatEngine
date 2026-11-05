package com.ailun.habitat.app.agent

import android.graphics.Bitmap
import android.os.Build
import android.util.Base64
import com.ailun.habitat.app.bridge.AppAccessibilityProvider
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream

/**
 * 给多模态模型看的截图：无障碍截图 → 缩到 [MAX_WIDTH] 宽 → JPEG → base64。
 * 截图前藏起 Habitat 自己的小屏（[com.ailun.habitat.OverlayGuard]）。系统截图接口有频率限制（约 1 秒一次），
 * 失败时等一下再试一次；还不行返回 null，调用方照常只发文字。需要 Android 11+ 和无障碍服务。
 */
object ScreenCapture {
    private const val MAX_WIDTH = 720
    private const val QUALITY = 60

    suspend fun jpegBase64(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val service = AppAccessibilityProvider.getService() ?: return null
        repeat(2) { attempt ->
            val bitmap = com.ailun.habitat.OverlayGuard.aroundCapture { WeChatOcr.capture(service) }
            if (bitmap != null) {
                return try {
                    encode(bitmap)
                } finally {
                    bitmap.recycle()
                }
            }
            if (attempt == 0) delay(1_100)
        }
        return null
    }

    private fun encode(bitmap: Bitmap): String {
        val scaled = if (bitmap.width > MAX_WIDTH) {
            Bitmap.createScaledBitmap(bitmap, MAX_WIDTH, (bitmap.height * MAX_WIDTH.toFloat() / bitmap.width).toInt().coerceAtLeast(1), true)
        } else bitmap
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
        if (scaled !== bitmap) scaled.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
