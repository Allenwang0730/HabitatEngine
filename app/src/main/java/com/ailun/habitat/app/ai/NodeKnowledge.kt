package com.ailun.habitat.app.ai

/**
 * 节点百科 + 安卓操作指南（单一数据源）。
 * WorkflowAIGenerator 从本对象动态生成 LLM 系统提示词 —— 加新节点只需在此加一个条目，提示词自动包含。
 *
 * NodeDoc:
 * - type: 节点类型（与 NodeHandlerFactory 常量一致）
 * - params: 参数说明（供 LLM 填 params）
 * - usage: 用途/语义
 * - before: 前置条件（如"需无障碍"/"需权限"），空表示无
 */
object NodeKnowledge {

    data class NodeDoc(val type: String, val params: String, val usage: String, val before: String = "")

    /** 常用节点百科（LLM 生成工作流时可选用的节点）。 */
    val nodes: List<NodeDoc> = listOf(
        NodeDoc("ACTION_SET_VARIABLE", "name, value, type=string/int/bool", "设变量"),
        NodeDoc("ACTION_LOG", "message（支持 \${var}）", "记录日志"),
        NodeDoc("ACTION_DELAY", "millis", "延时"),
        NodeDoc("CONDITION_SWITCH", "expression, branches={\"true\":id,\"false\":id}", "条件分支"),
        NodeDoc("ACTION_TOAST", "message", "弹提示"),
        NodeDoc("ACTION_GET_TIME", "format, output_var", "取时间"),
        NodeDoc("ACTION_MATH", "operation=add/subtract/multiply/divide, a, b, output_var", "数学"),
        NodeDoc("ACTION_TEXT_OPERATION", "action=uppercase/lowercase/trim/replace, input, output_var", "文本处理"),
        NodeDoc("ACTION_CLIPBOARD", "action=get/set, text, output_var", "剪贴板"),
        NodeDoc("ACTION_PARSE_JSON", "json, path(如 data.items[0]), output_var", "解析 JSON"),
        NodeDoc("ACTION_HTTP_REQUEST", "url, method=GET/POST, output_var, timeout", "HTTP 请求", "需网络；对不稳定第三方设 timeout + on_error=skip"),
        NodeDoc("ACTION_LAUNCH_APP", "package_name", "打开应用", ""),
        NodeDoc("ACTION_CLICK", "target（\"x,y\"坐标 或 界面文字）, region=top/bottom/left/right/center 或归一化\"x1,y1,x2,y2\", occurrence（第 N 个,0 起）", "点击", "需无障碍"),
        NodeDoc("ACTION_INPUT_TEXT", "text, mode=a11y", "输入文字", "需无障碍"),
        NodeDoc("ACTION_READ_SCREEN", "keyword, output_var", "读屏幕(变量 screen_data=是否找到)", "需无障碍"),
        NodeDoc("ACTION_FIND_ELEMENT", "selector, search_mode=text, region(可选), timeout_ms, output_var", "找元素(变量 element_found)", "需无障碍"),
        NodeDoc("ACTION_GLOBAL_KEY", "key=back/home/recents", "全局按键", "需无障碍"),
        NodeDoc("ACTION_SHELL", "command, mode=auto", "shell 命令", "需 Shizuku/root，无则 Runtime.exec 兜底"),
        NodeDoc("ACTION_GET_BATTERY", "output_var", "电量(battery_level)"),
        NodeDoc("ACTION_NETWORK_STATUS", "action=all", "网络(network_type)"),
        NodeDoc("ACTION_GET_DEVICE_INFO", "", "设备信息"),
        NodeDoc("ACTION_SCREENSHOT", "format=base64, output_var", "截图", "需截图权限"),
        NodeDoc("ACTION_VIBRATE", "duration", "振动"),
        NodeDoc("ACTION_TEXT_TO_SPEECH", "text, language", "语音"),
        NodeDoc("ACTION_FILE_OPERATION", "action=read/write/exists, path, content, output_var", "文件"),
        NodeDoc("ACTION_AI_CHAT", "prompt, output_var", "本地 AI 对话"),
        NodeDoc("ACTION_CALL_DIRECT", "phone_number, sim", "直接拨号（最优先，不用 UI 模拟）", "需 CALL_PHONE"),
        NodeDoc("ACTION_CALL_PHONE", "phone_number", "拨打（走 Intent）", "需 CALL_PHONE"),
        NodeDoc("ACTION_SEND_NOTIFICATION", "title, message, channel_id", "发通知"),
        NodeDoc("ACTION_SWIPE", "x1,y1,x2,y2,duration_ms", "滑动", "需无障碍"),
        NodeDoc("ACTION_MEDIA_CONTROL", "cmd=play/pause/next", "媒体控制"),
    )

    /** 常见包名映射（LLM 常不知道 App 的包名）。 */
    val packageHints: Map<String, String> = mapOf(
        "设置" to "com.android.settings",
        "微信" to "com.tencent.mm",
        "QQ" to "com.tencent.mobileqq",
        "拨号" to "com.android.dialer",
        "相机" to "com.android.camera",
        "计算器" to "com.android.calculator2",
        "系统设置" to "com.android.settings",
    )

    /** 生成工作流的优先规则 / 安卓操作指南。 */
    val rules: List<String> = listOf(
        "能用'直接 API'节点就不用 UI 点击模拟。例如拨号用 ACTION_CALL_DIRECT，不要用 LAUNCH_APP+CLICK+INPUT_TEXT 一条龙点 UI。",
        "打开应用、发通知、截图、设备信息等都有直接节点；UI 点击（CLICK/SWIPE/INPUT_TEXT）只在没有专门节点（如填表单、点特殊按钮）时才用。",
        "UI 文本可能多处出现（如多个'确定'/'发送'）。用 region 限定区域（\"bottom\"=下半屏 / \"top\"=上半屏 / 归一化\"0,0.5,1,1\"=下半屏）或 occurrence 取第 N 个（屏幕从上到下，0 起），避免点错。",
        "网络节点（HTTP_REQUEST/GET_WEATHER/GEOCODE）需网络；对不稳定第三方服务设合理 timeout 并用 on_error=skip 容错。",
    )

    /** few-shot 示例（JSON 字符串）。 */
    val examples: List<String> = listOf(
        """需求"启动应用并显示提示"→{"name":"启动设置","description":"打开系统设置后弹提示","workflow":{"start_node_id":"s1","nodes":{"s1":{"id":"s1","type":"ACTION_LAUNCH_APP","params":{"package_name":"com.android.settings"},"next":"s2"},"s2":{"id":"s2","type":"ACTION_DELAY","params":{"millis":1500},"next":"s3"},"s3":{"id":"s3","type":"ACTION_TOAST","params":{"message":"已打开设置"}}}}}""",
        """需求"读取网络状态，wifi则提示当前WiFi否则提示没有网络"→{"name":"网络检查","workflow":{"start_node_id":"n1","nodes":{"n1":{"id":"n1","type":"ACTION_NETWORK_STATUS","params":{"action":"all"},"next":"n2"},"n2":{"id":"n2","type":"CONDITION_SWITCH","params":{"expression":"network_type == wifi"},"branches":{"true":"n3","false":"n4"}},"n3":{"id":"n3","type":"ACTION_TOAST","params":{"message":"当前WiFi"}},"n4":{"id":"n4","type":"ACTION_TOAST","params":{"message":"没有网络"}}}}}""",
        """需求"拨打10086并开启录音"→{"name":"拨号录音","workflow":{"start_node_id":"s1","nodes":{"s1":{"id":"s1","type":"ACTION_CALL_DIRECT","params":{"phone_number":"10086"},"next":"s2"},"s2":{"id":"s2","type":"ACTION_TOAST","params":{"message":"已拨打10086"}}}}}""",
    )
}
