package com.ailun.habitat.app.triggers

import android.content.Context
import com.ailun.habitat.TriggerConfig
import com.ailun.habitat.api.ITriggerSource
import com.ailun.habitat.api.TriggerEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 定时触发器 — 每个定时工作流一个独立协程，按自己的 interval_ms 用 delay 调度（最小 1000ms）。
 * 事件参数带 [PARAM_WORKFLOW_ID]，TriggerManager 只把它分发给对应工作流。
 *
 * 限制：仅在应用进程存活期间有效。Doze/应用待机下 delay 会被推迟（CPU 休眠时不计时），
 * 进程被杀后不会自行唤醒（重启进程时由 HabitatApp 恢复注册）。需要严格准时或跨进程存活
 * 应改用 AlarmManager / WorkManager（本轮未引入）。
 */
class TimerTriggerSource : ITriggerSource {
    override val type: String = TriggerConfig.TYPE_TIMER

    private var scope: CoroutineScope? = null
    @Volatile
    private var onEvent: ((TriggerEvent) -> Unit)? = null
    private val jobs = ConcurrentHashMap<String, Job>()

    @Synchronized
    override fun start(context: Context, onEvent: (TriggerEvent) -> Unit) {
        this.onEvent = onEvent
        if (scope == null) scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    /** 为某个工作流（重新）安排定时；默认首次在一个间隔之后触发，fireImmediately=true 时立即触发一次。 */
    @Synchronized
    fun schedule(workflowId: String, intervalMs: Long, fireImmediately: Boolean) {
        val s = scope ?: return
        val interval = intervalMs.coerceAtLeast(MIN_INTERVAL_MS)
        jobs.remove(workflowId)?.cancel()
        jobs[workflowId] = s.launch {
            if (!fireImmediately) delay(interval)
            while (isActive) {
                onEvent?.invoke(
                    TriggerEvent(
                        type = TriggerConfig.TYPE_TIMER,
                        params = mapOf(PARAM_WORKFLOW_ID to workflowId),
                    )
                )
                delay(interval)
            }
        }
    }

    @Synchronized
    fun cancel(workflowId: String) {
        jobs.remove(workflowId)?.cancel()
    }

    @Synchronized
    override fun stop(context: Context) {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        scope?.cancel()
        scope = null
        onEvent = null
    }

    companion object {
        const val PARAM_WORKFLOW_ID = "workflow_id"
        private const val MIN_INTERVAL_MS = 1000L
    }
}
