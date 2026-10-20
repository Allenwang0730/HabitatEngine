package com.ailun.habitat.app.triggers

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.ailun.habitat.ScheduleMath
import com.ailun.habitat.TriggerConfig
import com.ailun.habitat.app.HabitatLogger
import com.ailun.habitat.app.TriggerManager
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * 按时刻定时（`"at":"08:00"`）用系统闹钟安排：进程被杀也会按时拉起；
 * 关机、强行停止、改时间/时区后由 [ScheduleReceiver] 重新安排。
 * 有「闹钟和提醒」权限时用精确闹钟，否则用不精确闹钟（可能晚几分钟）。
 */
object ScheduleAlarms {
    const val ACTION_FIRE = "com.ailun.habitat.action.SCHEDULE_FIRE"
    const val EXTRA_WORKFLOW_ID = "workflow_id"
    private const val TAG = "ScheduleAlarms"

    /** 这个配置是按时刻定时（而不是按间隔）。 */
    fun isTimeBased(config: TriggerConfig): Boolean =
        config.type == TriggerConfig.TYPE_TIMER && config.params["at"] != null

    /** 安排下一次；at/days 不合法时不安排并返回原因。 */
    fun schedule(context: Context, workflowId: String, config: TriggerConfig, nowMs: Long = System.currentTimeMillis()): String? {
        val at: LocalTime = config.at ?: return "at 不是 HH:mm：${config.params["at"]}"
        val days: Set<DayOfWeek> = config.days ?: return "days 不认识：${config.params["days"]}"
        val next = ScheduleMath.nextFire(nowMs, at, days)
        val am = context.getSystemService(AlarmManager::class.java) ?: return "没有 AlarmManager"
        val pi = pending(context, workflowId)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        HabitatLogger.habitat("Schedule '$workflowId' → ${java.util.Date(next)}（${if (exact) "精确" else "不精确"}）")
        return null
    }

    fun cancel(context: Context, workflowId: String) {
        context.getSystemService(AlarmManager::class.java)?.cancel(pending(context, workflowId))
    }

    /** 每个工作流一个闹钟：用 data URI 区分（PendingIntent 按 Intent.filterEquals 判同一个）。 */
    private fun pending(context: Context, workflowId: String): PendingIntent {
        val intent = Intent(context, ScheduleReceiver::class.java)
            .setAction(ACTION_FIRE)
            .setData(Uri.parse("habitat-schedule://workflow/${Uri.encode(workflowId)}"))
            .putExtra(EXTRA_WORKFLOW_ID, workflowId)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun canScheduleExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true

    internal fun logError(workflowId: String, reason: String) {
        HabitatLogger.w(TAG, "Schedule '$workflowId' 没有安排：$reason")
    }
}

/**
 * 定时闹钟到点 → 触发工作流并安排下一次；开机、升级、改时间/时区 → 全部重新安排。
 * 进程被杀时由系统拉起：[com.ailun.habitat.app.HabitatApp.onCreate] 先恢复已启用的触发器，再到这里。
 */
class ScheduleReceiver : BroadcastReceiver() {
    private companion object {
        /** AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED（API 31+）：用户开了精确闹钟权限后改用精确闹钟。 */
        const val EXACT_PERMISSION_CHANGED = "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            ScheduleAlarms.ACTION_FIRE -> {
                val id = intent.getStringExtra(ScheduleAlarms.EXTRA_WORKFLOW_ID) ?: return
                val result = goAsync()
                TriggerManager.fireScheduled(app, id) { result.finish() }
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, EXACT_PERMISSION_CHANGED,
            -> TriggerManager.rescheduleTimeAlarms(app)
        }
    }
}
