package com.ailun.habitat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 宿主自己的悬浮窗（小屏等）与无障碍手势 / 截图之间的协调。
 *
 * 手势会落到最上层的可触摸窗口上：小屏挡在目标上方时，点击会点到小屏自己。
 * 引擎不认识宿主的窗口，只在派发手势和截图前问一遍已注册的 [Hook]：
 * 挡住了就挪开（或暂时隐藏），完成后按返回的恢复动作复原。
 *
 * Hook 在主线程调用；没有注册任何 Hook 时零开销（不切线程、不等待）。
 */
object OverlayGuard {

    /** 屏幕像素坐标的矩形（左上闭、右下开）。 */
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        fun intersects(other: Box): Boolean =
            left < other.right && other.left < right && top < other.bottom && other.top < bottom

        fun inflate(by: Int): Box = Box(left - by, top - by, right + by, bottom + by)
    }

    interface Hook {
        /**
         * 手势将经过 [area]。挡住了就让开：返回恢复动作（不需要恢复就返回空操作）；没挡住返回 null。
         */
        fun yieldFor(area: Box): (() -> Unit)?

        /** 即将截图：把自己藏起来，返回恢复动作；本来就不在屏幕上返回 null。 */
        fun hideForCapture(): (() -> Unit)?
    }

    /** 窗口位置/透明度改动传到输入分发与合成需要一两帧。 */
    private const val SETTLE_MS = 120L

    private val hooks = CopyOnWriteArrayList<Hook>()

    fun register(hook: Hook) {
        if (hook !in hooks) hooks += hook
    }

    fun unregister(hook: Hook) {
        hooks -= hook
    }

    /** 派发经过 [area] 的手势；[area] 为 null（算不出范围）时按整屏处理。 */
    suspend fun <T> aroundGesture(area: Box?, block: suspend () -> T): T =
        around({ hook -> if (area == null) hook.hideForCapture() else hook.yieldFor(area) }, block)

    suspend fun <T> aroundCapture(block: suspend () -> T): T =
        around({ hook -> hook.hideForCapture() }, block)

    private suspend fun <T> around(ask: (Hook) -> (() -> Unit)?, block: suspend () -> T): T {
        if (hooks.isEmpty()) return block()
        val restores = withContext(Dispatchers.Main) {
            hooks.mapNotNull { hook -> runCatching { ask(hook) }.getOrNull() }
        }
        if (restores.isEmpty()) return block()
        delay(SETTLE_MS)
        try {
            return block()
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                restores.forEach { runCatching { it() } }
            }
        }
    }
}
