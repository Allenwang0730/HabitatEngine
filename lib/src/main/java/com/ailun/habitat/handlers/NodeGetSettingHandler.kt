package com.ailun.habitat.handlers

import android.provider.Settings
import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode

/**
 * [ACTION_GET_SETTING]：读取系统设置项（零权限读；部分设置镜像到 Android 无权限时也读得到）。
 * params：
 * - `key`（必填）：设置键名（如 "screen_off_timeout" / "adb_enabled" / "auto_time"）
 * - `namespace`（可选）："system"（默认）/ "global" / "secure"
 * - `output_var`（可选）：结果变量名，默认 "setting_value"
 * 输出：setting_value、setting_success
 *
 * 注意：写系统设置（SETTING）需 WRITE_SETTINGS 或 Shizuku，另行实现；本节点只读。
 */
class NodeGetSettingHandler : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        val key = node.params?.get("key")?.toString()?.trim().orEmpty()
        if (key.isEmpty()) {
            context.variables["setting_success"] = false
            context.variables["setting_error"] = "缺少 key"
            return node.next
        }
        val namespace = node.params?.get("namespace")?.toString()?.trim()?.lowercase() ?: "system"
        val outputVar = node.params?.get("output_var")?.toString()?.trim()?.ifEmpty { null }
            ?: "setting_value"
        val resolver = context.appContext.contentResolver

        try {
            val value: String? = when (namespace) {
                "global" -> Settings.Global.getString(resolver, key)
                "secure" -> Settings.Secure.getString(resolver, key)
                else -> Settings.System.getString(resolver, key)
            }
            if (value != null) {
                context.variables[outputVar] = value
                context.variables["setting_key"] = key
                context.variables["setting_namespace"] = namespace
                context.variables["setting_success"] = true
                context.log("GetSetting $namespace/$key = $value")
            } else {
                context.variables["setting_success"] = false
                context.variables["setting_error"] = "设置项 $namespace/$key 不存在或无权限读取"
            }
        } catch (e: Exception) {
            context.variables["setting_success"] = false
            context.variables["setting_error"] = e.message ?: "Unknown error"
        }
        return node.next
    }
}
