package com.ailun.habitat.handlers

import android.content.Context
import android.location.Location
import android.location.LocationManager
import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode

/**
 * [ACTION_GET_LOCATION]：读取最后一次已知位置（需定位权限）。
 * params：
 * - `output_var`（可选）：概要字符串输出变量名（默认 "location_summary"）
 * 输出：latitude、longitude、location_accuracy、location_time、location_summary、location_success
 *
 * 实现要点：LocationManager.getLastKnownLocation 同步查询（GPS → NETWORK 取较新者）。
 * 没有最近定位记录时如实返回失败。
 * 注意：属于高风险节点（位置隐私），建议在测试专用机验证。
 */
class NodeGetLocationHandler : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        val outputVar = node.params?.get("output_var")?.toString()?.trim()?.ifEmpty { null }
            ?: "location_summary"
        try {
            val lm = context.appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (lm == null) {
                context.variables["location_success"] = false
                context.variables["location_error"] = "LocationManager not available"
                return node.next
            }

            var best: Location? = null
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                runCatching {
                    val last = lm.getLastKnownLocation(provider)
                    if (last != null && (best == null || last.time > best.time)) best = last
                }
            }

            if (best != null) {
                context.variables["latitude"] = best.latitude
                context.variables["longitude"] = best.longitude
                context.variables["location_accuracy"] = best.accuracy
                context.variables["location_time"] = best.time
                context.variables[outputVar] = "${best.latitude},${best.longitude}"
                context.variables["location_success"] = true
                context.log("GetLocation OK: ${best.latitude},${best.longitude} (acc=${best.accuracy}m)")
            } else {
                context.variables["location_success"] = false
                context.variables["location_error"] = "无最近已知位置（需开启定位且至少定位过一次）"
                context.log("GetLocation: no last known location")
            }
        } catch (e: SecurityException) {
            context.variables["location_success"] = false
            context.variables["location_error"] = "缺少定位权限: ${e.message}"
            context.log("GetLocation FAILED (location permission missing)")
        } catch (e: Exception) {
            context.variables["location_success"] = false
            context.variables["location_error"] = e.message ?: "Unknown error"
            context.log("GetLocation FAILED: ${e.message}")
        }
        return node.next
    }
}
