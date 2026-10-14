package com.ailun.habitat.handlers

import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode
import com.ailun.habitat.paramInt
import com.ailun.habitat.paramString
import kotlin.random.Random

/**
 * [ACTION_RANDOM]：生成随机数。
 * params：
 * - `min`（可选）：最小值（含），默认 0
 * - `max`（可选）：最大值（含），默认 100
 * - `output_var`（可选）：输出变量名，默认 "random_value"
 * - `type`（可选）："int"（默认）/ "float" / "boolean"
 * 输出：random_value
 */
class NodeRandomHandler : INodeHandler {
    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        var min = paramInt(node, context, "min", 0)
        var max = paramInt(node, context, "max", 100)
        if (min > max) {
            context.log("Random: min($min) > max($max)，已交换")
            val t = min; min = max; max = t
        }
        val type = paramString(node, context, "type")?.trim()?.lowercase() ?: "int"
        val outputVar = node.params?.get("output_var")?.toString()?.ifEmpty { null } ?: "random_value"

        when (type) {
            "float" -> context.putVariable(
                outputVar,
                if (min == max) min.toDouble() else Random.nextDouble(min.toDouble(), max.toDouble()),
            )
            "boolean" -> context.putVariable(outputVar, Random.nextBoolean())
            else -> context.putVariable(outputVar, Random.nextLong(min.toLong(), max.toLong() + 1).toInt())
        }
        context.putVariable("random_success", true)
        context.log("Random type=$type min=$min max=$max → ${context.variables[outputVar]}")
        return node.next
    }
}
