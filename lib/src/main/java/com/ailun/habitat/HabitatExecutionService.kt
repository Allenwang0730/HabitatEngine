package com.ailun.habitat

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*

/**
 * 统一的工作流执行服务 — 悬浮窗和应用界面通过此单例触发任务，
 * 确保互斥执行、状态一致。
 */
object HabitatExecutionService {

    private const val TAG = "HabitatExecutionSvc"

    private val execScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val activeJobs = mutableMapOf<String, Job>()

    /** 检查指定工作流是否正在运行 */
    fun isRunning(workflowId: String): Boolean =
        activeJobs.containsKey(workflowId) && activeJobs[workflowId]?.isActive == true

    /** 所有正在运行的工作流 ID */
    fun activeWorkflowIds(): Set<String> = activeJobs.keys.toSet()

    /**
     * 启动工作流执行。
     * @return true 如果成功启动，false 如果已在运行中（防止重复执行）
     */
    fun start(
        workflowId: String,
        jsonContent: String,
        androidContext: Context,
        factory: NodeHandlerFactory,
        initialVars: Map<String, Any>? = null,
        onComplete: (() -> Unit)? = null,
        onLog: ((String) -> Unit)? = null,
    ): Boolean {
        if (isRunning(workflowId)) {
            Log.w(TAG, "Workflow '$workflowId' already running — skip duplicate start")
            return false
        }

        HabitatStateStore.setRunning(workflowId, true)

        val job = execScope.launch {
            try {
                Log.i(TAG, "=== Start workflow '$workflowId' ===")
                val graph = HabitatJson.fromJson(jsonContent)
                val executor = HabitatExecutor(factory)
                val ctx = WorkflowContext(androidContext)
                initialVars?.forEach { (k, v) -> ctx.variables[k] = v }
                executor.execute(graph, ctx, onLog).join()
            } catch (_: CancellationException) {
                Log.i(TAG, "Workflow '$workflowId' cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Workflow '$workflowId' error: ${e.message}", e)
                HabitatStateStore.setRunning(workflowId, false)
                activeJobs.remove(workflowId)
                throw e
            } finally {
                Log.i(TAG, "=== End workflow '$workflowId' ===")
                HabitatStateStore.setRunning(workflowId, false)
                activeJobs.remove(workflowId)
                onComplete?.invoke()
            }
        }

        activeJobs[workflowId] = job
        return true
    }

    /** 停止工作流执行 */
    fun stop(workflowId: String) {
        val job = activeJobs[workflowId]
        job?.cancel()
        activeJobs.remove(workflowId)
        HabitatStateStore.setRunning(workflowId, false)
        Log.i(TAG, "Workflow '$workflowId' stopped")
    }
}
