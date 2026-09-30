package com.ailun.habitat.handlers

import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode

/**
 * [ACTION_SET_VARIABLE]：动态设置上下文变量。
 *
 * params：`key`（必填），`value`（任意类型；字符串会先做 `${var}` 插值），`type`（可选：auto|string|int|bool|float）。
 * 示例：
 * ```json
 * {
 *   "id": "n1",
 *   "type": "ACTION_SET_VARIABLE",
 *   "params": {
 *     "key": "counter",
 *     "value": 10,
 *     "type": "int"
 *   },
 *   "next": "n2"
 * }
 * ```
 */
class NodeSetVariableHandler : INodeHandler {
    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        val key = (node.params?.get("name")?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: node.params?.get("key")?.toString()?.trim())
            ?: return node.next
        
        val rawValue = node.params?.get("value").let { if (it is String) context.interpolate(it) else it }
        val typeStr = node.params?.get("type")?.toString()?.trim()?.lowercase() ?: "auto"
        
        val value = when (typeStr) {
            "string" -> rawValue?.toString()
            "int" -> (rawValue as? Number)?.toInt() ?: rawValue?.toString()?.trim()?.let { it.toIntOrNull() ?: it.toDoubleOrNull()?.toInt() }
            "bool" -> when (rawValue) {
                is Boolean -> rawValue
                "true" -> true
                "false" -> false
                else -> rawValue?.toString()?.equals("true", true) == true
            }
            "float" -> (rawValue as? Number)?.toDouble() ?: rawValue?.toString()?.trim()?.toDoubleOrNull()
            else -> rawValue // auto: keep original type
        }
        
        context.putVariable(key, value)
        context.log("SetVariable $key = $value (type=$typeStr)")
        return node.next
    }
}

