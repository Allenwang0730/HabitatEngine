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
     *
     * @param onResult 工作流执行结束后，把上下文变量（所有节点输出）返还给调用方。
     *                 这是引擎向 App 返还"内容"的正式通道（此前只能靠文件副作用）。
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
        onResult: ((Map<String, Any?>) -> Unit)? = null,
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
                // 工作流结束：把变量快照返还给调用方（在 onComplete 之前）
                onResult?.invoke(HashMap(ctx.variables))
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
