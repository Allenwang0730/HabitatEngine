package com.ailun.habitat.handlers

import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode

/**
 * [ACTION_ASSERT]：断言节点（条件不满足 → 主动失败，走 executor 错误处理流）。
 *
 * params：
 * - `condition`（必填）：表达式，支持 `var == value` / `var != value` / `var > value` 等
 *   （与 CONDITION_SWITCH 语法一致，直接读变量名）或布尔变量名
 * - `message`（可选）：失败时的错误消息（写入 last_error）
 *
 * 语义：条件为 true → 返回 next 继续；false → 抛异常，
 * 由 executor 捕获并写入 last_error/last_error_msg/last_node（可配 on_error、被 TRY_CATCH 捕获）。
 */
class NodeAssertHandler : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        val rawCondition = node.params?.get("condition")?.toString()?.trim().orEmpty()
        if (rawCondition.isEmpty()) {
            context.log("Assert FAILED: no 'condition'")
            return node.next
        }
        val message = node.params?.get("message")?.toString()?.ifEmpty { null }

        val result = evaluate(rawCondition, context)
        context.log("Assert '$rawCondition' → $result")
        if (!result) {
            throw IllegalArgumentException(message ?: "断言失败: $rawCondition")
        }
        return node.next
    }

    /** 精简版表达式求值（与 SwitchNodeHandler 语法对齐）。 */
    private fun evaluate(expr: String, ctx: WorkflowContext): Boolean {
        // 直接取布尔变量
        val boolVal = ctx.getVariable(expr)
        if (boolVal is Boolean) return boolVal

        val ops = listOf("==", "!=", ">=", "<=", ">", "<")
        for (op in ops) {
            val parts = expr.split(op, limit = 2)
            if (parts.size != 2) continue
            val left = parts[0].trim()
            val right = parts[1].trim()
            val leftVal = ctx.getVariable(left)?.toString() ?: left
            val rightVal = ctx.getVariable(right)?.toString() ?: right
            if (op == "==") return leftVal == rightVal
            if (op == "!=") return leftVal != rightVal
            val l = leftVal.toDoubleOrNull() ?: continue
            val r = rightVal.toDoubleOrNull() ?: continue
            return when (op) {
                ">" -> l > r
                "<" -> l < r
                ">=" -> l >= r
                "<=" -> l <= r
                else -> false
            }
        }
        return false
    }
}
