package com.ailun.habitat.spec

import com.ailun.habitat.NodeHandlerFactory as F

/** INTERACTION 模块：无障碍交互节点。 */
internal object InteractionSpecs {
    val all: List<NodeSpec> = listOf(
        NodeSpec(
            F.ACTION_CLICK, "点击：按坐标、界面文字或 resource-id 点击目标",
            params = listOf(
                ParamSpec(
                    "target", ParamType.STRING,
                    "\"x,y\" 像素坐标；或界面文字（不区分大小写的子串匹配）；或 \"id:包名:id/xxx\" 按 resource-id 匹配。支持 \${var}",
                    required = true,
                ),
                ParamSpec(
                    "region", ParamType.STRING,
                    "仅文字/id 匹配：限定目标中心所在区域，top/bottom/left/right/center 或归一化 \"x1,y1,x2,y2\"（如 \"0,0.5,1,1\"=下半屏）；解析不了则不限定",
                ),
                ParamSpec("occurrence", ParamType.STRING_OR_NUMBER, "仅文字/id 匹配：region 过滤后取第几个（屏幕从上到下，0 起；0=第一个可见的）；越界直接失败", default = "0"),
                ParamSpec("offset_x", ParamType.STRING_OR_NUMBER, "仅文字/id 匹配：点击点相对目标中心的水平偏移像素", default = "0"),
                ParamSpec("offset_y", ParamType.STRING_OR_NUMBER, "仅文字/id 匹配：点击点相对目标中心的垂直偏移像素（向下为正）", default = "0"),
            ),
            outputs = listOf("click_success"),
            requires = "需无障碍",
            notes = "同一文字可能多处出现（多个「确定」「发送」）：用 region 限定区域或 occurrence 取第 N 个，避免点错。",
        ),
        NodeSpec(
            F.ACTION_SWIPE, "滑动：从 (x1,y1) 直线滑到 (x2,y2)",
            params = listOf(
                ParamSpec("x1", ParamType.STRING_OR_NUMBER, "起点 x（像素）", required = true),
                ParamSpec("y1", ParamType.STRING_OR_NUMBER, "起点 y（像素）", required = true),
                ParamSpec("x2", ParamType.STRING_OR_NUMBER, "终点 x（像素）", required = true),
                ParamSpec("y2", ParamType.STRING_OR_NUMBER, "终点 y（像素）", required = true),
                ParamSpec("duration", ParamType.STRING_OR_NUMBER, "滑动时长毫秒，限制 1-60000", default = "400"),
            ),
            outputs = listOf("swipe_success"),
            requires = "需无障碍",
        ),
        NodeSpec(
            F.ACTION_LONG_PRESS, "长按：按坐标或界面文字 / viewId 长按目标",
            params = listOf(
                ParamSpec(
                    "target", ParamType.STRING,
                    "\"x,y\" 像素坐标；或界面文字 / 完整 viewId（如 com.pkg:id/xxx，不加 id: 前缀）。支持 \${var}",
                    required = true,
                ),
                ParamSpec("duration", ParamType.STRING_OR_NUMBER, "按住时长毫秒（坐标模式和手势兜底时生效），限制 1-60000", default = "800"),
            ),
            outputs = listOf("long_press_success"),
            requires = "需无障碍",
            notes = "target 含逗号一律按坐标解析，解析失败直接失败（不像 ACTION_CLICK 会退回文字匹配）；不支持 region/occurrence。",
        ),
        NodeSpec(
            F.ACTION_INPUT_TEXT, "输入文字：写入输入框，可选输入后回车提交",
            params = listOf(
                ParamSpec("text", ParamType.STRING, "要输入的文字，支持 \${var}；为空时须 submit=true（只提交）或 allow_empty=true（清空）"),
                ParamSpec(
                    "mode", ParamType.STRING,
                    "a11y=无障碍写入（未生效时退回剪贴板粘贴，中文可用）；shell/ime=shell `input text`",
                    enum = listOf("a11y", "shell", "ime"), default = "a11y",
                ),
                ParamSpec(
                    "target", ParamType.STRING,
                    "\"x,y\" 坐标：定位包含该点的输入框并先聚焦（a11y 写入和 submit 用，shell/ime 写入不看它）；不传用当前焦点输入框。支持 \${var}",
                ),
                ParamSpec("submit", ParamType.BOOL, "输入后触发 IME 回车（搜索/发送）；优先无障碍 IME_ENTER，不支持时退回 shell keyevent 66", default = "false"),
                ParamSpec("allow_empty", ParamType.BOOL, "允许 text 为空时把输入框设为空串（清空）", default = "false"),
            ),
            outputs = listOf("input_success"),
            requires = "需无障碍（mode=shell/ime 需 Shizuku 或 root）",
            notes = "a11y 模式会读回输入框核对内容。shell/ime 的 input text 打不出中文等非 ASCII 字符。QQ/微信等输入框点过后常不报告焦点，传 target 坐标才写得进去。",
        ),
        NodeSpec(
            F.ACTION_GLOBAL_KEY, "全局按键：返回、桌面、最近任务、通知栏、回车等",
            params = listOf(
                ParamSpec(
                    "key", ParamType.STRING,
                    "按键。back/home/recents/notifications：先 shell keyevent，失败退回无障碍；quick_settings/power_dialog：仅无障碍；screenshot_toggle/enter/delete/space/tab：仅 shell",
                    required = true,
                    enum = listOf(
                        "back", "home", "recents", "notifications", "screenshot_toggle",
                        "enter", "delete", "space", "tab", "quick_settings", "power_dialog",
                    ),
                ),
            ),
            outputs = listOf("key_success"),
            requires = "需 Shizuku/root 或无障碍（见 key 说明）",
        ),
        NodeSpec(
            F.ACTION_FIND_ELEMENT, "找元素：限时轮询查找界面元素，输出是否找到、边界和文字",
            params = listOf(
                ParamSpec("selector", ParamType.STRING, "要找的文字 / 完整 viewId / 类名 / 内容描述（按 search_mode）。支持 \${var}", required = true),
                ParamSpec(
                    "search_mode", ParamType.STRING,
                    "text=界面文字（不区分大小写的子串）、id=完整 viewId、class=类名子串、content_desc=内容描述子串；主方式找不到会自动换其它方式再找",
                    enum = listOf("text", "id", "class", "content_desc"), default = "text",
                ),
                ParamSpec(
                    "region", ParamType.STRING,
                    "限定元素中心所在区域：top/bottom/left/right/center 或归一化 \"x1,y1,x2,y2\"；解析不了直接失败并写 element_error",
                ),
                ParamSpec("timeout_ms", ParamType.STRING_OR_NUMBER, "最长查找毫秒（每 100ms 重试一次）", default = "3000"),
                ParamSpec("output_var", ParamType.STRING, "可选：把元素详情 map（found/bounds/text/clickable/contentDescription/className/packageName）存到这个变量"),
            ),
            outputs = listOf("element_found", "element_error", "element_bounds", "element_text", "element_clickable"),
            requires = "需无障碍",
            notes = "element_bounds 是 \"left,top,right,bottom\" 四个数，不能直接当 ACTION_CLICK 的 target（会点到左上角）。",
        ),
        NodeSpec(
            F.ACTION_GLOBAL_NAV, "无障碍全局动作：返回、桌面、最近任务、通知栏、快捷设置、电源菜单、锁屏、截屏",
            params = listOf(
                ParamSpec(
                    "action", ParamType.STRING, "全局动作（纯无障碍 performGlobalAction，不需要 Shizuku）",
                    required = true,
                    enum = listOf("back", "home", "recents", "notifications", "quick_settings", "power_dialog", "lock_screen", "take_screenshot"),
                ),
            ),
            outputs = listOf("nav_success", "nav_error"),
            requires = "需无障碍",
        ),
        NodeSpec(
            F.ACTION_WAIT_FOR_ELEMENT, "等元素：每 500ms 轮询，等待界面元素出现或消失",
            params = listOf(
                ParamSpec("selector", ParamType.STRING, "关键字 / 完整 viewId（如 com.pkg:id/xxx）/ 类名（按 mode）。支持 \${var}", required = true),
                ParamSpec(
                    "mode", ParamType.STRING,
                    "text=可见文字或内容描述包含关键字（不区分大小写）、id=完整 viewId、class=类名子串",
                    enum = listOf("text", "id", "class"), default = "text",
                ),
                ParamSpec("wait_for", ParamType.STRING, "等出现还是等消失", enum = listOf("appear", "disappear"), default = "appear"),
                ParamSpec("timeout_ms", ParamType.STRING_OR_NUMBER, "最长等待毫秒", default = "10000", aliases = listOf("timeout")),
                ParamSpec("output_var", ParamType.STRING, "结果变量名", default = "element_found"),
            ),
            outputs = listOf("element_found"),
            requires = "需无障碍",
            notes = "结果为 true 表示条件满足：wait_for=disappear 时 true 是「已消失」。只查当前活动窗口。",
        ),
    )
}
