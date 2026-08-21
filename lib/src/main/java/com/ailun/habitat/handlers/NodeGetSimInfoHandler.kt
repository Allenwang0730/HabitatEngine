package com.ailun.habitat.handlers

import android.content.Context
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode

/**
 * [ACTION_GET_SIM_INFO]：读取 SIM 卡信息（需 READ_PHONE_STATE）。
 * params：
 * - `output_var`（可选）：概要字符串输出变量名（默认 "sim_summary"）
 * 输出：sim_count(Int)、sim_carriers(String，逗号分隔)、sim_numbers(String，逗号分隔)、
 *       sim_summary(String)、sim_success
 *
 * 实现要点：SubscriptionManager.activeSubscriptionInfoList（同步查询）。
 */
class NodeGetSimInfoHandler : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        val outputVar = node.params?.get("output_var")?.toString()?.trim()?.ifEmpty { null }
            ?: "sim_summary"
        try {
            val sm = context.appContext.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
                as? SubscriptionManager
            val tm = context.appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

            val subs = sm?.activeSubscriptionInfoList.orEmpty()
            val carriers = subs.mapNotNull { it.carrierName?.toString() }
            val numbers = subs.mapNotNull { it.number }

            // 兜底：无 SubscriptionManager 信息时按 simState 估个数
            val count = if (subs.isNotEmpty()) {
                subs.size
            } else {
                val state = tm?.simState ?: TelephonyManager.SIM_STATE_UNKNOWN
                if (state == TelephonyManager.SIM_STATE_ABSENT || state == TelephonyManager.SIM_STATE_UNKNOWN) 0 else 1
            }

            val summary = "SIM×$count: carriers=[${carriers.joinToString(",")}] numbers=[${numbers.joinToString(",")}]"
            context.variables["sim_count"] = count
            context.variables["sim_carriers"] = carriers.joinToString(",")
            context.variables["sim_numbers"] = numbers.joinToString(",")
            context.variables[outputVar] = summary
            context.variables["sim_success"] = true
            context.log("SimInfo $summary")
        } catch (e: SecurityException) {
            context.variables["sim_success"] = false
            context.variables["sim_error"] = "缺少 READ_PHONE_STATE 权限: ${e.message}"
            context.log("SimInfo FAILED (READ_PHONE_STATE missing)")
        } catch (e: Exception) {
            context.variables["sim_success"] = false
            context.variables["sim_error"] = e.message ?: "Unknown error"
            context.log("SimInfo FAILED: ${e.message}")
        }
        return node.next
    }
}
