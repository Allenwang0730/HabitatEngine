package com.ailun.habitat.handlers

import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode

/**
 * [ACTION_MATH]：数学运算。
 * params: `operation` (add/subtract/multiply/divide/modulo/power),
 * `a` 或 `operand1_var` — 第一操作数（变量名或直接数值）,
 * `b` 或 `operand2` 或 `operand2_var` — 第二操作数,
 * `output_var` — 输出变量名（默认 "math_result"）
 */
class NodeMathHandler : INodeHandler {
    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        val params = node.params ?: return node.next

        val operation = params["operation"]?.toString()?.trim()?.lowercase() ?: return node.next
        val outputVar = params["output_var"]?.toString()?.trim()?.ifEmpty { null } ?: "math_result"

        val a = resolveValue(params["a"], context)
            ?: resolveValue(params["operand1_var"], context)
            ?: 0.0

        val b = resolveValue(params["b"], context)
            ?: resolveValue(params["operand2"], context)
            ?: resolveValue(params["operand2_var"], context)
            ?: 0.0

        val result = when (operation) {
            "add" -> a + b
            "subtract" -> a - b
            "multiply" -> a * b
            "divide" -> if (b != 0.0) a / b else 0.0
            "modulo" -> if (b != 0.0) a % b else 0.0
            "power" -> Math.pow(a, b)
            else -> 0.0
        }

        context.putVariable(outputVar, result)
        context.log("Math $a $operation $b = $result → $outputVar")
        return node.next
    }

    private fun resolveValue(raw: Any?, ctx: WorkflowContext): Double? {
        return when (raw) {
            is Number -> raw.toDouble()
            is String -> {
                val interpolated = ctx.interpolate(raw)
                interpolated.toDoubleOrNull()
                    ?: (ctx.getVariable(raw) as? Number)?.toDouble()
            }
            else -> null
        }
    }
}
