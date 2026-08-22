package com.ailun.habitat.handlers

import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode

/**
 * [ACTION_ARRAY]：数组操作（零权限，引擎内部纯逻辑）。
 * 变量中存 List<Any?>，可用 ACTION_SET_VARIABLE / 本节点创建。
 * params：
 * - `action`（必填）："set" / "push" / "pop" / "get" / "length" / "join" / "clear" / "remove" / "contains"
 * - `var`（必填）：数组变量名
 * - `value`（set 时）：字符串，按 delimiter 拆分建数组；或 JSON 数组字符串（自动识别）
 * - `item`（push/remove/contains 时）：元素
 * - `index`（pop/get/remove 时）：索引（负数=从末尾数）
 * - `delimiter`（可选）：set/join 分隔符，默认 ","
 * - `output_var`（可选）：结果变量名（get/length/join/contains/pop 时使用），默认 "array_result"
 * 输出：操作结果写 output_var；数组本体存回 var。
 */
class NodeArrayHandler : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        val action = node.params?.get("action")?.toString()?.trim()?.lowercase() ?: ""
        val varName = node.params?.get("var")?.toString()?.trim().orEmpty()
        if (varName.isEmpty()) {
            context.variables.remove("array_result") // CHM 不允许 null value
            context.log("Array FAILED: no 'var'")
            return node.next
        }
        val outputVar = node.params?.get("output_var")?.toString()?.trim()?.ifEmpty { null }
            ?: "array_result"
        val delimiter = node.params?.get("delimiter")?.toString() ?: ","
        val item = node.params?.get("item")?.toString()
        val index = (node.params?.get("index") as? Number)?.toInt()
            ?: node.params?.get("index")?.toString()?.toIntOrNull()

        val list = (context.variables[varName] as? List<*>)?.toMutableList() ?: mutableListOf<Any?>()

        try {
            when (action) {
                "set" -> {
                    val value = node.params?.get("value")?.toString() ?: ""
                    list.clear()
                    list.addAll(parseValues(value, delimiter))
                    context.variables[varName] = list
                    context.variables[outputVar] = list.size
                    context.log("Array set '${varName}' ← ${list.size} items")
                }

                "push" -> {
                    if (item != null) list.add(item)
                    context.variables[varName] = list
                    context.variables[outputVar] = list.size
                }

                "pop" -> {
                    val popped = if (list.isEmpty()) null else list.removeAt(list.size - 1)
                    context.variables[varName] = list
                    context.variables[outputVar] = popped
                }

                "get" -> {
                    val i = normalizeIndex(index ?: 0, list.size)
                    context.variables[outputVar] = if (i != null) list.getOrNull(i) else null
                }

                "length" -> context.variables[outputVar] = list.size

                "join" -> context.variables[outputVar] = list.joinToString(delimiter) { it?.toString() ?: "" }

                "clear" -> {
                    list.clear()
                    context.variables[varName] = list
                    context.variables[outputVar] = 0
                }

                "remove" -> {
                    val removed = if (index != null) {
                        val i = normalizeIndex(index, list.size)
                        if (i != null) list.removeAt(i) else null
                    } else {
                        list.remove(item)
                    }
                    context.variables[varName] = list
                    context.variables[outputVar] = removed
                }

                "contains" -> context.variables[outputVar] = list.any { it?.toString() == item }

                else -> {
                    context.variables.remove(outputVar) // CHM 不允许 null value
                    context.log("Array FAILED: unknown action '$action'")
                    return node.next
                }
            }
        } catch (e: Exception) {
            context.variables.remove(outputVar)
            context.log("Array FAILED: ${e.message}")
        }
        return node.next
    }

    private fun parseValues(value: String, delimiter: String): List<String> {
        val trimmed = value.trim()
        // 支持 JSON 数组字符串
        if (trimmed.startsWith("[")) {
            return try {
                @Suppress("UNCHECKED_CAST")
                (com.google.gson.Gson().fromJson(trimmed, List::class.java) as? List<*>)?.map { it?.toString() ?: "" }
                    ?: emptyList()
            } catch (e: Exception) {
                trimmed.removePrefix("[").removeSuffix("]").split(delimiter).map { it.trim() }
            }
        }
        return if (trimmed.isEmpty()) emptyList() else trimmed.split(delimiter).map { it.trim() }
    }

    private fun normalizeIndex(index: Int, size: Int): Int? {
        if (size == 0) return null
        val i = if (index < 0) size + index else index
        return if (i in 0 until size) i else null
    }
}
