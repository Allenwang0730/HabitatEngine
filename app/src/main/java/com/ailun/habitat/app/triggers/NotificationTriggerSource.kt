package com.ailun.habitat.app.triggers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.ailun.habitat.TriggerConfig
import com.ailun.habitat.api.ITriggerSource
import com.ailun.habitat.api.TriggerEvent
import com.ailun.habitat.app.HabitatNotificationService

/**
 * 通知触发器 — 通过 [HabitatNotificationService] 监听系统通知事件。
 * 使用 LocalBroadcastManager 接收通知广播，无需额外权限。
 */
class NotificationTriggerSource : ITriggerSource {
    override val type: String = TriggerConfig.TYPE_NOTIFICATION

    private var receiver: BroadcastReceiver? = null
    private var onEvent: ((TriggerEvent) -> Unit)? = null

    override fun start(context: Context, onEvent: (TriggerEvent) -> Unit) {
        // 幂等：若已 start，先清理旧的（防重复注册 receiver 泄漏）
        if (receiver != null) stop(context)
        this.onEvent = onEvent

        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val event = TriggerEvent(
                    type = TriggerConfig.TYPE_NOTIFICATION,
                    params = mapOf(
                        "package" to (intent.getStringExtra(HabitatNotificationService.EXTRA_PACKAGE_NAME) ?: ""),
                        "title" to (intent.getStringExtra(HabitatNotificationService.EXTRA_TITLE) ?: ""),
                        "text" to (intent.getStringExtra(HabitatNotificationService.EXTRA_TEXT) ?: ""),
                        "timestamp" to (intent.getLongExtra(HabitatNotificationService.EXTRA_TIMESTAMP, 0L)),
                    ),
                )
                onEvent(event)
            }
        }

        receiver = r
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(context)
            .registerReceiver(r, IntentFilter(HabitatNotificationService.ACTION_HABITAT_NOTIFICATION_POSTED))
    }

    override fun stop(context: Context) {
        receiver?.let {
            try {
                androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(context).unregisterReceiver(it)
            } catch (_: Exception) {}
            receiver = null
        }
        onEvent = null
    }
}
