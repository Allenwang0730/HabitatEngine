package com.ailun.habitat

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext

/**
 * 工作流执行器。
 *
 * 健壮性机制（v1.4.1）：
 * - **节点级超时**：每个 handler 包 withTimeout（默认 [NODE_TIMEOUT_DEFAULT_MS] = 30 分钟，
 *   节点 params 可 `node_timeout_ms` 覆盖；`node_timeout_ms = 0` 表示不设节点超时）；
 *   超时按节点失败处理。注意：超时不能中断 handler 内的阻塞调用（如 Runtime.exec.waitFor），
 *   但工作流会继续前进，阻塞线程由系统回收。
 * - **require 声明式成功条件**：节点 params 可加 `require: "xxx_success"`，
 *   handler 正常返回后检查该变量必须为 true，否则按节点失败处理。
 * - **失败策略 on_error**：`skip`（默认，记 last_error 后走 next 继续）/ `stop`（终止工作流）。
 * - **错误变量**：失败时写 last_error / last_error_msg / last_node / last_node_type；
 *   节点成功时清空 last_error —— ACTION_TRY_CATCH 据此走 error/success 分支。
 * - **FORK/JOIN 并行**：ACTION_FORK 声明 targets 启动多个分支协程（共享 context.variables），
 *   ACTION_JOIN 等待**本分支**此前 fork 出的全部分支完成（嵌套 FORK 各自独立记录，互不覆盖）。
 *   分支结束时仍未 join 的 fork 会被隐式等待——工作流"结束"时不留孤儿协程。
 *   分支各自有步数上限与节点超时护栏。
 * - **1000 步上限**：主流程与每个分支各自计数。
 */
class HabitatExecutor(
    private val factory: NodeHandlerFactory,
    private val maxSteps: Int = 1000,
) {

    companion object {
        private const val TAG = "HabitatExecutor"

        /** 节点级默认执行超时（毫秒，30 分钟兜底）。可用节点 params `node_timeout_ms` 覆盖，
         *  `node_timeout_ms = 0` 表示不设超时（WAIT_UNTIL_TIME 等长等待节点用）。
         *  注意：`timeout_ms` 是节点自身语义参数（如 WAIT_FOR_ELEMENT 的等待超时），executor 不读取。 */
        const val NODE_TIMEOUT_DEFAULT_MS = 1_800_000L

        /** FORK/JOIN 由 executor 内建处理（不经过 NodeHandlerFactory）。 */
        const val ACTION_FORK = "ACTION_FORK"
        const val ACTION_JOIN = "ACTION_JOIN"

        /**
         * 所有 executor 实例共享的顶层 scope（进程级，不需要 cancel；各次执行的 Job 由调用方持有并取消）。
         * runNode 已捕获 Throwable，这里的 handler 只兜底记录漏网异常，避免 SupervisorJob 下未捕获异常直接杀进程。
         */
        private val executorScope = CoroutineScope(
            SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
                Log.e(TAG, "uncaught in workflow coroutine: ${e.message}", e)
            }
        )
    }

    fun execute(
        graph: WorkflowGraph,
        workflowContext: WorkflowContext,
        onLog: ((String) -> Unit)? = null,
    ): Job {
        return executorScope.launch {
            executeSuspend(graph, workflowContext, onLog)
        }
    }

    fun execute(
        graph: WorkflowGraph,
        androidContext: Context,
        onLog: ((String) -> Unit)? = null,
    ): Job {
        val workflowContext = WorkflowContext(androidContext)
        return execute(graph, workflowContext, onLog)
    }

    private suspend fun executeSuspend(
        graph: WorkflowGraph,
        workflowContext: WorkflowContext,
        onLog: ((String) -> Unit)? = null,
    ) {
        workflowContext.onLog = onLog

        val nodes = graph.nodes
        val startId = graph.startNodeId
        if (nodes.isNullOrEmpty() || startId.isNullOrBlank()) {
            Log.w(TAG, "execute: invalid graph (nodes=${nodes?.size}, startId=$startId)")
            workflowContext.log("Habitat: Invalid graph — missing nodes or start_node_id")
            return
        }

        val index = HashMap(nodes)
        Log.i(TAG, "=== Workflow start: ${nodes.size} nodes, start=$startId ===")
        workflowContext.log("Graph loaded: ${nodes.size} nodes, starting from '$startId'")

        runBranch(startId, index, workflowContext, maxSteps)

        workflowContext.log("Habitat: Finished")
    }

    /**
     * 从 startId 沿 next/branches 执行。主流程与 FORK 分支共用。
     * 遇到 FORK 启动子分支并记入本分支的 pendingForks；遇到 JOIN 等待本分支已 fork 的全部子分支。
     * 分支结束（含 break/步数上限/stop）时隐式 join 剩余子分支，保证返回即整棵子树已结束。
     */
    private suspend fun runBranch(
        startId: String,
        index: Map<String, WorkflowNode>,
        ctx: WorkflowContext,
        maxSteps: Int,
    ) {
        var currentId: String? = startId
        var step = 0
        var errorCount = 0
        // 本分支 fork 出、尚未 join 的子分支（局部变量：嵌套/连续 FORK 不会互相覆盖）
        val pendingForks = ArrayList<Job>()

        while (currentId != null && step < maxSteps) {
            coroutineContext.ensureActive()
            // 树级终止：on_error=stop 置 stopRequested，所有分支在此检查后停止
            if (ctx.stopRequested) {
                ctx.log("Habitat: stop requested — terminating branch")
                break
            }
            val node = index[currentId]
            if (node == null) {
                ctx.log("Habitat: Missing node '$currentId'")
                break
            }

            val nodeType = node.type ?: "<null type>"

            when (nodeType) {
                ACTION_FORK -> {
                    val targets = parseForkTargets(node, ctx)
                    ctx.log("Fork: ${targets.size} branches → $targets")
                    // 分支用 CoroutineScope(coroutineContext) 挂到当前执行 job 下，使整棵树可被外部取消级联
                    targets.mapTo(pendingForks) { t ->
                        CoroutineScope(coroutineContext).launch {
                            try {
                                runBranch(t, index, ctx, maxSteps)
                            } catch (e: CancellationException) {
                                throw e // 取消必须向上传播（分支 job 已随父取消）
                            } catch (e: Throwable) {
                                ctx.log("✗ Branch '$t' failed: ${e.message ?: e.javaClass.simpleName}")
                            }
                        }
                    }
                    currentId = node.next
                    step++
                }

                ACTION_JOIN -> {
                    if (pendingForks.isNotEmpty()) {
                        ctx.log("Join: waiting for ${pendingForks.size} branches...")
                        pendingForks.forEach { it.join() }
                        pendingForks.clear()
                        ctx.log("Join: all branches done")
                    }
                    currentId = node.next
                    step++
                }

                else -> {
                    val handler = factory.get(nodeType)
                    if (handler == null) {
                        ctx.log("⚠ No handler for type '$nodeType' (node '$currentId'), skipping")
                        currentId = node.next
                        step++
                        continue
                    }

                    Log.i(TAG, "step $step: [$currentId] $nodeType")
                    val startTime = System.currentTimeMillis()
                    val result = runNode(node, handler, ctx)
                    if (result.failed) errorCount++

                    val elapsed = System.currentTimeMillis() - startTime
                    if (elapsed > 1000) {
                        Log.i(TAG, "step $step: [$currentId] $nodeType took ${elapsed}ms (slow)")
                    }

                    currentId = result.next
                    step++
                }
            }
        }
        // 未显式 JOIN 的子分支：分支正常结束（含 break/步数上限/stop）时等待它们完成——
        // 工作流"结束"= 整棵树结束，不留孤儿协程。取消时循环已抛出，子分支作为子 job 随父级一并取消。
        if (pendingForks.isNotEmpty()) {
            ctx.log("Habitat: implicit join of ${pendingForks.size} un-joined branches")
            pendingForks.forEach { it.join() }
        }

        if (step >= maxSteps) {
            Log.w(TAG, "=== Branch aborted after $maxSteps steps ===")
            ctx.log("Habitat: Aborted (step limit $maxSteps reached)")
        } else {
            Log.i(TAG, "=== Branch finished: $step steps, $errorCount errors ===")
        }
    }

    private data class NodeResult(
        val next: String?,
        val failed: Boolean,
        val error: String?,
    )

    /** 执行单个节点：超时 + require 检查 + 错误变量 + on_error 策略。 */
    private suspend fun runNode(
        node: WorkflowNode,
        handler: INodeHandler,
        ctx: WorkflowContext,
    ): NodeResult {
        // node_timeout_ms：缺省 → 默认 30 分钟；0 → 不设超时；其余最小 100ms
        val timeoutMs = when (val raw = (node.params?.get("node_timeout_ms") as? Number)?.toLong()) {
            null -> NODE_TIMEOUT_DEFAULT_MS
            0L -> 0L
            else -> raw.coerceAtLeast(100L)
        }

        var next: String? = null
        var failed = false
        var errorMsg: String? = null

        try {
            next = if (timeoutMs == 0L) handler.handle(node, ctx)
            else withTimeout(timeoutMs) { handler.handle(node, ctx) }

            // 声明式成功条件：require 指定的变量必须为 true
            val require = node.params?.get("require")?.toString()?.takeIf { it.isNotBlank() }
            if (require != null) {
                val ok = ctx.getVariable(require) == true
                if (!ok) {
                    failed = true
                    errorMsg = "节点未满足 require '$require'（值为 ${ctx.getVariable(require)}）"
                }
            }
        } catch (e: TimeoutCancellationException) {
            failed = true
            errorMsg = "节点超时（${timeoutMs}ms）"
            Log.e(TAG, "node ${node.id} ${node.type} TIMEOUT after ${timeoutMs}ms")
        } catch (e: CancellationException) {
            throw e // 外部取消必须传播
        } catch (e: Throwable) {
            // 捕获 Throwable：handler 抛出的 Error（OOM、NoSuchMethodError/LinkageError 等）也按节点失败处理，
            // 不让它逃逸到 executorScope 杀死整个 App
            failed = true
            errorMsg = e.message ?: e.javaClass.simpleName
            Log.e(TAG, "node ${node.id} ${node.type} FAILED: ${e.message}", e)
        }

        if (failed) {
            ctx.variables["last_error"] = errorMsg
            ctx.variables["last_error_msg"] = errorMsg
            ctx.variables["last_node"] = node.id
            ctx.variables["last_node_type"] = node.type
            // 累计错误（不被后续成功节点清除，供事后检查）
            val prevErrors = (ctx.getVariable("run_errors") as? Number)?.toInt() ?: 0
            ctx.variables["run_errors"] = prevErrors + 1
            ctx.variables["last_error_any"] = errorMsg
            val onError = node.params?.get("on_error")?.toString()?.trim()?.lowercase() ?: "skip"
            if (onError == "stop") {
                ctx.stopRequested = true // 树级终止：所有随后的节点/分支检查此标志后停止
                ctx.log("✗ '${node.type}' 失败（on_error=stop 终止整棵树）: $errorMsg")
                return NodeResult(null, failed = true, error = errorMsg)
            }
            ctx.log("✗ '${node.type}' 失败（跳过继续）: $errorMsg")
            return NodeResult(next ?: node.next, failed = true, error = errorMsg)
        }

        // 成功：清空错误标记，供 ACTION_TRY_CATCH 判定（CHM 不允许 null value，用 remove）；
        // 记录 last_node 供 ACTION_RETRY 判断"上一节点是否目标"
        ctx.variables.remove("last_error")
        ctx.variables.remove("last_error_msg")
        ctx.variables["last_node"] = node.id
        ctx.variables["last_node_type"] = node.type
        return NodeResult(next, failed = false, error = null)
    }

    /** 解析 FORK 的 targets（JSON 数组字符串或逗号分隔）。 */
    private fun parseForkTargets(node: WorkflowNode, ctx: WorkflowContext): List<String> {
        val param = node.params?.get("targets")
        if (param is List<*>) {
            return param.mapNotNull { it?.toString()?.let(ctx::interpolate)?.trim() }.filter { it.isNotBlank() }
        }
        val raw = param?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) {
            ctx.log("⚠ Fork: no 'targets' param")
            return emptyList()
        }
        val interpolated = ctx.interpolate(raw)
        return if (interpolated.startsWith("[")) {
            try {
                @Suppress("UNCHECKED_CAST")
                (com.google.gson.Gson().fromJson(interpolated, List::class.java) as? List<*>)
                    ?.map { it?.toString() ?: "" }?.filter { it.isNotBlank() } ?: emptyList()
            } catch (e: Exception) {
                interpolated.removePrefix("[").removeSuffix("]").split(",").map { it.trim() }.filter { it.isNotBlank() }
            }
        } else {
            interpolated.split(",").map { it.trim() }.filter { it.isNotBlank() }
        }
    }
}
