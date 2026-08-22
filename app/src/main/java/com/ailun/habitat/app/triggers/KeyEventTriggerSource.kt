package com.ailun.habitat.app.triggers

import android.content.Context
import android.view.KeyEvent
import com.ailun.habitat.TriggerConfig
import com.ailun.habitat.api.ITriggerSource
import com.ailun.habitat.api.TriggerEvent

/**
 * 按键触发器 — 通过无障碍服务的 onKeyEvent 回调接入。
 *
 * 不直接持有 Receiver/Broadcast，而是在 [com.ailun.habitat.app.HabitatAccessibilityService]
 * 中拦截按键事件并转发给此 source 的回调。TriggerManager 负责连接这两个组件。
 */
class KeyEventTriggerSource : ITriggerSource {
    override val type: String = TriggerConfig.TYPE_KEY

    @Volatile
    var onEvent: ((TriggerEvent) -> Unit)? = null
        private set

    override fun start(context: Context, onEvent: (TriggerEvent) -> Unit) {
        this.onEvent = onEvent
        // 将当前实例注册为活跃的按键事件监听者
        activeInstance = this
    }

    override fun stop(context: Context) {
        onEvent = null
        if (activeInstance == this) activeInstance = null
    }

    companion object {
        @Volatile
        var activeInstance: KeyEventTriggerSource? = null
            private set

        /** 由无障碍服务调用，将按键事件转发给触发器系统。返回 false = 监听但不拦截按键。 */
        fun onKeyEvent(keyCode: Int, action: Int): Boolean {
            val instance = activeInstance ?: return false
            val onEvent = instance.onEvent ?: return false
            // 只在按下时触发，避免 Key DOWN/UP 双触发（拦截由返回 false 交给系统）
            if (action != KeyEvent.ACTION_DOWN) return false
            onEvent(
                TriggerEvent(
                    type = TriggerConfig.TYPE_KEY,
                    params = mapOf(
                        "keycode" to keyCode,
                        "action" to action,
                    ),
                )
            )
            // 监听但不拦截：把按键事件交回系统（音量/返回等仍正常工作）
            return false
        }
    }
}
