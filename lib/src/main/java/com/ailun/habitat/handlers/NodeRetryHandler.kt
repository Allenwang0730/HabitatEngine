package com.ailun.habitat.handlers

import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode
import com.ailun.habitat.paramInt
import com.ailun.habitat.paramLong
import com.ailun.habitat.paramString
import kotlinx.coroutines.delay

/**
 * [ACTION_RETRY]：失败重试节点（配合 executor 的 last_error/on_error 机制）。
 *
 * 用法（工作流模式：retry → target → 回跳 retry）：
 * ```json
 * { "id":"retry", "type":"ACTION_RETRY", "params":{"target":"do_something","times":3,"interval_ms":1000}, "next":"after" }
 * { "id":"do_something", "type":"ACTION_XXX", "params":{"require":"xxx_success"}, "next":"retry" }
 * ```
 *
 * 语义（状态按本节点 id 记录在 `_retry_active_<id>` / `_retry_errs_<id>` / `retry_count_<id>`，target 可以是多节点链）：
 * - 首次进入（未处于重试中）：清计数、记下当前 run_errors，跳转到 target
 * - 回跳进入：本轮期间 run_errors 有增长（链上任一节点失败）或 last_error 非空 → 视为失败，否则成功
 * - 成功：清除状态，返回 next 继续
 * - 失败：计数+1，未达 times 则等 interval_ms 后重试 target；达到则放弃返回 next
 *
 * 注意：target 失败时需自行保证 on_error 默认 skip（或 require 失败）才会回到本节点。
 */
class NodeRetryHandler : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        val target = paramString(node, context, "target")?.trim().orEmpty()
        val times = paramInt(node, context, "times", 3).coerceAtLeast(1)
        val intervalMs = paramLong(node, context, "interval_ms", 1000L).coerceAtLeast(0L)
        if (target.isEmpty()) {
            context.log("Retry FAILED: no 'target' param")
            return node.next
        }

        val countVar = "retry_count_${node.id}"
        val activeVar = "_retry_active_${node.id}"
        val errsVar = "_retry_errs_${node.id}"
        val runErrors = (context.getVariable("run_errors") as? Number)?.toInt() ?: 0

        // 首次进入
        if (context.getVariable(activeVar) != true) {
            context.putVariable(activeVar, true)
            context.putVariable(errsVar, runErrors)
            context.putVariable(countVar, 0)
            context.log("Retry: first attempt → '$target'")
            return target
        }

        // 回跳进入：检查上一轮 target（链）是否成功
        val baseline = (context.getVariable(errsVar) as? Number)?.toInt() ?: runErrors
        val lastError = context.getVariable("last_error")?.toString()
        val failed = runErrors > baseline || lastError != null
        if (!failed) {
            clear(context, countVar, activeVar, errsVar)
            context.log("Retry: target succeeded")
            return node.next
        }

        val count = (context.getVariable(countVar) as? Number)?.toInt() ?: 0
        if (count < times - 1) {
            context.putVariable(countVar, count + 1)
            context.putVariable(errsVar, runErrors)
            val reason = lastError ?: context.getVariable("last_error_any")?.toString() ?: "unknown"
            context.log("Retry: attempt ${count + 2}/$times failed, retry in ${intervalMs}ms: $reason")
            if (intervalMs > 0) delay(intervalMs)
            return target
        }

        clear(context, countVar, activeVar, errsVar)
        context.log("Retry: exhausted after $times attempts")
        return node.next
    }

    private fun clear(context: WorkflowContext, vararg keys: String) {
        keys.forEach { context.variables.remove(it) }
    }
}
