package com.ailun.habitat.handlers

import android.util.Base64
import android.util.Log
import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode

/**
 * [ACTION_BASE64]：Base64 编码或解码。
 *
 * params：
 * - `action`（必填）："encode" 或 "decode"
 * - `data`（必填）：要编码/解码的数据
 * - `output_var`（可选）：存储结果的变量名
 */
class NodeBase64Handler : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        val params = node.params ?: return node.next

        val action = params["action"]?.toString()?.trim()?.lowercase() ?: run {
            Log.w(TAG, "No action specified")
            context.putVariable("base64_success", false)
            return node.next
        }

        val rawData = params["data"]?.toString()?.trim() ?: run {
            Log.w(TAG, "No data provided")
            context.putVariable("base64_success", false)
            return node.next
        }

        val data = context.interpolate(rawData)
        val outputVar = params["output_var"]?.toString()?.trim()?.ifEmpty { null }

        try {
            val result: String = when (action) {
                "encode" -> {
                    Base64.encodeToString(data.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                }
                "decode" -> {
                    String(Base64.decode(data, Base64.NO_WRAP), Charsets.UTF_8)
                }
                else -> {
                    Log.w(TAG, "Unknown action: $action, expected 'encode' or 'decode'")
                    context.putVariable("base64_success", false)
                    return node.next
                }
            }

            val resultKey = outputVar ?: "base64_result"
            context.putVariable("base64_result", result)
            context.putVariable(resultKey, result)
            context.putVariable("base64_success", true)

            Log.i(TAG, "Base64 $action successful: ${result.length} chars")
        } catch (e: Exception) {
            Log.e(TAG, "Base64 $action failed: ${e.message}", e)
            context.putVariable("base64_success", false)
            context.putVariable("base64_error", e.message ?: "Unknown error")
        }

        return node.next
    }

    companion object {
        private const val TAG = "HabitatBase64"
    }
}
