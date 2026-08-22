package com.ailun.habitat.handlers

import android.net.TrafficStats
import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode

/**
 * [ACTION_GET_TRAFFIC]：读取网络流量统计（零权限）。
 * params：
 * - `output_var`（可选）：汇总信息变量名，默认 "traffic_summary"
 * 输出：traffic_rx（累计接收字节）、traffic_tx（累计发送字节）、traffic_total、traffic_summary、traffic_success
 */
class NodeGetTrafficHandler : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        val outputVar = node.params?.get("output_var")?.toString()?.trim()?.ifEmpty { null }
            ?: "traffic_summary"
        try {
            val rx = TrafficStats.getTotalRxBytes()
            val tx = TrafficStats.getTotalTxBytes()
            val total = rx + tx
            val summary = "rx=${format(rx)} tx=${format(tx)} total=${format(total)}"
            context.variables["traffic_rx"] = rx
            context.variables["traffic_tx"] = tx
            context.variables["traffic_total"] = total
            context.variables[outputVar] = summary
            context.variables["traffic_success"] = true
            context.log("Traffic $summary")
        } catch (e: Exception) {
            context.variables["traffic_success"] = false
            context.variables["traffic_error"] = e.message ?: "Unknown error"
        }
        return node.next
    }

    private fun format(bytes: Long): String {
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1 -> String.format("%.2fGB", gb)
            mb >= 1 -> String.format("%.2fMB", mb)
            kb >= 1 -> String.format("%.1fKB", kb)
            else -> "${bytes}B"
        }
    }
}
