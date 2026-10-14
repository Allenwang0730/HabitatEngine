package com.ailun.habitat.handlers

import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode
import kotlinx.coroutines.CancellationException
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
        context.putVariable("weather_success", false)
        val rawLat = node.params?.get("latitude")?.toString()?.trim().orEmpty()
        val rawLon = node.params?.get("longitude")?.toString()?.trim().orEmpty()
        if (rawLat.isEmpty() || rawLon.isEmpty()) {
            context.putVariable("weather_success", false)
            context.putVariable("weather_error", "缺少 latitude/longitude 参数")
            return node.next
        }
        val lat = context.interpolate(rawLat).trim().toDoubleOrNull()?.takeIf { it in -90.0..90.0 }
        val lon = context.interpolate(rawLon).trim().toDoubleOrNull()?.takeIf { it in -180.0..180.0 }
        if (lat == null || lon == null) {
            context.putVariable("weather_success", false)
            context.putVariable("weather_error", "latitude/longitude 不是合法坐标")
            return node.next
        }
        val outputVar = node.params?.get("output_var")?.toString()?.trim()?.ifEmpty { null }
            ?: "weather_json"

        try {
            val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current_weather=true"
            val conn = URL(url).openConnection() as HttpURLConnection
            val body = HttpUtil.withConnection(conn) { c ->
                c.connectTimeout = 10_000
                c.readTimeout = 10_000
                HttpUtil.readLimited(c.inputStream).first
            }

            val json = JSONObject(body)
            val cw = json.getJSONObject("current_weather")
            context.putVariable("weather_temperature", cw.getDouble("temperature"))
            context.putVariable("weather_wind_speed", cw.getDouble("windspeed"))
            context.putVariable("weather_wind_direction", cw.getDouble("winddirection"))
            context.putVariable("weather_code", cw.getInt("weathercode"))
            context.putVariable(outputVar, body)
            context.putVariable("weather_success", true)
            context.log("Weather: ${cw.getDouble("temperature")}℃ code=${cw.getInt("weathercode")}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            context.putVariable("weather_success", false)
            context.putVariable("weather_error", e.message ?: "Unknown error")
            context.log("Weather FAILED: ${e.message}")
        }
        return node.next
    }
}
