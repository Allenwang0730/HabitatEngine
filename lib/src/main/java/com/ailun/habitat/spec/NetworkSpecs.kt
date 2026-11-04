package com.ailun.habitat.spec

import com.ailun.habitat.NodeHandlerFactory as F

/** NETWORK：HTTP、网络状态、天气、地理编码、流量。 */
internal object NetworkSpecs {
    val all: List<NodeSpec> = listOf(
        NodeSpec(
            F.ACTION_HTTP_REQUEST, "发送 HTTP 请求，响应体存入变量",
            params = listOf(
                ParamSpec("url", ParamType.STRING, "请求 URL，支持 \${var}", required = true),
                ParamSpec("method", ParamType.STRING, "GET / POST / PUT / DELETE / PATCH 等（PATCH 以 POST + X-HTTP-Method-Override 发送）", default = "GET"),
                ParamSpec("headers", ParamType.STRING, "请求头字符串：JSON 对象字符串（如 {\"Authorization\":\"Bearer x\"}）或 Key:Value;Key2:Value2，支持 \${var}"),
                ParamSpec("body", ParamType.STRING, "请求体字符串，支持 \${var}；只在 POST/PUT/PATCH 发送，未设 Content-Type 时按 application/json"),
                ParamSpec("output_var", ParamType.STRING, "响应体另存到的变量名（http_response 总会写）"),
                ParamSpec("timeout", ParamType.STRING_OR_NUMBER, "连接/读取超时毫秒（最小 1000）", default = "30000"),
                ParamSpec("max_response_bytes", ParamType.STRING_OR_NUMBER, "响应体上限字节，超出截断并置 http_truncated=true", default = "5242880"),
            ),
            outputs = listOf("http_success", "http_error", "http_status_code", "http_response", "http_truncated"),
            requires = "需网络",
            notes = "headers / body 要写成字符串（JSON 转义成字符串）；写成嵌套 JSON 对象会被转成 {k=v} 文本而失效。" +
                "请求失败不抛错：看 http_success（状态码 200–399 为 true），要按节点出错处理可加 require=http_success。" +
                "对不稳定的第三方服务设合理 timeout（失败默认跳过继续）。",
        ),
        NodeSpec(
            F.ACTION_NETWORK_STATUS, "查询是否联网、网络类型，可选查 IP",
            params = listOf(
                ParamSpec(
                    "action", ParamType.STRING, "status=只查连接与类型；ip / all=另外写 wifi_ip、mobile_ip",
                    enum = listOf("status", "ip", "all"), default = "status",
                ),
            ),
            outputs = listOf("network_connected", "network_type", "wifi_ip", "mobile_ip", "network_success", "network_error"),
            notes = "network_type：wifi / cellular / ethernet / other / none。mobile_ip 实为第一个非回环 IPv4 地址，连着 WiFi 时可能就是 WiFi 地址。",
        ),
        NodeSpec(
            F.ACTION_GET_WEATHER, "按经纬度查当前天气（Open-Meteo，免 key）",
            params = listOf(
                ParamSpec("latitude", ParamType.STRING_OR_NUMBER, "纬度 -90–90，支持 \${var}", required = true),
                ParamSpec("longitude", ParamType.STRING_OR_NUMBER, "经度 -180–180，支持 \${var}", required = true),
                ParamSpec("output_var", ParamType.STRING, "原始响应 JSON 存到的变量名", default = "weather_json"),
            ),
            outputs = listOf(
                "weather_success", "weather_error", "weather_temperature", "weather_wind_speed",
                "weather_wind_direction", "weather_code", "weather_json",
            ),
            requires = "需网络",
            notes = "温度 ℃、风速 km/h；连接/读取超时固定各 10 秒。weather_code 为 WMO 代码：0 晴，1–3 多云/阴，45/48 雾，" +
                "51–67 毛毛雨/雨，71–77 雪，80–82 阵雨，85/86 阵雪，95–99 雷暴。",
        ),
        NodeSpec(
            F.ACTION_GEOCODE, "地址与经纬度互查（OpenStreetMap Nominatim，免 key）",
            params = listOf(
                ParamSpec("address", ParamType.STRING, "地址 → 查经纬度，支持 \${var}；与坐标同时给时优先用地址"),
                ParamSpec("latitude", ParamType.STRING_OR_NUMBER, "纬度 → 逆查地址，支持 \${var}；不给 address 时与 longitude 一起必填"),
                ParamSpec("longitude", ParamType.STRING_OR_NUMBER, "经度 → 逆查地址，支持 \${var}；不给 address 时与 latitude 一起必填"),
            ),
            outputs = listOf("geocode_success", "geocode_error", "latitude", "longitude", "address"),
            requires = "需网络",
            notes = "地址查询取第一个结果写 latitude/longitude，逆查写 address，会覆盖同名变量（如 ACTION_GET_LOCATION 写的 latitude/longitude）。" +
                "境外第三方服务，可能慢或不可达；连接/读取超时固定各 10 秒。",
        ),
        NodeSpec(
            F.ACTION_GET_TRAFFIC, "读取开机以来整机累计收发流量（字节）",
            params = listOf(
                ParamSpec("output_var", ParamType.STRING, "可读汇总字符串存到的变量名", default = "traffic_summary"),
            ),
            outputs = listOf("traffic_rx", "traffic_tx", "traffic_total", "traffic_summary", "traffic_success", "traffic_error"),
        ),
    )
}
