package com.ailun.habitat.spec

import com.ailun.habitat.NodeHandlerFactory as F

/** TELEPHONY：拨号、通话状态、SIM、通话记录。 */
internal object TelephonySpecs {
    val all: List<NodeSpec> = listOf(
        NodeSpec(
            F.ACTION_CALL_PHONE, "打开系统拨号界面并填好号码（不拨出，需用户自己点拨打）",
            params = listOf(
                ParamSpec("phone_number", ParamType.STRING, "电话号码，支持 \${var}", required = true),
            ),
            outputs = listOf("call_success"),
            notes = "要直接拨出请用 ACTION_CALL_DIRECT（推荐），不要用 CALL_PHONE + 点击拨打键模拟。",
        ),
        NodeSpec(
            F.ACTION_CALL_DIRECT, "直接拨出电话（TelecomManager.placeCall，无需点 UI，后台可用）",
            params = listOf(
                ParamSpec("phone_number", ParamType.STRING, "电话号码，支持 \${var}；只允许数字和 +*#,;()- 空格，最多 40 位", required = true),
                ParamSpec("sim", ParamType.STRING_OR_NUMBER, "双卡选卡：auto=系统默认，1=卡1，2=卡2", default = "auto"),
            ),
            outputs = listOf("call_success", "call_error"),
            requires = "需 CALL_PHONE 权限",
            notes = "拨号优先用本节点。提交拨号即返回，不等接通；要等通话结束接 ACTION_WAIT_CALL_END。",
        ),
        NodeSpec(
            F.ACTION_WAIT_CALL_END, "阻塞等待当前通话挂断（每秒轮询通话状态）",
            params = listOf(
                ParamSpec("timeout_ms", ParamType.STRING_OR_NUMBER, "最长等待毫秒，超时 call_ended=false", default = "600000"),
            ),
            outputs = listOf("call_ended", "call_error"),
            requires = "需 READ_PHONE_STATE 权限",
            notes = "进入时没有通话且 3 秒内也没出现通话，直接返回 call_ended=true（视为已结束）。",
        ),
        NodeSpec(
            F.ACTION_WAIT_CALL_ANSWER, "阻塞等待通话进入通话中（OFFHOOK）",
            params = listOf(
                ParamSpec("timeout_ms", ParamType.STRING_OR_NUMBER, "最长等待毫秒，超时 call_answered=false", default = "120000"),
            ),
            outputs = listOf("call_answered", "call_error"),
            requires = "需 READ_PHONE_STATE 权限",
            notes = "5 秒内既没响铃也没进入通话就返回 false，不会等满超时。去电一拨出系统即为 OFFHOOK，不代表对方已接听；来电则表示已接起。",
        ),
        NodeSpec(
            F.ACTION_GET_CALL_STATE, "读取当前电话状态",
            outputs = listOf("call_state", "call_state_name", "call_success", "call_error"),
            requires = "需 READ_PHONE_STATE 权限",
            notes = "call_state：0=空闲 1=响铃 2=通话中；call_state_name：idle/ringing/offhook。",
        ),
        NodeSpec(
            F.ACTION_GET_SIM_INFO, "读取 SIM 卡数量、运营商、号码",
            params = listOf(
                ParamSpec("output_var", ParamType.STRING, "概要字符串存到的变量名", default = "sim_summary"),
            ),
            outputs = listOf("sim_count", "sim_carriers", "sim_numbers", "sim_summary", "sim_success", "sim_error"),
            requires = "需 READ_PHONE_STATE 权限",
            notes = "sim_carriers / sim_numbers 为逗号分隔；很多 SIM 卡不存本机号码，sim_numbers 常为空。",
        ),
        NodeSpec(
            F.ACTION_READ_CALL_LOG, "读最近一条通话记录的号码",
            params = listOf(
                ParamSpec(
                    "direction", ParamType.STRING, "incoming=已接来电，outgoing=去电，all=全部（含未接/拒接）",
                    enum = listOf("incoming", "outgoing", "all"), default = "incoming",
                ),
                ParamSpec("output_var", ParamType.STRING, "号码另存到的变量名（call_log_number 总会写）", default = "call_log_number"),
            ),
            outputs = listOf("call_log_found", "call_log_number", "call_log_type", "call_log_date"),
            requires = "需 READ_CALL_LOG 权限",
            notes = "通话记录一般在通话结束后才写入，响铃时多半查不到。call_log_type：1=来电 2=去电 3=未接 5=拒接；call_log_date 为毫秒时间戳。",
        ),
    )
}
