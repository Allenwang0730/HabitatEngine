package com.ailun.habitat.spec

import com.ailun.habitat.NodeHandlerFactory as F

/** SENSOR：读屏、短信、前台应用、应用搜索、定位、通知。 */
internal object SensorSpecs {
    val all: List<NodeSpec> = listOf(
        NodeSpec(
            F.ACTION_READ_SCREEN, "在当前界面找关键字，或抓取全屏可见文字",
            params = listOf(
                ParamSpec("keyword", ParamType.STRING, "要找的文字（不区分大小写，匹配文本或描述），支持 \${var}；为空时 screen_data 恒为 false"),
                ParamSpec("output_var", ParamType.STRING, "给了就把全屏可见文字（换行分隔）存到该变量"),
            ),
            outputs = listOf("screen_data"),
            requires = "需无障碍",
            notes = "screen_data 是「是否找到 keyword」的布尔值，不是屏幕文字；要文字请设 output_var。",
        ),
        NodeSpec(
            F.ACTION_READ_SMS, "读收件箱短信：按发件人/内容过滤，可提取验证码",
            params = listOf(
                ParamSpec(
                    "filter_by", ParamType.STRING, "latest=最新一条，sender=按发件人，content=按内容，both=两者都要匹配",
                    enum = listOf("latest", "sender", "content", "both"), default = "latest",
                ),
                ParamSpec("sender", ParamType.STRING, "发件人包含的文字（不区分大小写），支持 \${var}；filter_by=sender/both 时生效"),
                ParamSpec("content", ParamType.STRING, "内容包含的文字（不区分大小写），支持 \${var}；filter_by=content/both 时生效；extract_code=true 时不用"),
                ParamSpec("max_scan", ParamType.STRING_OR_NUMBER, "最多扫描最近几条，范围 1–500", default = "20"),
                ParamSpec("extract_code", ParamType.BOOL, "true=只接受能提取到验证码的短信（4–8 位数字，或含数字的 6–12 位字母数字）", default = "false"),
                ParamSpec("within_minutes", ParamType.STRING_OR_NUMBER, "只看最近 N 分钟内的短信；不填不限"),
            ),
            outputs = listOf("sms_found", "sms_error", "sms_date", "sms_sender", "sms_content", "verification_code"),
            requires = "需 READ_SMS 权限（没有时回退 Shizuku/root shell）",
            notes = "给了 sender/content 必须同时设 filter_by=sender/content/both，默认 latest 会忽略它们。" +
                "extract_code=true 时「内容匹配」改为「提取到验证码」。sms_date 为毫秒时间戳。",
        ),
        NodeSpec(
            F.ACTION_GET_APP_INFO, "读取前台应用包名和 Activity（经无障碍）",
            params = listOf(
                ParamSpec("output_var", ParamType.STRING, "包名存到的变量名", default = "current_app"),
                ParamSpec("activity_var", ParamType.STRING, "Activity 类名存到的变量名", default = "current_activity"),
            ),
            outputs = listOf("current_app", "current_activity"),
            requires = "需无障碍",
            notes = "无障碍未开时写 unknown；需要 shell 兜底请用 ACTION_FOREGROUND_APP。",
        ),
        NodeSpec(
            F.ACTION_FOREGROUND_APP, "获取前台应用包名/Activity（先无障碍，拿不到再走 shell dumpsys）",
            params = listOf(
                ParamSpec("output_var", ParamType.STRING, "包名另存到的变量名", default = "foreground_package"),
                ParamSpec("activity_var", ParamType.STRING, "Activity 类名另存到的变量名", default = "foreground_activity"),
                ParamSpec("use_shell", ParamType.BOOL, "true=跳过无障碍，直接用 shell", default = "false"),
            ),
            outputs = listOf("foreground_package", "foreground_success", "foreground_activity", "foreground_class"),
            requires = "需无障碍或 Shizuku/root",
            notes = "拿不到时写 unknown 且 foreground_success=false；foreground_class 为短类名。节点必须带 params（可为 {}），缺 params 字段时什么都不做。",
        ),
        NodeSpec(
            F.ACTION_APP_SEARCH, "搜索已安装应用，结果为 JSON 数组",
            params = listOf(
                ParamSpec("query", ParamType.STRING, "匹配包名或应用名（不区分大小写），支持 \${var}；不填返回全部"),
                ParamSpec("include_system", ParamType.BOOL, "是否包含系统应用（只认 true / \"true\"）", default = "false"),
                ParamSpec("output_var", ParamType.STRING, "结果另存到的变量名（app_list 总会写）", default = "app_list"),
                ParamSpec("max_results", ParamType.INT, "最多返回条数", default = "100"),
                ParamSpec(
                    "sort_by", ParamType.STRING, "name=按应用名，package=按包名，install_time=按安装时间（新的在前）",
                    enum = listOf("name", "package", "install_time"), default = "name",
                ),
            ),
            outputs = listOf("app_list", "app_count", "app_search_success", "app_search_error"),
            notes = "每项含 package、name、is_system、version_name、version_code。Android 11+ 受包可见性限制，没有桌面图标的应用可能搜不到。" +
                "节点必须带 params（可为 {}），缺 params 字段时什么都不做。",
        ),
        NodeSpec(
            F.ACTION_GET_LOCATION, "读取系统最后一次已知位置（GPS/网络取较新者）",
            params = listOf(
                ParamSpec("output_var", ParamType.STRING, "\"纬度,经度\" 字符串存到的变量名", default = "location_summary"),
            ),
            outputs = listOf("location_success", "location_error", "latitude", "longitude", "location_accuracy", "location_time", "location_summary"),
            requires = "需定位权限",
            notes = "不主动定位：从没定位过会失败，结果可能是旧位置（看 location_time 毫秒时间戳）。location_accuracy 单位米。",
        ),
        NodeSpec(
            F.ACTION_READ_NOTIFICATIONS, "读取通知栏当前通知列表（JSON 数组）",
            params = listOf(
                ParamSpec("output_var", ParamType.STRING, "JSON 另存到的变量名（notifications_json 总会写）", default = "notifications_json"),
                ParamSpec("limit", ParamType.STRING_OR_NUMBER, "最多条数，范围 1–50", default = "20"),
                ParamSpec("package", ParamType.STRING, "只要该包名的通知（精确匹配），支持 \${var}"),
            ),
            outputs = listOf("notifications_success", "notification_success", "notifications_json", "notification_count", "notification_error"),
            requires = "需通知使用权",
            notes = "每项含 package、title、text、post_time。notification_success 是 notifications_success 的旧名，值相同。" +
                "宿主未注入通知监听源时只能读到本应用自己的通知，并按失败处理。",
        ),
    )
}
