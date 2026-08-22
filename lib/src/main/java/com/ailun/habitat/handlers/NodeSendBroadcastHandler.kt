package com.ailun.habitat.handlers

import android.content.Intent
import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode
import org.json.JSONObject

/**
 * [ACTION_SEND_BROADCAST]：发送系统广播（零权限基础；Android 8+ 隐式广播受限，自定义 action 可发）。
 * params：
 * - `action`（必填）：广播 action，支持 ${var}
 * - `package`（可选）：限定目标包
 * - `extras`（可选）：JSON 对象字符串（如 {"key":"value","num":5,"flag":true}）
 * 输出：broadcast_success、broadcast_error
 */
class NodeSendBroadcastHandler : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        val rawAction = node.params?.get("action")?.toString()?.trim().orEmpty()
        if (rawAction.isEmpty()) {
            context.variables["broadcast_success"] = false
            context.variables["broadcast_error"] = "缺少 'action' 参数"
            return node.next
        }
        val action = context.interpolate(rawAction)

        try {
            val intent = Intent(action)
            node.params?.get("package")?.toString()?.takeIf { it.isNotBlank() }?.let {
                intent.setPackage(context.interpolate(it))
            }
            node.params?.get("component")?.toString()?.takeIf { it.isNotBlank() }?.let {
                intent.setComponent(android.content.ComponentName.unflattenFromString(context.interpolate(it)))
            }
            applyExtras(intent, node.params?.get("extras")?.toString())

            context.appContext.sendBroadcast(intent)
            context.variables["broadcast_success"] = true
            context.log("SendBroadcast: $action")
        } catch (e: Exception) {
            context.variables["broadcast_success"] = false
            context.variables["broadcast_error"] = e.message ?: "Unknown error"
            context.log("SendBroadcast FAILED: ${e.message}")
        }
        return node.next
    }

    private fun applyExtras(intent: Intent, raw: String?) {
        if (raw.isNullOrBlank()) return
        try {
            val json = JSONObject(raw)
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                when (val v = json.get(key)) {
                    is Int -> intent.putExtra(key, v)
                    is Long -> intent.putExtra(key, v)
                    is Double -> intent.putExtra(key, v)
                    is Boolean -> intent.putExtra(key, v)
                    else -> intent.putExtra(key, v.toString())
                }
            }
        } catch (_: Exception) {
            // extras 解析失败则忽略（action 仍发送）
        }
    }
}
