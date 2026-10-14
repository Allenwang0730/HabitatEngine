package com.ailun.habitat

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 无障碍手势派发工具，使用标准 Android [AccessibilityService] API。
 */
object HabitatAccessibility {

    suspend fun dispatchTap(service: AccessibilityService, x: Int, y: Int): Boolean {
        if (x < 0 || y < 0) return false
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100L))
            .build()
        return dispatchGestureAwait(service, gesture)
    }

    suspend fun dispatchSwipe(
        service: AccessibilityService,
        x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long,
    ): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1L)))
            .build()
        return dispatchGestureAwait(service, gesture)
    }

    /** 原地按住 [durationMs] 毫秒（长按）。 */
    suspend fun dispatchLongPress(service: AccessibilityService, x: Int, y: Int, durationMs: Long): Boolean {
        if (x < 0 || y < 0) return false
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val duration = durationMs.coerceAtLeast(1L)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        return dispatchGestureAwait(service, gesture, timeoutMs = duration + 5000)
    }

    /** 派发任意手势并挂起等待结果；dispatchGesture 返回 false / 抛异常 / 超时均返回 false。 */
    suspend fun dispatchGestureAwait(
        service: AccessibilityService,
        gesture: GestureDescription,
        timeoutMs: Long = 5000,
    ): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        val dispatched = try {
            service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gesture: GestureDescription?) { deferred.complete(true) }
                override fun onCancelled(gesture: GestureDescription?) { deferred.complete(false) }
            }, null)
        } catch (e: IllegalArgumentException) {
            // 手势时长/坐标超出系统限制
            false
        }
        if (!dispatched) {
            // 手势未派发（服务未就绪/队列忙/屏幕灭）→ 立即返回 false，避免 deferred 永挂
            return false
        }
        // 超时兜底：正常派发但回调可能丢失（如屏幕瞬间变化）→ 超时无回调也返回 false
        return withTimeoutOrNull(timeoutMs) { deferred.await() } ?: false
    }
}
