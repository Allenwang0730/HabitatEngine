package com.ailun.habitat

/**
 * 触发器配置，从工作流 JSON 的顶层 `trigger` 字段解析。
 *
 * 示例：
 * ```json
 * { "type": "notification", "package": "com.android.mms" }
 * { "type": "timer", "interval_ms": 60000, "repeat": true }
 * { "type": "clipboard" }
 * { "type": "key", "keycode": 24 }
 * ```
 */
data class TriggerConfig(
    val type: String,
    val params: Map<String, Any> = emptyMap(),
) {
    companion object {
        const val TYPE_NOTIFICATION = "notification"
        const val TYPE_SMS = "sms"
        const val TYPE_TIMER = "timer"
        const val TYPE_CLIPBOARD = "clipboard"
        const val TYPE_KEY = "key"
    }

    val packageFilter: String?
        get() = params["package"]?.toString()

    val intervalMs: Long
        get() = (params["interval_ms"] as? Number)?.toLong() ?: 60000L

    val repeat: Boolean
        get() = params["repeat"]?.toString()?.toBooleanStrictOrNull() ?: false

    val keycode: Int?
        get() = (params["keycode"] as? Number)?.toInt()

    /**
     * 触发时若工作流已在运行的碰撞策略：
     * - "ignore"（默认）：丢弃本次触发
     * - "abort_existing"：停止旧实例，立即启动新的
     * - "queue"：排队，旧实例完成后补跑一次
     */
    val onBusy: String
        get() = params["on_busy"]?.toString()?.trim()?.lowercase() ?: "ignore"
}
