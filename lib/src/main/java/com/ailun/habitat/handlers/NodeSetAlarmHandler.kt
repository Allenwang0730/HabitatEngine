package com.ailun.habitat.handlers

import android.app.AlarmManager
import android.content.Intent
import android.provider.AlarmClock
import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode
import com.ailun.habitat.paramIntOrNull
import com.ailun.habitat.paramString

/**
 * [ACTION_SET_ALARM]：调起系统闹钟设置（零权限）。
 * params：
 * - `hour`（必填）：0-23
 * - `minute`（必填）：0-59
 * - `label`（可选）：闹钟标签
 * 输出：alarm_success
 *
 * 实现要点：Intent.ACTION_SET_ALARM + FLAG_ACTIVITY_NEW_TASK（application context 启动必须加）。
 * 注意：会打开系统时钟应用的设置界面（后台启动受限时可能失败，如实返回 false）。
 */
class NodeSetAlarmHandler : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        val hour = paramIntOrNull(node, context, "hour")
        val minute = paramIntOrNull(node, context, "minute")
        val label = paramString(node, context, "label")?.takeIf { it.isNotBlank() }

        if (hour == null || minute == null || hour !in 0..23 || minute !in 0..59) {
            context.putVariable("alarm_success", false)
            context.putVariable("alarm_error", "hour/minute 缺失或越界 (hour=$hour, minute=$minute)")
            context.log("SetAlarm FAILED: bad hour/minute")
            return node.next
        }

        try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                .apply {
                    if (label != null) putExtra(AlarmClock.EXTRA_MESSAGE, label)
                }
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            val pm = context.appContext.packageManager
            if (intent.resolveActivity(pm) == null) {
                context.putVariable("alarm_success", false)
                context.putVariable("alarm_error", "没有可处理 ACTION_SET_ALARM 的应用")
                context.log("SetAlarm FAILED: no alarm app")
                return node.next
            }

            context.appContext.startActivity(intent)
            context.putVariable("alarm_success", true)
            context.log("SetAlarm $hour:$minute${label?.let { " ($it)" } ?: ""}")
        } catch (e: Exception) {
            context.putVariable("alarm_success", false)
            context.putVariable("alarm_error", e.message ?: "Unknown error")
            context.log("SetAlarm FAILED: ${e.message}")
        }
        return node.next
    }
}
