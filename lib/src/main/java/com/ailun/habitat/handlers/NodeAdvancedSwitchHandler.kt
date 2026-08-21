package com.ailun.habitat.handlers

import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode
import java.util.Calendar

/**
 * [CONDITION_ADVANCED_SWITCH]：高级条件判断。
 */
class NodeAdvancedSwitchHandler : INodeHandler {
    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        // Support two formats:
        // 1. expression: "sum > 15" (compact)
        // 2. var + operator + value: var="sum", operator=">", value="15" (split)
        val rawExpr = node.params?.get("expression")?.toString()?.trim()
            ?: buildExpression(node.params ?: emptyMap())
        val expr = context.interpolate(rawExpr)

        val result = evaluate(expr, context)
        val key = if (result) "true" else "false"
        val nextNode = node.branches?.get(key)

        context.log("决策: 表达式 '$expr' -> 结果: $result -> 下一节点: $nextNode")
        return nextNode
    }

    private fun buildExpression(params: Map<String, Any>): String {
        val varName = params["var"]?.toString()?.trim().orEmpty()
        val operator = params["operator"]?.toString()?.trim() ?: params["op"]?.toString()?.trim().orEmpty()
        val value = params["value"]?.toString()?.trim().orEmpty()
        return if (varName.isNotEmpty() && operator.isNotEmpty()) {
            "$varName $operator $value"
        } else ""
    }
    
    private fun evaluate(expr: String, context: WorkflowContext): Boolean {
        if (expr.isBlank()) return false

        return when {
            expr == "is_daytime" -> {
                val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                h in 8..17
            }
            expr == "is_nighttime" -> {
                val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                h !in 8..17
            }
            // Check multi-char operators FIRST to avoid partial matches (> matching >= etc.)
            expr.contains("!=") -> evaluateComparison(expr, context, "!=")
            expr.contains(">=") -> evaluateNumericComparison(expr, context, ">=")
            expr.contains("<=") -> evaluateNumericComparison(expr, context, "<=")
            expr.contains("==") -> evaluateComparison(expr, context, "==")
            expr.contains(">")  -> evaluateNumericComparison(expr, context, ">")
            expr.contains("<")  -> evaluateNumericComparison(expr, context, "<")
            expr.contains("contains")   -> evaluateStringOp(expr, context, "contains")
            expr.contains("startswith") -> evaluateStringOp(expr, context, "startswith")
            expr.contains("endswith")   -> evaluateStringOp(expr, context, "endswith")
            else -> {
                context.log("决策警告: 无法识别的表达式格式 '$expr'")
                false
            }
        }
    }

    private fun evaluateStringOp(expr: String, context: WorkflowContext, op: String): Boolean {
        val parts = expr.split(" $op ", limit = 2)
        if (parts.size != 2) return false
        val left = parts[0].trim()
        val right = parts[1].trim()
        val leftVal = context.getVariable(left)?.toString() ?: left
        val rightVal = context.getVariable(right)?.toString() ?: right
        return when (op) {
            "contains"   -> leftVal.contains(rightVal, ignoreCase = true)
            "startswith" -> leftVal.startsWith(rightVal, ignoreCase = true)
            "endswith"   -> leftVal.endsWith(rightVal, ignoreCase = true)
            else -> false
        }
    }
    
    private fun evaluateComparison(expr: String, context: WorkflowContext, op: String): Boolean {
        val parts = expr.split(op, limit = 2)
        if (parts.size != 2) return false
        
        val left = parts[0].trim()
        val right = parts[1].trim()
        
        // 尝试获取变量值，如果没有则取字面量
        val leftRaw = context.getVariable(left)
        val leftValue = leftRaw?.toString() ?: left
        
        val rightRaw = context.getVariable(right)
        val rightValue = rightRaw?.toString() ?: right
        
        val result = when (op) {
            "==" -> leftValue == rightValue
            "!=" -> leftValue != rightValue
            else -> false
        }
        
        // 只有在变量存在时打印详细对比，方便排查
        if (leftRaw != null || rightRaw != null) {
            context.log("  比较: '$leftValue' $op '$rightValue'")
        }
        
        return result
    }
    
    private fun evaluateNumericComparison(expr: String, context: WorkflowContext, op: String): Boolean {
        val parts = expr.split(op, limit = 2)
        if (parts.size != 2) return false
        
        val left = parts[0].trim()
        val right = parts[1].trim()
        
        val leftNum = (context.getVariable(left) as? Number)?.toDouble()
            ?: left.toDoubleOrNull()
        
        val rightNum = (context.getVariable(right) as? Number)?.toDouble()
            ?: right.toDoubleOrNull()
            
        if (leftNum == null || rightNum == null) {
            context.log("  比较失败: 操作数不是数字 (left=$leftNum, right=$rightNum)")
            return false
        }
        
        return when (op) {
            ">" -> leftNum > rightNum
            "<" -> leftNum < rightNum
            ">=" -> leftNum >= rightNum
            "<=" -> leftNum <= rightNum
            else -> false
        }
    }
}
