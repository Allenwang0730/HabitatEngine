package com.ailun.habitat.handlers

import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode
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
 * 语义：
 * - 首次进入：清计数，跳转到 target
 * - target 成功回跳（last_error 为空）：清除计数，返回 next 继续
 * - target 失败回跳：计数+1，未达 times 则等 interval_ms 后重试 target；达到则放弃返回 next
 *
 * 注意：target 失败时需自行保证 on_error 默认 skip（或 require 失败）才会回到本节点。
 */
class NodeRetryHandler : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        val target = node.params?.get("target")?.toString()?.trim().orEmpty()
        val times = (node.params?.get("times") as? Number)?.toInt()?.coerceAtLeast(1) ?: 3
        val intervalMs = (node.params?.get("interval_ms") as? Number)?.toLong()?.coerceAtLeast(0L) ?: 1000L
        if (target.isEmpty()) {
            context.log("Retry FAILED: no 'target' param")
            return node.next
        }

        val countVar = "retry_count_${node.id}"
        val lastNode = context.getVariable("last_node")?.toString()

        // 首次进入（上一个执行的节点不是 target）
        if (lastNode != target) {
            context.variables[countVar] = 0
            context.log("Retry: first attempt → '$target'")
            return target
        }

        // 回跳进入：检查上一轮 target 是否成功
        val lastError = context.getVariable("last_error")?.toString()
        if (lastError == null) {
            context.variables.remove(countVar)
            context.log("Retry: target succeeded")
            return node.next
        }

        val count = (context.getVariable(countVar) as? Number)?.toInt() ?: 0
        if (count < times - 1) {
            context.variables[countVar] = count + 1
            context.log("Retry: attempt ${count + 2}/$times failed, retry in ${intervalMs}ms: $lastError")
            if (intervalMs > 0) delay(intervalMs)
            return target
        }

        context.variables.remove(countVar)
        context.log("Retry: exhausted after $times attempts")
        return node.next
    }
}
