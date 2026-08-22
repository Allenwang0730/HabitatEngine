package com.ailun.habitat.app.triggers

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.ailun.habitat.TriggerConfig
import com.ailun.habitat.api.ITriggerSource
import com.ailun.habitat.api.TriggerEvent

/**
 * 定时触发器 — 基于 Handler 的周期性定时。
 * 对于需要持久化的定时任务，应改为 AlarmManager；当前实现适用于应用进程内的简单定时。
 *
 * source 以 5s 最小粒度 tick；精确间隔由 TriggerManager 按每个 timer entry 的
 * TriggerConfig.intervalMs 门控（见 onTriggerEvent 的 timerLastFired 检查）。
 */
class TimerTriggerSource : ITriggerSource {
    override val type: String = TriggerConfig.TYPE_TIMER

    private var handler: Handler? = null
    private var runnable: Runnable? = null
    private var onEvent: ((TriggerEvent) -> Unit)? = null
    private var intervalMs: Long = 5000L
    private var isRepeat: Boolean = false
    private var started: Boolean = false

    override fun start(context: Context, onEvent: (TriggerEvent) -> Unit) {
        // 幂等：若已 start，先彻底清理旧的（防旧 Handler/Runnable 自续期泄漏）
        if (started) stop(context)

        this.onEvent = onEvent
        intervalMs = 5000L // 最小粒度；精确间隔由 TriggerManager 门控
        isRepeat = true

        val h = Handler(Looper.getMainLooper())
        handler = h

        val self = this
        val r = object : Runnable {
            override fun run() {
                val cb = self.onEvent
                // 若已 stop（onEvent 为 null）则不再自续期，避免旧 runnable 无限循环
                if (cb != null) {
                    cb(TriggerEvent(type = TriggerConfig.TYPE_TIMER))
                    if (self.isRepeat) h.postDelayed(this, self.intervalMs)
                }
            }
        }
        runnable = r
        started = true
        h.post(r)
    }

    override fun stop(context: Context) {
        handler?.removeCallbacksAndMessages(null)
        handler = null
        runnable = null
        onEvent = null
        isRepeat = false
        started = false
    }
}
