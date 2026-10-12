package com.ailun.habitat.app.agent

/**
 * 自主模式可以直接调用的设备工具（不经过界面点击）。
 *
 * 为什么要有：Habitat 本来就有 80 多个节点，旧的自主循环却只会"在屏幕上点"。
 * 「打开蓝牙」「亮度调到最高」「进入关于手机」这类目标，界面上要翻好几屏、还常常点错；
 * 一个工具调用就能做完，而且结果可以直接读回来验证。
 *
 * 安全边界（规划器的输出一律当作不可信）：
 * - 只有这里登记的工具，参数只能取枚举值或已安装应用；拿不到合法参数就不执行。
 * - 会改设备状态的工具（开关 WLAN/蓝牙/手电/免打扰、调亮度音量）必须由**用户目标原文**点名
 *   （[DeviceTool.gate] 关键词），规划器写进步骤说明的文字不算——否则规划器可以自己"决定"关掉 WLAN。
 * - 有外部副作用的只有 `call`：号码只能来自 [ToolScope]（代码在本机查通讯录/客服表），规划器给不出号码；
 *   直接拨出只在本机发起且有 CALL_PHONE 时，远程发起和紧急号码只填进拨号盘。其余（发短信、文件、Shell…）不放。
 */
object DeviceTools {

    /** 一个可执行的工具调用：落到 Habitat 节点 + 固定参数。 */
    data class Invocation(
        val tool: String,
        val args: Map<String, String>,
        val node: String,
        val params: Map<String, Any>,
        val label: String,
        val successFlag: String,
        /** 执行后记成"已知事实"的输出变量。 */
        val outputs: List<String> = emptyList(),
        /** 执行成功后前台应该是这个包（用于验证"真的跳过去了"）；null 不检查。 */
        val expectPackage: String? = null,
    )

    data class DeviceTool(
        val id: String,
        val description: String,
        /** 参数名 → 允许的取值。 */
        val args: Map<String, List<String>>,
        /** 目标原文里出现其中任意一个词才可用；空表示不改设备状态（导航/只读），总是可用。 */
        val gate: List<String> = emptyList(),
    )

    /** 系统设置页：键 → (显示名, intent action, 目标里出现就推荐的词)。 */
    val SETTINGS_PAGES: Map<String, Triple<String, String, List<String>>> = linkedMapOf(
        "main" to Triple("设置首页", "android.settings.SETTINGS", listOf("设置")),
        "wifi" to Triple("WLAN", "android.settings.WIFI_SETTINGS", listOf("wlan", "wifi", "wi-fi", "无线")),
        "bluetooth" to Triple("蓝牙", "android.settings.BLUETOOTH_SETTINGS", listOf("蓝牙", "bluetooth")),
        "mobile_network" to Triple("移动网络", "android.settings.DATA_ROAMING_SETTINGS", listOf("移动网络", "流量", "数据", "sim", "双卡")),
        "display" to Triple("显示与亮度", "android.settings.DISPLAY_SETTINGS", listOf("显示", "亮度", "深色", "护眼", "字体")),
        "sound" to Triple("声音与振动", "android.settings.SOUND_SETTINGS", listOf("声音", "铃声", "振动", "音量")),
        "notifications" to Triple("通知", "android.settings.NOTIFICATION_SETTINGS", listOf("通知")),
        "battery" to Triple("电池", "android.intent.action.POWER_USAGE_SUMMARY", listOf("电池", "电量", "耗电")),
        "storage" to Triple("存储", "android.settings.INTERNAL_STORAGE_SETTINGS", listOf("存储", "空间", "内存")),
        "location" to Triple("定位", "android.settings.LOCATION_SOURCE_SETTINGS", listOf("定位", "位置", "gps")),
        "date" to Triple("日期与时间", "android.settings.DATE_SETTINGS", listOf("日期", "时间", "时区")),
        "language" to Triple("语言", "android.settings.LOCALE_SETTINGS", listOf("语言", "language")),
        "accessibility" to Triple("无障碍", "android.settings.ACCESSIBILITY_SETTINGS", listOf("无障碍", "辅助功能")),
        "about" to Triple("关于手机", "android.settings.DEVICE_INFO_SETTINGS", listOf("关于手机", "关于本机", "版本号", "型号", "设备信息")),
        "airplane" to Triple("飞行模式", "android.settings.AIRPLANE_MODE_SETTINGS", listOf("飞行模式")),
    )

    private val ON_OFF = listOf("on", "off")
    private val LEVELS = listOf("max", "high", "mid", "low", "min")

    /** `call` 的门控词：目标原文里写了"打电话"这类词才能拨号。必须在 [ALL] 之前初始化。 */
    val CALL_WORDS = listOf("打电话", "电话给", "打给", "拨打", "拨给", "呼叫", "致电")

    val ALL: List<DeviceTool> = listOf(
        DeviceTool("open_settings", "直接打开系统设置的某一页（不用在设置里翻找）", mapOf("page" to SETTINGS_PAGES.keys.toList())),
        DeviceTool("open_app", "打开一个已安装应用", mapOf("package" to emptyList())),
        DeviceTool("set_wifi", "打开或关闭 WLAN", mapOf("state" to ON_OFF), gate = listOf("wlan", "wifi", "wi-fi", "无线网")),
        DeviceTool("set_bluetooth", "打开或关闭蓝牙", mapOf("state" to ON_OFF), gate = listOf("蓝牙", "bluetooth")),
        DeviceTool("set_flashlight", "打开或关闭手电筒", mapOf("state" to ON_OFF), gate = listOf("手电", "闪光灯", "flashlight")),
        DeviceTool("set_dnd", "打开或关闭免打扰", mapOf("state" to ON_OFF), gate = listOf("免打扰", "勿扰")),
        DeviceTool("set_brightness", "把屏幕亮度设为某一档（需要\"修改系统设置\"授权，失败会退回界面操作）", mapOf("level" to LEVELS), gate = listOf("亮度")),
        DeviceTool(
            "set_volume", "把某一路音量设为某一档",
            mapOf("stream" to listOf("music", "ring", "alarm", "notification"), "level" to listOf("max", "high", "mid", "low", "mute")),
            gate = listOf("音量", "静音", "声音"),
        ),
        DeviceTool("call", "给目标里的联系人打电话（号码由代码查好，不用参数）", emptyMap(), gate = CALL_WORDS),
        DeviceTool("read_battery", "读取电量与是否在充电", emptyMap()),
        DeviceTool("read_time", "读取当前日期时间", emptyMap()),
        DeviceTool("read_device_info", "读取手机型号、品牌、安卓版本", emptyMap()),
        DeviceTool("read_network", "读取当前网络连接状态与类型", emptyMap()),
    )

    private val BY_ID = ALL.associateBy { it.id }

    /**
     * 目标原文点名了才可用（[DeviceTool.gate]）。只看用户目标，不看规划器写的步骤说明。
     * `call` 还要求代码已经查到号码（[ToolScope.callee]）。
     */
    fun available(goal: String, scope: ToolScope = ToolScope()): List<DeviceTool> {
        val folded = goal.lowercase()
        return ALL.filter { tool ->
            (tool.gate.isEmpty() || tool.gate.any { folded.contains(it) }) && (tool.id != "call" || scope.callee != null)
        }
    }

    /**
     * 把规划器/执行器给的 (tool, args) 校验成可执行调用。任何一项不合法返回 null：
     * 工具不存在、没被目标点名、参数不在枚举里、应用没安装。
     */
    fun resolve(tool: String, args: Map<String, String>, goal: String, apps: List<AppEntry>, scope: ToolScope = ToolScope()): Invocation? {
        val spec = BY_ID[tool] ?: return null
        if (spec !in available(goal, scope)) return null
        for ((name, allowed) in spec.args) {
            val value = args[name] ?: return null
            if (allowed.isNotEmpty() && value !in allowed) return null
        }
        return when (tool) {
            "open_settings" -> {
                val (label, action, _) = SETTINGS_PAGES[args.getValue("page")] ?: return null
                // 部分 ROM 的电池页由厂商省电应用提供（不是 com.android.settings），只要 intent 成功就算跳过去了
                Invocation(
                    tool, args, "ACTION_LAUNCH_APP", mapOf("intent_action" to action),
                    "直接打开「$label」设置页", "launch_success",
                    expectPackage = if (args["page"] == "battery") null else "settings",
                )
            }
            "open_app" -> {
                val pkg = args.getValue("package")
                val app = apps.firstOrNull { it.packageName == pkg } ?: return null
                Invocation(
                    tool, args, "ACTION_LAUNCH_APP", mapOf("package_name" to pkg),
                    "打开 ${app.label}", "launch_success", expectPackage = pkg,
                )
            }
            "call" -> {
                val callee = scope.callee ?: return null
                val direct = scope.allowDirectCall && callee.number !in ServiceNumbers.EMERGENCY
                Invocation(
                    tool, args, if (direct) "ACTION_CALL_DIRECT" else "ACTION_CALL_PHONE", mapOf("phone_number" to callee.number),
                    if (direct) "拨打 ${callee.name}" else "打开拨号盘并填好 ${callee.name} 的号码", "call_success",
                )
            }
            "set_wifi" -> toggle(tool, args, "ACTION_WIFI", "WLAN", "wifi_success", listOf("wifi_enabled"))
            "set_bluetooth" -> toggle(tool, args, "ACTION_BLUETOOTH", "蓝牙", "bluetooth_success", listOf("bluetooth_enabled"))
            "set_flashlight" -> toggle(tool, args, "ACTION_FLASHLIGHT", "手电筒", "flashlight_success", listOf("flashlight_on"))
            "set_dnd" -> toggle(tool, args, "ACTION_DND_MODE", "免打扰", "dnd_success", listOf("dnd_state"))
            "set_brightness" -> {
                val level = args.getValue("level")
                val value = mapOf("max" to 255, "high" to 190, "mid" to 128, "low" to 60, "min" to 10).getValue(level)
                Invocation(
                    tool, args, "ACTION_BRIGHTNESS", mapOf("action" to "set", "value" to value),
                    "亮度设为${levelName(level)}", "brightness_success", listOf("brightness_value"),
                )
            }
            "set_volume" -> {
                val level = args.getValue("level")
                val stream = args.getValue("stream")
                val value = mapOf("max" to 100, "high" to 75, "mid" to 50, "low" to 25, "mute" to 0).getValue(level)
                val name = mapOf("music" to "媒体", "ring" to "铃声", "alarm" to "闹钟", "notification" to "通知").getValue(stream)
                Invocation(
                    tool, args, "ACTION_VOLUME", mapOf("action" to "set", "stream" to stream, "value" to value),
                    "${name}音量设为${levelName(level)}", "volume_success", listOf("volume_current", "volume_max"),
                )
            }
            "read_battery" -> Invocation(
                tool, args, "ACTION_GET_BATTERY", emptyMap(), "读取电量", "battery_success",
                listOf("battery_level", "battery_charging"),
            )
            "read_time" -> Invocation(
                tool, args, "ACTION_GET_TIME", mapOf("format" to "yyyy-MM-dd HH:mm EEEE"), "读取当前时间", "time_success",
                listOf("current_time"),
            )
            "read_device_info" -> Invocation(
                tool, args, "ACTION_GET_DEVICE_INFO", emptyMap(), "读取设备信息", "device_success",
                listOf("device_brand", "device_model", "device_android_version"),
            )
            "read_network" -> Invocation(
                tool, args, "ACTION_NETWORK_STATUS", mapOf("action" to "all"), "读取网络状态", "network_success",
                listOf("network_connected", "network_type"),
            )
            else -> null
        }
    }

    /**
     * 执行器每一步也能用的工具快捷方式（USE_TOOL）：只放"跳到设置某页"和被目标点名的开关，
     * 并且只放跟目标/当前步骤相关的几个——全量列出 15 个设置页只会分散模型注意力。
     */
    fun shortcuts(goal: String, instruction: String, apps: List<AppEntry>, scope: ToolScope = ToolScope()): List<Invocation> {
        val text = "$goal $instruction".lowercase()
        val out = mutableListOf<Invocation>()
        SETTINGS_PAGES.forEach { (key, page) ->
            if (key != "main" && page.third.any { text.contains(it) }) {
                resolve("open_settings", mapOf("page" to key), goal, apps)?.let(out::add)
            }
        }
        // 提问类目标：直接给读数工具，不用去界面里翻（"电量多少""安卓版本号"）
        READ_HINTS.forEach { (tool, words) ->
            if (words.any { goal.lowercase().contains(it) }) resolve(tool, emptyMap(), goal, apps)?.let(out::add)
        }
        for (tool in available(goal, scope).filter { it.gate.isNotEmpty() }) {
            when (tool.id) {
                "call" -> resolve(tool.id, emptyMap(), goal, apps, scope)?.let(out::add)
                "set_brightness" -> LEVELS.filter { it == "max" || it == "min" || it == "mid" }
                    .mapNotNull { resolve(tool.id, mapOf("level" to it), goal, apps) }.forEach(out::add)
                "set_volume" -> listOf("max", "mute").mapNotNull {
                    resolve(tool.id, mapOf("stream" to "music", "level" to it), goal, apps)
                }.forEach(out::add)
                else -> ON_OFF.mapNotNull { resolve(tool.id, mapOf("state" to it), goal, apps) }.forEach(out::add)
            }
        }
        return out.take(MAX_SHORTCUTS)
    }

    /** 规划器提示词里的工具说明（只列目标点名可用的）。 */
    fun describeFor(goal: String, scope: ToolScope = ToolScope()): String = available(goal, scope).joinToString("\n") { tool ->
        val args = if (tool.args.isEmpty()) "无参数" else tool.args.entries.joinToString("，") { (k, v) ->
            if (v.isEmpty()) "$k=<已安装应用包名>" else "$k ∈ ${v.joinToString("/")}"
        }
        "- ${tool.id}：${tool.description}（$args）"
    }

    private fun toggle(tool: String, args: Map<String, String>, node: String, name: String, flag: String, outputs: List<String>): Invocation {
        val on = args.getValue("state") == "on"
        return Invocation(
            tool, args, node, mapOf("action" to if (on) "on" else "off"),
            "${if (on) "打开" else "关闭"}$name", flag, outputs,
        )
    }

    private fun levelName(level: String) = when (level) {
        "max" -> "最高"
        "high" -> "较高"
        "mid" -> "中等"
        "low" -> "较低"
        "min" -> "最低"
        "mute" -> "静音"
        else -> level
    }

    private const val MAX_SHORTCUTS = 6

    private val READ_HINTS = mapOf(
        "read_battery" to listOf("电量", "多少电", "充电"),
        "read_time" to listOf("几点", "时间", "日期", "星期"),
        "read_device_info" to listOf("型号", "安卓版本", "android 版本", "系统版本", "版本号", "品牌"),
        "read_network" to listOf("网络", "联网", "连着"),
    )
}
