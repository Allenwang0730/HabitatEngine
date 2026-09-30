package com.ailun.habitat.handlers

import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode
import com.ailun.habitat.paramInt

/**
 * [ACTION_TEXT_OPERATION]：文本操作。
 * params：`action`/`operation` (replace/substring/split/uppercase/lowercase/trim/append/prepend),
 * `input`/`source_var` — 输入文本或变量名,
 * `old`/`search`, `new`/`replace_with` — 替换参数,
 * `output_var` — 输出变量名
 */
class NodeTextOperationHandler : INodeHandler {
    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        val params = node.params ?: return node.next

        val op = (params["action"]?.toString() ?: params["operation"]?.toString())
            ?.trim()?.lowercase() ?: return node.next

        val inputKey = (params["input"]?.toString() ?: params["source_var"]?.toString())?.trim().orEmpty()
        // input 是变量名则取变量值；否则当作字面文本（支持 ${var} 插值）
        val sourceValue = context.getVariable(inputKey)?.toString() ?: context.interpolate(inputKey)
        fun text(vararg keys: String): String =
            keys.firstNotNullOfOrNull { params[it]?.toString() }?.let { context.interpolate(it) }.orEmpty()
        val outputVar = params["output_var"]?.toString()?.trim()?.ifEmpty { null } ?: "text_output"

        val result = when (op) {
            "replace" -> {
                val old = text("old", "search")
                val new = text("new", "replace_with")
                if (old.isEmpty()) sourceValue else sourceValue.replace(old, new)
            }
            "substring" -> {
                val len = sourceValue.length
                val start = paramInt(node, context, "start", 0).coerceIn(0, len)
                val end = paramInt(node, context, "end", len).coerceIn(start, len)
                sourceValue.substring(start, end)
            }
            "split" -> {
                val delimiter = params["delimiter"]?.toString()?.ifEmpty { null } ?: ","
                sourceValue.split(delimiter).joinToString("\n")
            }
            "uppercase" -> sourceValue.uppercase()
            "lowercase" -> sourceValue.lowercase()
            "trim" -> sourceValue.trim()
            "append" -> sourceValue + text("text")
            "prepend" -> text("text") + sourceValue
            else -> sourceValue
        }

        context.putVariable(outputVar, result)
        context.log("TextOp $op '$inputKey' → '$result' (→ $outputVar)")
        return node.next
    }
}
