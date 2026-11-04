package com.ailun.habitat.app.ai

/**
 * 生成工作流用的安卓操作指南：触发器写法、常见包名、优先规则、示例。
 * 节点和参数来自 [com.ailun.habitat.spec.NodeSpecs]（加新节点改规格即可，提示词自动包含）。
 */
object NodeKnowledge {

    // 节点清单与参数不再写在这里：来自节点规格 com.ailun.habitat.spec.NodeSpecs（MCP、lint、docs/NODES.md 同一来源）。

    data class TriggerDoc(val example: String, val variables: String)

    /** 触发器（写在 workflow 里，与 start_node_id 同级）与触发时注入的变量：变量名来自 TriggerManager（trigger_ + 事件字段）。 */
    val triggers: List<TriggerDoc> = listOf(
        TriggerDoc("\"trigger\":{\"type\":\"notification\"}（可加 \"package\":\"com.xx\" 只听某个应用）", "\${trigger_title} \${trigger_text} \${trigger_big_text} \${trigger_package}"),
        TriggerDoc("\"trigger\":{\"type\":\"sms\"}", "\${trigger_sender} \${trigger_body}"),
        TriggerDoc("每天/每周几的固定时刻：\"trigger\":{\"type\":\"timer\",\"at\":\"08:00\",\"days\":\"weekdays\"}（days 可省=每天；weekdays/weekends 或 \"1,3,5\"，1=周一）", "无"),
        TriggerDoc("按间隔重复：\"trigger\":{\"type\":\"timer\",\"interval_ms\":3600000,\"repeat\":true}（「每隔 N 分钟/小时」才用这个；「每天几点」用 at）", "无"),
        TriggerDoc("\"trigger\":{\"type\":\"clipboard\"}", "\${trigger_text}"),
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
        "拨号用 ACTION_CALL_DIRECT（直接拨出，最优先，走系统 TelecomManager.placeCall 可靠）；它需要 CALL_PHONE 权限。若不希望直拨（或权限受限）才用 ACTION_CALL_PHONE（打开拨号器填号再点'拨打'）。",
        "网络节点（HTTP_REQUEST/GET_WEATHER/GEOCODE）需网络；对不稳定第三方服务设合理 timeout 并用 on_error=skip 容错。",
    )

    /** few-shot 示例（JSON 字符串）。 */
    val examples: List<String> = listOf(
        """需求"每天早上 8 点提醒我喝水并震动一下"→{"name":"喝水提醒","workflow":{"trigger":{"type":"timer","at":"08:00"},"start_node_id":"s1","nodes":{"s1":{"id":"s1","type":"ACTION_SEND_NOTIFICATION","params":{"title":"喝水提醒","message":"该喝水了"},"next":"s2"},"s2":{"id":"s2","type":"ACTION_VIBRATE","params":{"duration":500}}}}}""",
        """需求"收到含验证码的短信时，在小屏上显示 20 秒"→{"name":"验证码提醒","workflow":{"trigger":{"type":"sms"},"start_node_id":"s1","nodes":{"s1":{"id":"s1","type":"CONDITION_SWITCH","params":{"expression":"trigger_body contains 验证码"},"branches":{"true":"s2"}},"s2":{"id":"s2","type":"ACTION_MINI_SCREEN","params":{"title":"验证码","text":"${'$'}{trigger_body}","duration_ms":20000}}}}}""",
        """需求"让 AI 总结剪贴板内容并显示在小屏上，同时存到工作区"→{"name":"剪贴板摘要","workflow":{"start_node_id":"s1","nodes":{"s1":{"id":"s1","type":"ACTION_CLIPBOARD","params":{"action":"get","output_var":"clip"},"next":"s2"},"s2":{"id":"s2","type":"ACTION_AI_CHAT","params":{"prompt":"用三句话总结：${'$'}{clip}","output_var":"summary"},"next":"s3"},"s3":{"id":"s3","type":"ACTION_MINI_SCREEN","params":{"title":"剪贴板摘要","text":"${'$'}{summary}"},"next":"s4"},"s4":{"id":"s4","type":"ACTION_WORKSPACE","params":{"action":"write","name":"剪贴板摘要","content":"${'$'}{summary}","tags":"摘要"}}}}}""",
        """需求"启动应用并显示提示"→{"name":"启动设置","description":"打开系统设置后弹提示","workflow":{"start_node_id":"s1","nodes":{"s1":{"id":"s1","type":"ACTION_LAUNCH_APP","params":{"package_name":"com.android.settings"},"next":"s2"},"s2":{"id":"s2","type":"ACTION_DELAY","params":{"millis":1500},"next":"s3"},"s3":{"id":"s3","type":"ACTION_TOAST","params":{"message":"已打开设置"}}}}}""",
        """需求"读取网络状态，wifi则提示当前WiFi否则提示没有网络"→{"name":"网络检查","workflow":{"start_node_id":"n1","nodes":{"n1":{"id":"n1","type":"ACTION_NETWORK_STATUS","params":{"action":"all"},"next":"n2"},"n2":{"id":"n2","type":"CONDITION_SWITCH","params":{"expression":"network_type == wifi"},"branches":{"true":"n3","false":"n4"}},"n3":{"id":"n3","type":"ACTION_TOAST","params":{"message":"当前WiFi"}},"n4":{"id":"n4","type":"ACTION_TOAST","params":{"message":"没有网络"}}}}}""",
        """需求"拨打10086并开启录音"→{"name":"拨号录音","description":"直拨10086并点录音","workflow":{"start_node_id":"s1","nodes":{"s1":{"id":"s1","type":"ACTION_CALL_DIRECT","params":{"phone_number":"10086"},"next":"s2"},"s2":{"id":"s2","type":"ACTION_DELAY","params":{"millis":3000},"next":"s3"},"s3":{"id":"s3","type":"ACTION_CLICK","params":{"target":"录音"}}}}}}""",
    )
}
