package com.ailun.habitat.handlers

import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode
import com.ailun.habitat.paramLongOrNull
import kotlinx.coroutines.delay

/**
 * [ACTION_DELAY]：暂停执行指定毫秒数。
 * params: `millis`（优先）或 `ms` — 延迟毫秒数（上限 60000，超出截断并记录日志；更长等待用 ACTION_WAIT_UNTIL_TIME）
 */
class NodeDelayHandler : INodeHandler {
    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        val ms = paramLongOrNull(node, context, "millis")
            ?: paramLongOrNull(node, context, "ms")
            ?: 0L
        if (ms > MAX_MS) {
            context.log("Delay ${ms}ms 超过上限，截断为 ${MAX_MS}ms")
        } else {
            context.log("Delay ${ms}ms")
        }
        if (ms > 0) delay(ms.coerceAtMost(MAX_MS))
        return node.next
    }

    companion object {
        private const val MAX_MS = 60_000L
    }
}
