package com.ailun.habitat.app.triggers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Telephony
import com.ailun.habitat.TriggerConfig
import com.ailun.habitat.api.ITriggerSource
import com.ailun.habitat.api.TriggerEvent

/**
 * 短信触发器 — 通过 BroadcastReceiver 监听 SMS_RECEIVED 广播。
 * 需要 RECEIVE_SMS 权限。
 */
class SmsTriggerSource : ITriggerSource {
    override val type: String = TriggerConfig.TYPE_SMS

    private var receiver: BroadcastReceiver? = null
    private var onEvent: ((TriggerEvent) -> Unit)? = null

    override fun start(context: Context, onEvent: (TriggerEvent) -> Unit) {
        // 幂等：若已 start，先清理旧的（防重复注册 receiver 泄漏）
        if (receiver != null) stop(context)
        this.onEvent = onEvent

        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

                val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                val body = messages?.joinToString("") { it.messageBody } ?: ""
                val sender = messages?.firstOrNull()?.originatingAddress ?: ""

                val event = TriggerEvent(
                    type = TriggerConfig.TYPE_SMS,
                    params = mapOf(
                        "body" to body,
                        "sender" to sender,
                    ),
                )
                onEvent(event)
            }
        }

        receiver = r
        context.registerReceiver(
            r,
            IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION),
        )
    }

    override fun stop(context: Context) {
        receiver?.let {
            try { context.unregisterReceiver(it) } catch (_: Exception) {}
            receiver = null
        }
        onEvent = null
    }
}
