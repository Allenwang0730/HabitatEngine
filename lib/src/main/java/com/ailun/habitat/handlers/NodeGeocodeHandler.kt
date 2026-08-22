package com.ailun.habitat.handlers

import android.util.Log
import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * [ACTION_GEOCODE]：地理编码/逆地理编码（Nominatim，OpenStreetMap，免 key；需网络）。
 * params：
 * - `address`（可选）：地址字符串 → 查经纬度
 * - `latitude` / `longitude`（可选）：坐标 → 查地址（reverse）
 * 输出：latitude、longitude（地址查询）；address（reverse）；geocode_success
 */
class NodeGeocodeHandler : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        val address = node.params?.get("address")?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val lat = node.params?.get("latitude")?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val lon = node.params?.get("longitude")?.toString()?.trim()?.takeIf { it.isNotEmpty() }

        if (address == null && (lat == null || lon == null)) {
            context.variables["geocode_success"] = false
            context.variables["geocode_error"] = "需要 address 或 latitude+longitude"
            return node.next
        }

        var conn: HttpURLConnection? = null
        try {
            val url = if (address != null) {
                "https://nominatim.openstreetmap.org/search?format=json&q=${URLEncoder.encode(address, "UTF-8")}"
            } else {
                "https://nominatim.openstreetmap.org/reverse?format=json&lat=${context.interpolate(lat!!)}&lon=${context.interpolate(lon!!)}"
            }
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("User-Agent", "Habitat/1.0")
            val body = conn.inputStream.bufferedReader().use { it.readText() }

            if (address != null) {
                val arr = JSONArray(body)
                if (arr.length() > 0) {
                    val first = arr.getJSONObject(0)
                    context.variables["latitude"] = first.getDouble("lat")
                    context.variables["longitude"] = first.getDouble("lon")
                    context.variables["geocode_success"] = true
                } else {
                    context.variables["geocode_success"] = false
                    context.variables["geocode_error"] = "未找到该地址"
                }
            } else {
                val obj = JSONObject(body)
                context.variables["address"] = obj.optString("display_name")
                context.variables["geocode_success"] = true
            }
            context.log("Geocode OK")
        } catch (e: Exception) {
            context.variables["geocode_success"] = false
            context.variables["geocode_error"] = e.message ?: "Unknown error"
            Log.e(TAG, "Geocode FAILED: ${e.message}")
        } finally {
            conn?.disconnect()
        }
        return node.next
    }

    companion object {
        private const val TAG = "HabitatGeocode"
    }
}
