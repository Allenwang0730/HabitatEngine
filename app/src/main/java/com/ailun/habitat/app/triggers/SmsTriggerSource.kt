package com.ailun.habitat.app.triggers

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Telephony
import androidx.core.content.ContextCompat
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
        // 系统广播需 EXPORTED（Android 14 要求显式标志）；限定发送方持有 BROADCAST_SMS，防其他 App 伪造短信事件
        ContextCompat.registerReceiver(
            context,
            r,
            IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION),
            Manifest.permission.BROADCAST_SMS,
            null,
            ContextCompat.RECEIVER_EXPORTED,
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
