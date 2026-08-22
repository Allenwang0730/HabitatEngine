package com.ailun.habitat.handlers

import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * [ACTION_GET_WEATHER]：查询天气（Open-Meteo，免 key；需网络）。
 * params：
 * - `latitude` / `longitude`（必填）：坐标，支持 ${var}
 * - `output_var`（可选）：原始 JSON 输出变量名，默认 "weather_json"
 * 输出：weather_temperature(℃)、weather_wind_speed(km/h)、weather_code、
 *       weather_json、weather_success、weather_error
 */
class NodeGetWeatherHandler : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        val rawLat = node.params?.get("latitude")?.toString()?.trim().orEmpty()
        val rawLon = node.params?.get("longitude")?.toString()?.trim().orEmpty()
        if (rawLat.isEmpty() || rawLon.isEmpty()) {
            context.variables["weather_success"] = false
            context.variables["weather_error"] = "缺少 latitude/longitude 参数"
            return node.next
        }
        val lat = context.interpolate(rawLat)
        val lon = context.interpolate(rawLon)
        val outputVar = node.params?.get("output_var")?.toString()?.trim()?.ifEmpty { null }
            ?: "weather_json"

        var conn: HttpURLConnection? = null
        try {
            val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current_weather=true"
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            val body = conn.inputStream.bufferedReader().use { it.readText() }

            val json = JSONObject(body)
            val cw = json.getJSONObject("current_weather")
            context.variables["weather_temperature"] = cw.getDouble("temperature")
            context.variables["weather_wind_speed"] = cw.getDouble("windspeed")
            context.variables["weather_wind_direction"] = cw.getDouble("winddirection")
            context.variables["weather_code"] = cw.getInt("weathercode")
            context.variables[outputVar] = body
            context.variables["weather_success"] = true
            context.log("Weather: ${cw.getDouble("temperature")}℃ code=${cw.getInt("weathercode")}")
        } catch (e: Exception) {
            context.variables["weather_success"] = false
            context.variables["weather_error"] = e.message ?: "Unknown error"
            context.log("Weather FAILED: ${e.message}")
        } finally {
            conn?.disconnect()
        }
        return node.next
    }
}
