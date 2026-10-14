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

    private val execScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "uncaught in execution service: ${e.message}", e)
        }
    )
    private val activeJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val lock = Any()

    /** 检查指定工作流是否正在运行 */
    fun isRunning(workflowId: String): Boolean =
        activeJobs[workflowId]?.isCompleted == false

    /** 所有正在运行的工作流 ID */
    fun activeWorkflowIds(): Set<String> = activeJobs.keys.toSet()

    /**
     * 启动工作流执行。
     *
     * @param onResult 工作流执行结束后，把上下文变量（所有节点输出）返还给调用方。
     *                 这是引擎向 App 返还"内容"的正式通道（此前只能靠文件副作用）。
     * @param onComplete 无论成功/失败/取消，结束时恰好调用一次。
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
    ): Boolean = synchronized(lock) {
        if (isRunning(workflowId)) {
            Log.w(TAG, "Workflow '$workflowId' already running — skip duplicate start")
            return false
        }

        // LAZY：先登记到 activeJobs 再启动，避免 job 在登记前就跑完、finally 的清理先于登记执行
        val job = execScope.launch(start = CoroutineStart.LAZY) {
            val self = coroutineContext.job
            try {
                Log.i(TAG, "=== Start workflow '$workflowId' ===")
                val graph = HabitatJson.fromJson(jsonContent)
                val executor = HabitatExecutor(factory)
                val ctx = WorkflowContext(androidContext)
                initialVars?.forEach { (k, v) -> ctx.variables[k] = v }
                // executor 在自己的 scope 里 launch，与本 job 没有父子关系，取消需手动级联
                val execJob = executor.execute(graph, ctx, onLog)
                try {
                    execJob.join()
                } finally {
                    // join 正常返回时 execJob 已完成（cancel 为空操作）；
                    // 走到这里而 execJob 未完成 = 外层被取消（stop），级联取消 executor 内部 job（含 FORK 分支）
                    if (!execJob.isCompleted) execJob.cancel()
                }
                // 工作流结束：把变量快照返还给调用方（在 onComplete 之前）
                onResult?.invoke(HashMap(ctx.variables))
            } catch (_: CancellationException) {
                Log.i(TAG, "Workflow '$workflowId' cancelled")
            } catch (e: Throwable) {
                // 不重抛：工作流解析/执行失败只记录，避免协程未捕获异常导致进程崩溃。
                Log.e(TAG, "Workflow '$workflowId' error: ${e.message}", e)
            } finally {
                Log.i(TAG, "=== End workflow '$workflowId' ===")
                release(workflowId, self)
                try {
                    onComplete?.invoke()
                } catch (e: Throwable) {
                    Log.e(TAG, "Workflow '$workflowId' onComplete error: ${e.message}", e)
                }
            }
        }

        activeJobs[workflowId] = job
        HabitatStateStore.setRunning(workflowId, true)
        job.start()
        true
    }

    /** 停止工作流执行 */
    fun stop(workflowId: String) {
        val job = activeJobs[workflowId] ?: return
        job.cancel()
        release(workflowId, job)
        Log.i(TAG, "Workflow '$workflowId' stopped")
    }

    /** 仅当 [job] 仍是该 id 的当前任务时才移除并清运行状态，避免误清同 id 新启动的任务。 */
    private fun release(workflowId: String, job: Job) {
        synchronized(lock) {
            if (activeJobs.remove(workflowId, job)) {
                HabitatStateStore.setRunning(workflowId, false)
            }
        }
    }
}
