package com.ailun.habitat.app.triggers

import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.ailun.habitat.TriggerConfig
import com.ailun.habitat.api.ITriggerSource
import com.ailun.habitat.api.TriggerEvent

/**
 * 剪贴板触发器 — 通过 ClipboardManager 监听剪贴板变化。
 * Android 10+ 限制后台读取剪贴板，此触发器在应用进程内有效。
 */
class ClipboardTriggerSource : ITriggerSource {
    override val type: String = TriggerConfig.TYPE_CLIPBOARD

    private var listener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private var clipboardManager: ClipboardManager? = null
    private var onEvent: ((TriggerEvent) -> Unit)? = null
    private var lastText: String? = null
    private var lastAt = 0L

    override fun start(context: Context, onEvent: (TriggerEvent) -> Unit) {
        // 幂等：若已 start，先清理旧的（防重复注册 listener 泄漏）
        if (listener != null) stop(context)
        this.onEvent = onEvent

        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return
        clipboardManager = cm

        val l = ClipboardManager.OnPrimaryClipChangedListener {
            val text = try {
                cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
            } catch (_: Exception) { "" }
            // 部分 ROM（实测 vivo OriginOS）一次复制回调两次：同一内容 500ms 内只算一次
            val now = android.os.SystemClock.uptimeMillis()
            if (text == lastText && now - lastAt < DEDUP_MS) return@OnPrimaryClipChangedListener
            lastText = text
            lastAt = now

            onEvent(
                TriggerEvent(
                    type = TriggerConfig.TYPE_CLIPBOARD,
                    params = mapOf("text" to text),
                )
            )
        }

        listener = l
        cm.addPrimaryClipChangedListener(l)
    }

    override fun stop(context: Context) {
        listener?.let { clipboardManager?.removePrimaryClipChangedListener(it) }
        listener = null
        clipboardManager = null
        onEvent = null
        lastText = null
    }

    private companion object {
        const val DEDUP_MS = 500L
    }
}
