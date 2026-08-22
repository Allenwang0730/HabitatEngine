package com.ailun.habitat.handlers

import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * [ACTION_PARSE_DATETIME]：日期时间解析/格式化/当前时间（零权限，java.time）。
 * params：
 * - `action`（可选）："now"（默认）/ "parse" / "format"
 * - `input`（parse 时必填）：日期时间字符串（如 "2026-08-22 10:30:00"）
 * - `input_format`（parse 时可选）：输入格式，默认 "yyyy-MM-dd HH:mm:ss"
 * - `format`（可选）：输出格式，默认 "yyyy-MM-dd HH:mm:ss"
 * - `add_days`/`add_hours`/`add_minutes`/`add_seconds`（可选）：时间偏移
 * - `output_var`（可选）：结果变量名，默认 "datetime_result"
 * 输出：datetime_result（格式化串）、datetime_epoch（毫秒）、
 *       datetime_year/month/day/hour/minute/second/weekday、datetime_success
 *
 * 说明：format 动作的 input 支持 epoch 毫秒（数字）或可解析字符串；
 * 日期格式使用 java.time.DateTimeFormatter 模式（yyyy/MM/dd/HH/mm/ss）。
 */
class NodeParseDateTimeHandler : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        val action = node.params?.get("action")?.toString()?.trim()?.lowercase() ?: "now"
        val inputFormat = node.params?.get("input_format")?.toString()?.ifEmpty { null } ?: "yyyy-MM-dd HH:mm:ss"
        val outputFormat = node.params?.get("format")?.toString()?.ifEmpty { null } ?: "yyyy-MM-dd HH:mm:ss"
        val outputVar = node.params?.get("output_var")?.toString()?.trim()?.ifEmpty { null }
            ?: "datetime_result"

        try {
            var epochMs: Long
            when (action) {
                "now" -> {
                    epochMs = System.currentTimeMillis()
                    applyOffsets(node, epochMs).let { epochMs = it }
                }

                "parse" -> {
                    val rawInput = node.params?.get("input")?.toString()?.trim().orEmpty()
                    if (rawInput.isEmpty()) throw IllegalArgumentException("缺少 'input' 参数")
                    val input = context.interpolate(rawInput)
                    val formatter = try {
                        DateTimeFormatter.ofPattern(inputFormat)
                    } catch (e: Exception) {
                        throw IllegalArgumentException("input_format 无效: $inputFormat")
                    }
                    epochMs = LocalDateTime.parse(input, formatter)
                        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    applyOffsets(node, epochMs).let { epochMs = it }
                }

                else -> { // format
                    val rawInput = node.params?.get("input")?.toString()?.trim().orEmpty()
                    if (rawInput.isEmpty()) throw IllegalArgumentException("缺少 'input' 参数")
                    val input = context.interpolate(rawInput)
                    epochMs = input.toLongOrNull()
                        ?: LocalDateTime.parse(input, DateTimeFormatter.ofPattern(inputFormat))
                            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    applyOffsets(node, epochMs).let { epochMs = it }
                }
            }

            val formatter = DateTimeFormatter.ofPattern(outputFormat)
            val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault())

            context.variables[outputVar] = dt.format(formatter)
            context.variables["datetime_epoch"] = epochMs
            context.variables["datetime_year"] = dt.year
            context.variables["datetime_month"] = dt.monthValue
            context.variables["datetime_day"] = dt.dayOfMonth
            context.variables["datetime_hour"] = dt.hour
            context.variables["datetime_minute"] = dt.minute
            context.variables["datetime_second"] = dt.second
            context.variables["datetime_weekday"] = dt.dayOfWeek.value // 1=Monday
            context.variables["datetime_success"] = true
            context.log("DateTime $action → ${dt.format(formatter)} (epoch=$epochMs)")
        } catch (e: Exception) {
            context.variables["datetime_success"] = false
            context.variables["datetime_error"] = e.message ?: "Unknown error"
            context.log("DateTime FAILED: ${e.message}")
        }
        return node.next
    }

    private fun applyOffsets(node: WorkflowNode, base: Long): Long {
        var ms = base
        val zone = ZoneId.systemDefault()
        val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), zone)
        val days = (node.params?.get("add_days") as? Number)?.toLong() ?: 0L
        val hours = (node.params?.get("add_hours") as? Number)?.toLong() ?: 0L
        val minutes = (node.params?.get("add_minutes") as? Number)?.toLong() ?: 0L
        val seconds = (node.params?.get("add_seconds") as? Number)?.toLong() ?: 0L
        if (days != 0L || hours != 0L || minutes != 0L || seconds != 0L) {
            ms = dt.plusDays(days).plusHours(hours).plusMinutes(minutes).plusSeconds(seconds)
                .atZone(zone).toInstant().toEpochMilli()
        }
        return ms
    }
}
