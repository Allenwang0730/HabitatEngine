package com.ailun.habitat.handlers

import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode
import kotlinx.coroutines.delay
import java.util.Calendar

/**
 * [ACTION_WAIT_UNTIL_TIME]：等待到指定时刻（零权限，轮询实现）。
 * params：
 * - `time`（必填）：时刻。mode=clock 时为 "HH:mm" 或 "HH:mm:ss"（今天已过则顺延到明天）；
 *   mode=timestamp 时为 Unix 毫秒时间戳（字符串或数字）
 * - `mode`（可选）："clock"（默认）/"timestamp"
 * - `timeout_ms`（可选）：最长等待毫秒，默认 12 小时
 * - `output_var`（可选）：结果变量名，默认 "time_reached"
 * 输出：time_reached(Boolean)
 *
 * 实现要点：每秒轮询 System.currentTimeMillis()，符合无 Looper 线程约束。
 */
class NodeWaitUntilTimeHandler : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        val rawTime = node.params?.get("time")?.toString()?.trim().orEmpty()
        val mode = node.params?.get("mode")?.toString()?.trim()?.lowercase() ?: "clock"
        val timeoutMs = (node.params?.get("timeout_ms") as? Number)?.toLong()
            ?.coerceAtLeast(1000L) ?: 12L * 3600_000L
        val outputVar = node.params?.get("output_var")?.toString()?.trim()?.ifEmpty { null }
            ?: "time_reached"

        if (rawTime.isEmpty()) {
            context.variables["time_reached"] = false
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
            context.variables["time_reached"] = false
            context.log("WaitUntilTime FAILED: cannot parse time '$time' (mode=$mode)")
            return node.next
        }

        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (System.currentTimeMillis() >= target) {
                context.variables["time_reached"] = true
                context.variables["wait_until_time"] = time
                context.log("WaitUntilTime reached: $time")
                return node.next
            }
            delay(1000)
        }
        context.variables["time_reached"] = false
        context.log("WaitUntilTime timeout after ${timeoutMs}ms (target=$time)")
        return node.next
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
