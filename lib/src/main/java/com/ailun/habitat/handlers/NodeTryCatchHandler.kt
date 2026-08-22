package com.ailun.habitat.handlers

import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode

/**
 * [ACTION_TRY_CATCH]：错误处理与恢复（条件分支）。
 *
 * 由 HabitatExecutor 维护 last_error / last_error_msg 变量：
 * - 节点失败（异常/超时/require 不满足）时写入；节点成功时清空
 * - 本节点读取"上一个节点"的错误标记决定 success/error 分支
 *
 * params：
 * - `catch_var`：异常信息存储变量名（可选，默认 "exception_msg"）
 * - `branches`：{"success": "...", "error": "..."}
 */
class NodeTryCatchHandler : INodeHandler {
    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        // 查询上一步是否产生了错误标记（executor 在节点失败时写入、成功时清空）
        val hasError = context.getVariable("last_error") != null
        val errorMsg = context.getVariable("last_error_msg")?.toString() ?: ""

        val catchVar = node.params?.get("catch_var")?.toString() ?: "exception_msg"
        if (hasError) {
            context.putVariable(catchVar, errorMsg)
        }

        // 根据 branches 选择路径
        val key = if (hasError) "error" else "success"
        context.log("TryCatch hasError=$hasError → branch=$key")
        return node.branches?.get(key)
    }
}

