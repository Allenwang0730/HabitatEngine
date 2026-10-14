package com.ailun.habitat.handlers

import android.util.Log
import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode
import kotlinx.coroutines.CancellationException
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
        context.putVariable("geocode_success", false)
        val address = node.params?.get("address")?.toString()?.let { context.interpolate(it) }
            ?.trim()?.takeIf { it.isNotEmpty() }
        val rawLat = node.params?.get("latitude")?.toString()?.let { context.interpolate(it) }?.trim()?.takeIf { it.isNotEmpty() }
        val rawLon = node.params?.get("longitude")?.toString()?.let { context.interpolate(it) }?.trim()?.takeIf { it.isNotEmpty() }

        if (address == null && (rawLat == null || rawLon == null)) {
            context.putVariable("geocode_error", "需要 address 或 latitude+longitude")
            return node.next
        }
        val lat = rawLat?.toDoubleOrNull()?.takeIf { it in -90.0..90.0 }
        val lon = rawLon?.toDoubleOrNull()?.takeIf { it in -180.0..180.0 }
        if (address == null && (lat == null || lon == null)) {
            context.putVariable("geocode_error", "latitude/longitude 不是合法坐标")
            return node.next
        }

        try {
            val url = if (address != null) {
                "https://nominatim.openstreetmap.org/search?format=json&q=${URLEncoder.encode(address, "UTF-8")}"
            } else {
                "https://nominatim.openstreetmap.org/reverse?format=json&lat=$lat&lon=$lon"
            }
            val conn = URL(url).openConnection() as HttpURLConnection
            val body = HttpUtil.withConnection(conn) { c ->
                c.connectTimeout = 10_000
                c.readTimeout = 10_000
                c.setRequestProperty("User-Agent", "Habitat/1.0")
                HttpUtil.readLimited(c.inputStream).first
            }

            if (address != null) {
                val arr = JSONArray(body)
                if (arr.length() > 0) {
                    val first = arr.getJSONObject(0)
                    context.putVariable("latitude", first.getDouble("lat"))
                    context.putVariable("longitude", first.getDouble("lon"))
                    context.putVariable("geocode_success", true)
                } else {
                    context.putVariable("geocode_success", false)
                    context.putVariable("geocode_error", "未找到该地址")
                }
            } else {
                val obj = JSONObject(body)
                context.putVariable("address", obj.optString("display_name"))
                context.putVariable("geocode_success", true)
            }
            context.log("Geocode OK")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            context.putVariable("geocode_success", false)
            context.putVariable("geocode_error", e.message ?: "Unknown error")
            Log.e(TAG, "Geocode FAILED: ${e.message}")
        }
        return node.next
    }

    companion object {
        private const val TAG = "HabitatGeocode"
    }
}
