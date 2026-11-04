package com.ailun.habitat.spec

import com.ailun.habitat.NodeHandlerFactory as F

/** 宿主 :app 注册的节点（handler 在 app/.../handlers/，lib 单独使用时不可用）。 */
internal object HostSpecs {
    val all: List<NodeSpec> = listOf(
        NodeSpec(
            F.ACTION_SEND_NOTIFICATION, "发一条系统通知",
            params = listOf(
                ParamSpec("title", ParamType.STRING, "通知标题", default = "Habitat 通知"),
                ParamSpec("message", ParamType.STRING, "通知正文", default = "这是一条来自 Habitat 的消息。"),
            ),
            requires = "宿主 App 注册；Android 13+ 需通知权限（POST_NOTIFICATIONS）",
            notes = "title / message 原样显示，不做 \${var} 插值。不写任何输出变量。",
        ),
        NodeSpec(
            F.ACTION_DYNAMIC_ISLAND, "屏幕顶部弹出灵动岛样式的胶囊提示，到时自动消失",
            params = listOf(
                ParamSpec("message", ParamType.STRING, "显示的文字（单行，过长截断），支持 \${var}", required = true),
                ParamSpec("duration_ms", ParamType.INT, "显示毫秒数", default = "3000"),
            ),
            requires = "宿主 App 注册；需悬浮窗权限",
            notes = "duration_ms 必须是数字，写成字符串会被忽略按 3000。不阻塞，显示后立即执行下一节点；没有悬浮窗权限时静默失败，不写任何变量。",
        ),
        NodeSpec(
            F.ACTION_MINI_SCREEN, "悬浮小屏：给用户实时显示文字、图片、进度",
            params = listOf(
                ParamSpec(
                    "action", ParamType.STRING,
                    "show=替换内容，append=追加一段，progress=只更新进度（和标题），hide=隐藏但保留内容，clear=隐藏并清空",
                    enum = listOf("show", "append", "progress", "hide", "clear"), default = "show",
                ),
                ParamSpec("title", ParamType.STRING, "标题，支持 \${var}，最多 60 字"),
                ParamSpec("text", ParamType.STRING, "正文，支持 \${var}，最多 4000 字"),
                ParamSpec("image", ParamType.STRING, "图片：绝对路径（经路径守卫）或 base64（可带 data:image/...;base64, 前缀），支持 \${var}"),
                ParamSpec("progress", ParamType.STRING_OR_NUMBER, "进度 0–100；action=progress 时必填"),
                ParamSpec("position", ParamType.STRING, "位置；不填沿用上次（初始 top），用户拖动过则按用户位置", enum = listOf("top", "center", "bottom")),
                ParamSpec("duration_ms", ParamType.STRING_OR_NUMBER, "大于 0 时到时自动隐藏；0=一直显示", default = "0"),
            ),
            outputs = listOf("mini_screen_success", "mini_screen_shown", "mini_screen_error"),
            requires = "宿主 App 注册；需开启 Habitat 无障碍服务或悬浮窗权限",
            notes = "show/append 时 text、image、title、progress 至少给一个。不阻塞。" +
                "用户在本次运行中关掉小屏后，后续写入只更新内容不再弹出（mini_screen_shown=false）。来源标签由系统标注，不能用参数指定。",
        ),
    )
}
