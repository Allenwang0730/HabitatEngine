package com.ailun.habitat.handlers

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode

/**
 * [ACTION_GET_BATTERY]：读取电池电量与充电状态（零权限）。
 * params：
 * - `output_var`（可选）：电量输出变量名，默认 "battery_level"
 * 输出：battery_level(0-100)、battery_charging(Boolean)、battery_success
 *
 * 实现要点：BATTERY_PROPERTY_CAPACITY（API 21+）无需权限；
 * 充电状态用 ACTION_BATTERY_CHANGED 粘性广播（registerReceiver(null, filter)）。
 */
class NodeGetBatteryHandler : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        val outputVar = node.params?.get("output_var")?.toString()?.trim()?.ifEmpty { null }
        try {
            val bm = context.appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1

            val charging = if (level >= 0) {
                val intent: Intent? = context.appContext.registerReceiver(
                    null,
                    IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                )
                when (intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
                    BatteryManager.BATTERY_STATUS_CHARGING,
                    BatteryManager.BATTERY_STATUS_FULL -> true
                    else -> false
                }
            } else {
                false
            }

            context.variables["battery_level"] = level
            context.variables["battery_charging"] = charging
            if (outputVar != null) context.variables[outputVar] = level
            context.variables["battery_success"] = true
            context.log("Battery level=$level charging=$charging")
        } catch (e: Exception) {
            context.variables["battery_success"] = false
            context.variables["battery_error"] = e.message ?: "Unknown error"
            context.log("Battery FAILED: ${e.message}")
        }
        return node.next
    }
}
