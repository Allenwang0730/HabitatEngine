package com.ailun.habitat.handlers

import android.util.Log
import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * [ACTION_PARSE_JSON]：解析 JSON 字符串并提取指定路径的值。
 *
 * params：
 * - `json`（必填）：JSON 字符串或变量名
 * - `path`（可选）：点分隔的路径，如 "data.items[0].name"
 * - `output_var`（可选）：存储解析结果的变量名
 *
 * 路径语法：
 * - `foo.bar` — 访问对象的 bar 属性
 * - `items[0]` — 访问数组的第 0 个元素
 * - `data.items[2].name` — 组合访问
 */
class NodeParseJsonHandler : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        val params = node.params ?: return node.next

        val rawJson = params["json"]?.toString()?.trim() ?: run {
            Log.w(TAG, "No JSON string provided")
            context.putVariable("parse_success", false)
            return node.next
        }

        val jsonString = context.interpolate(rawJson)

        // Try to resolve as variable first if it doesn't look like JSON
        val actualJson = if (!jsonString.trimStart().startsWith("{") &&
            !jsonString.trimStart().startsWith("[") &&
            !jsonString.trimStart().startsWith("\"")
        ) {
            context.getVariable(rawJson)?.toString() ?: jsonString
        } else {
            jsonString
        }

        val path = params["path"]?.toString()?.trim()?.ifEmpty { null }
        val outputVar = params["output_var"]?.toString()?.trim()?.ifEmpty { null }

        try {
            val jsonValue = JSONTokener(actualJson).nextValue()
            val result: Any? = if (path != null) {
                navigatePath(jsonValue, path)
            } else {
                jsonValue
            }

            val resultStr = when (result) {
                is JSONObject -> result.toString(2)
                is JSONArray -> result.toString(2)
                null -> "null"
                else -> result.toString()
            }

            val resultKey = outputVar ?: "parsed_json"
            context.putVariable(resultKey, resultStr)
            context.putVariable("parsed_value", resultStr)
            context.putVariable("parse_success", true)

            Log.i(TAG, "JSON parsed successfully${if (path != null) " at path: $path" else ""}")
        } catch (e: Exception) {
            Log.e(TAG, "JSON parse failed: ${e.message}", e)
            context.putVariable("parse_success", false)
            context.putVariable("parse_error", e.message ?: "Unknown error")
            if (outputVar != null) {
                context.putVariable(outputVar, "null")
            }
        }

        return node.next
    }

    /**
     * Navigate a parsed JSON value using a dot-separated path with optional array indices.
     * Examples: "data.name", "items[0]", "data.items[2].name"
     */
    private fun navigatePath(value: Any, path: String): Any? {
        var current: Any? = value
        val segments = tokenizePath(path)

        for (segment in segments) {
            current = when {
                // Array index: [N]
                segment.startsWith("[") && segment.endsWith("]") -> {
                    val arr = current as? JSONArray ?: return null
                    val index = segment.substring(1, segment.length - 1).toIntOrNull() ?: return null
                    if (index < 0 || index >= arr.length()) return null
                    arr.get(index)
                }
                // Object key
                else -> {
                    val obj = current as? JSONObject ?: return null
                    if (obj.has(segment)) obj.get(segment) else return null
                }
            }
        }

        return current
    }

    /**
     * Tokenize a path like "data.items[0].name" into ["data", "items", "[0]", "name"].
     */
    private fun tokenizePath(path: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()

        for (ch in path) {
            when (ch) {
                '.' -> {
                    if (current.isNotEmpty()) {
                        tokens.add(current.toString())
                        current.clear()
                    }
                }
                '[' -> {
                    if (current.isNotEmpty()) {
                        tokens.add(current.toString())
                        current.clear()
                    }
                    current.append(ch)
                }
                ']' -> {
                    current.append(ch)
                    tokens.add(current.toString())
                    current.clear()
                }
                else -> current.append(ch)
            }
        }

        if (current.isNotEmpty()) {
            tokens.add(current.toString())
        }

        return tokens
    }

    companion object {
        private const val TAG = "HabitatParseJson"
    }
}
