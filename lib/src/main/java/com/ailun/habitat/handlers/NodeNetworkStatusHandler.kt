package com.ailun.habitat.handlers

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * [ACTION_NETWORK_STATUS]：查询网络状态和设备 IP。
 * params：
 * - `action`（可选）："status"（默认）/ "ip" / "all"
 * - `output_var`（可选）：输出变量前缀
 * 输出：network_connected, network_type (wifi/cellular/ethernet/none),
 *       wifi_ip, mobile_ip, network_success
 */
class NodeNetworkStatusHandler : INodeHandler {
    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        val action = node.params?.get("action")?.toString()?.lowercase() ?: "status"
        val cm = context.appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

        try {
            // Network type
            val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
            val connected = caps != null
            val type = when {
                caps == null -> "none"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "other"
            }
            context.putVariable("network_connected", connected)
            context.putVariable("network_type", type)

            // IP addresses
            if (action == "ip" || action == "all") {
                context.putVariable("wifi_ip", getWifiIp(context))
                context.putVariable("mobile_ip", getMobileIp())
            }

            context.putVariable("network_success", true)
        } catch (e: Exception) {
            context.putVariable("network_connected", false)
            context.putVariable("network_error", e.message ?: e.javaClass.simpleName)
            context.putVariable("network_success", false)
        }
        context.log("NetworkStatus connected=${context.variables["network_connected"]} type=${context.variables["network_type"]}")
        return node.next
    }

    private fun getWifiIp(context: WorkflowContext): String {
        return try {
            val wm = context.appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val ip = wm?.connectionInfo?.ipAddress ?: 0
            if (ip != 0) {
                String.format("%d.%d.%d.%d", ip and 0xFF, (ip shr 8) and 0xFF, (ip shr 16) and 0xFF, (ip shr 24) and 0xFF)
            } else ""
        } catch (_: Exception) { "" }
    }

    private fun getMobileIp(): String {
        return try {
            NetworkInterface.getNetworkInterfaces()?.asSequence()
                ?.flatMap { it.inetAddresses.asSequence() }
                ?.filterIsInstance<Inet4Address>()
                ?.filter { !it.isLoopbackAddress }
                ?.map { it.hostAddress }
                ?.firstOrNull() ?: ""
        } catch (_: Exception) { "" }
    }
}
