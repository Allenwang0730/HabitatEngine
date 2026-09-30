package com.ailun.habitat.handlers

import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode
import com.ailun.habitat.paramLongOrNull
import com.ailun.habitat.paramString
import kotlinx.coroutines.delay
import java.util.Calendar

/**
 * [ACTION_WAIT_UNTIL_TIME]：等待到指定时刻（零权限，轮询实现）。
 * params：
 * - `time`（必填）：时刻。mode=clock 时为 "HH:mm" 或 "HH:mm:ss"（今天已过则顺延到明天）；
 *   mode=timestamp 时为 Unix 毫秒时间戳（字符串或数字）
 * - `mode`（可选）："clock"（默认）/"timestamp"
 * - `timeout_ms`（可选）：最长等待毫秒。默认：params 设了 `node_timeout_ms` 时为 12 小时
 *   （`node_timeout_ms=0` 不设节点超时；>0 时再受其约束）；未设 `node_timeout_ms` 时为 29 分钟
 *   （executor 默认节点超时 30 分钟，超过必被打断）。需要长等待请同时设 `"node_timeout_ms": 0`。
 * - `output_var`（可选）：结果变量名，默认 "time_reached"
 * 输出：time_reached(Boolean)
 *
 * 实现要点：每秒轮询 System.currentTimeMillis()，符合无 Looper 线程约束。
 */
class NodeWaitUntilTimeHandler : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        val rawTime = node.params?.get("time")?.toString()?.trim().orEmpty()
        val mode = paramString(node, context, "mode")?.trim()?.lowercase() ?: "clock"
        val nodeTimeout = paramLongOrNull(node, context, "node_timeout_ms")
        val explicit = paramLongOrNull(node, context, "timeout_ms")?.coerceAtLeast(1000L)
        var timeoutMs = explicit ?: if (nodeTimeout != null) LONG_DEFAULT_MS else SHORT_DEFAULT_MS
        if (nodeTimeout == null && timeoutMs > SHORT_DEFAULT_MS) {
            context.log("WaitUntilTime: timeout_ms=${timeoutMs} 超过默认节点超时（30min），已截到 29min；长等待请设 node_timeout_ms=0")
            timeoutMs = SHORT_DEFAULT_MS
        } else if (nodeTimeout != null && nodeTimeout > 0 && timeoutMs > nodeTimeout) {
            timeoutMs = nodeTimeout
        }
        val outputVar = node.params?.get("output_var")?.toString()?.trim()?.ifEmpty { null }
            ?: "time_reached"

        if (rawTime.isEmpty()) {
            context.putVariable("time_reached", false)
            context.log("WaitUntilTime FAILED: no 'time' param")
            return node.next
        }
        val time = context.interpolate(rawTime)

        val target = if (mode == "timestamp") {
            time.toLongOrNull()
        } else {
            parseClockTime(time)
        }
        if (target == null) {
            context.putVariable("time_reached", false)
            context.log("WaitUntilTime FAILED: cannot parse time '$time' (mode=$mode)")
            return node.next
        }

        if (explicit == null && nodeTimeout == null && target - System.currentTimeMillis() > timeoutMs) {
            context.log("WaitUntilTime: 目标时间超出默认 29min 等待上限，将超时；长等待请设 node_timeout_ms=0")
        }
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (System.currentTimeMillis() >= target) {
                context.putVariable("time_reached", true)
                context.putVariable("wait_until_time", time)
                context.log("WaitUntilTime reached: $time")
                return node.next
            }
            delay(1000)
        }
        context.putVariable("time_reached", false)
        context.log("WaitUntilTime timeout after ${timeoutMs}ms (target=$time)")
        return node.next
    }

    companion object {
        private const val SHORT_DEFAULT_MS = 29L * 60_000L
        private const val LONG_DEFAULT_MS = 12L * 3600_000L
    }

    /** 解析 "HH:mm" / "HH:mm:ss"，今天已过则顺延到明天。 */
    private fun parseClockTime(s: String): Long? {
        val parts = s.split(":")
        if (parts.size !in 2..3) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        val sec = if (parts.size == 3) parts[2].toIntOrNull() ?: 0 else 0
        if (h !in 0..23 || m !in 0..59 || sec !in 0..59) return null

        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, h)
            set(Calendar.MINUTE, m)
            set(Calendar.SECOND, sec)
            set(Calendar.MILLISECOND, 0)
        }
        var target = cal.timeInMillis
        if (target <= System.currentTimeMillis()) {
            target += 24L * 3600_000L
        }
        return target
    }
}
