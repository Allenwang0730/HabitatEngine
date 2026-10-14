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
                context.putVariable("location_success", false)
                context.putVariable("location_error", "LocationManager not available")
                return node.next
            }

            val ctx = context.appContext
            val fine = ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            val coarse = ctx.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!fine && !coarse) {
                context.putVariable("location_success", false)
                context.putVariable("location_error", "缺少定位权限（ACCESS_FINE/COARSE_LOCATION）")
                context.log("GetLocation FAILED (location permission missing)")
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
                context.putVariable("latitude", best.latitude)
                context.putVariable("longitude", best.longitude)
                context.putVariable("location_accuracy", best.accuracy)
                context.putVariable("location_time", best.time)
                context.putVariable(outputVar, "${best.latitude},${best.longitude}")
                context.putVariable("location_success", true)
                context.log("GetLocation OK: ${best.latitude},${best.longitude} (acc=${best.accuracy}m)")
            } else {
                context.putVariable("location_success", false)
                context.putVariable("location_error", "无最近已知位置（需开启定位且至少定位过一次）")
                context.log("GetLocation: no last known location")
            }
        } catch (e: SecurityException) {
            context.putVariable("location_success", false)
            context.putVariable("location_error", "缺少定位权限: ${e.message}")
            context.log("GetLocation FAILED (location permission missing)")
        } catch (e: Exception) {
            context.putVariable("location_success", false)
            context.putVariable("location_error", e.message ?: "Unknown error")
            context.log("GetLocation FAILED: ${e.message}")
        }
        return node.next
    }
}
