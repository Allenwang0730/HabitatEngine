package com.ailun.habitat.spec

import com.ailun.habitat.NodeHandlerFactory as F

/** SYSTEM 模块：shell、应用、屏幕、无线、音量亮度、闹钟、免打扰、分享、广播、媒体键。 */
internal object SystemSpecs {
    val all: List<NodeSpec> = listOf(
        NodeSpec(
            F.ACTION_SHELL, "执行 shell 命令，输出命令结果",
            params = listOf(
                ParamSpec(
                    "command", ParamType.STRING,
                    "shell 命令；其中的 \${var} 替换后自动加单引号转义（作为一个完整参数），不要再给它加引号",
                    required = true,
                ),
                ParamSpec("mode", ParamType.STRING, "root=以 root 执行；auto/shizuku=由 shell 执行器决定通道（Habitat 默认 Shizuku）", enum = listOf("auto", "shizuku", "root"), default = "auto"),
                ParamSpec("raw_interpolation", ParamType.BOOL, "true=\${var} 原样拼入命令不转义（有注入风险）", default = "false"),
                ParamSpec("timeout_ms", ParamType.STRING_OR_NUMBER, "超时毫秒，只在宿主没提供 shell 执行器、走本地 sh 兜底时生效", default = "60000"),
            ),
            outputs = listOf("shell_output", "shell_success"),
            requires = "需 Shizuku 或 root",
            notes = "失败时 shell_output 以 \"Error:\" 开头（非 0 退出码、超时、Shizuku 不可用），shell_success 据此判断。",
        ),
        NodeSpec(
            F.ACTION_LAUNCH_APP, "打开应用，或打开系统设置的某一页",
            params = listOf(
                ParamSpec("package_name", ParamType.STRING, "应用包名，支持 \${var}；不给 intent_action 时必填"),
                ParamSpec("activity", ParamType.STRING, "可选：完整 Activity 类名，直接启动该页面"),
                ParamSpec(
                    "intent_action", ParamType.STRING,
                    "打开系统设置页，只允许 android.settings.*（如 android.settings.BLUETOOTH_SETTINGS）或 android.intent.action.POWER_USAGE_SUMMARY；给了就忽略 package_name",
                ),
            ),
            outputs = listOf("launch_success", "launch_error", "launch_has_overlay", "launch_package"),
            notes = "Android 10+ 后台启动可能被系统静默拦截而 launch_success 仍为 true；launch_has_overlay 表示是否有悬浮窗权限（后台启动的豁免条件之一）。",
        ),
        NodeSpec(
            F.ACTION_FORCE_STOP_APP, "强制停止应用（am force-stop）",
            params = listOf(
                ParamSpec("package_name", ParamType.STRING, "要停止的应用包名（须为合法包名），支持 \${var}", required = true),
                ParamSpec("as_root", ParamType.BOOL, "以 root 执行", default = "false"),
            ),
            outputs = listOf("force_stop_success"),
            requires = "需 Shizuku 或 root",
        ),
        NodeSpec(
            F.ACTION_SCREEN_WAKE, "亮屏、息屏、亮屏并解锁",
            params = listOf(
                ParamSpec(
                    "action", ParamType.STRING,
                    "wake=亮屏；sleep=息屏；wake_and_unlock=亮屏后发菜单键划开锁屏，给了 password 再输入并回车",
                    required = true, enum = listOf("wake", "sleep", "wake_and_unlock"),
                ),
                ParamSpec("password", ParamType.STRING, "可选：wake_and_unlock 的锁屏密码，只能是 1-64 位字母数字（格式不对整个节点直接失败）"),
            ),
            outputs = listOf("screen_wake_success"),
            requires = "sleep 和解锁需 Shizuku 或 root；wake 无 shell 时退回 WakeLock",
            notes = "wake_and_unlock 不核对是否真的解锁（按键序列发完即算成功）；图案锁解不了。",
        ),
        NodeSpec(
            F.ACTION_WIFI, "WiFi 开关、切换、查询状态",
            params = listOf(
                ParamSpec("action", ParamType.STRING, "on/off/toggle 开关；status 查询（只有 status 写 wifi_enabled/wifi_state）", required = true, enum = listOf("on", "off", "toggle", "status")),
            ),
            outputs = listOf("wifi_success", "wifi_enabled", "wifi_state"),
            requires = "开关需 Shizuku 或 root（Android 10+ 系统 API 对普通应用无效）；status 不需要",
            notes = "wifi_state 是 WifiManager 数字码：0=关闭中 1=已关 2=开启中 3=已开 4=未知；开启中也算 wifi_enabled=true。",
        ),
        NodeSpec(
            F.ACTION_BLUETOOTH, "蓝牙开关、切换、查询状态",
            params = listOf(
                ParamSpec("action", ParamType.STRING, "on/off/toggle 开关；status 查询（只有 status 写 bluetooth_enabled）", required = true, enum = listOf("on", "off", "toggle", "status")),
            ),
            outputs = listOf("bluetooth_success", "bluetooth_enabled"),
            requires = "开关需 Shizuku 或 root（Android 13+ 系统 API 对普通应用无效）；status 不需要",
        ),
        NodeSpec(
            F.ACTION_VOLUME, "音量：设置、读取、增减、静音",
            params = listOf(
                ParamSpec("action", ParamType.STRING, "set=按百分比设置；get=读取；up/down=调一档；mute/unmute=静音/取消", required = true, enum = listOf("set", "get", "up", "down", "mute", "unmute")),
                ParamSpec(
                    "stream", ParamType.STRING, "音频流；call 与 voice_call 相同，无法识别的值按 music",
                    enum = listOf("music", "ring", "alarm", "notification", "system", "call", "voice_call", "dtmf", "accessibility"),
                    default = "music",
                ),
                ParamSpec("value", ParamType.STRING_OR_NUMBER, "音量百分比 0-100（超出截断），action=set 时必填"),
            ),
            outputs = listOf("volume_success", "volume_current", "volume_max"),
            notes = "value 是百分比，而 volume_current/volume_max 是系统档位（如 0-15），两者不能直接比较。",
        ),
        NodeSpec(
            F.ACTION_BRIGHTNESS, "屏幕亮度：设置、读取、开关自动亮度",
            params = listOf(
                ParamSpec("action", ParamType.STRING, "set=设置亮度；get=读取亮度和模式；auto_on/auto_off=开/关自动亮度", required = true, enum = listOf("set", "get", "auto_on", "auto_off")),
                ParamSpec("value", ParamType.STRING_OR_NUMBER, "亮度 0-255（超出截断），action=set 时必填"),
            ),
            outputs = listOf("brightness_success", "brightness_error", "brightness_value", "brightness_mode"),
            requires = "set/auto_on/auto_off 需「修改系统设置」权限（WRITE_SETTINGS）；get 不需要",
            notes = "自动亮度开着时 set 的值可能被系统改掉，要固定亮度先 auto_off。brightness_mode 为 auto/manual。",
        ),
        NodeSpec(
            F.ACTION_FLASHLIGHT, "手电筒开关",
            params = listOf(
                ParamSpec("action", ParamType.STRING, "on/off/toggle", required = true, enum = listOf("on", "off", "toggle")),
            ),
            outputs = listOf("flashlight_success", "flashlight_on"),
            notes = "系统没有同步查询手电状态的 API：toggle 按本次运行记录的 flashlight_on 取反，没记录时一律打开。",
        ),
        NodeSpec(
            F.ACTION_SET_ALARM, "设闹钟：打开系统时钟的新建闹钟（带时间和标签）",
            params = listOf(
                ParamSpec("hour", ParamType.STRING_OR_NUMBER, "小时 0-23", required = true),
                ParamSpec("minute", ParamType.STRING_OR_NUMBER, "分钟 0-59", required = true),
                ParamSpec("label", ParamType.STRING, "可选：闹钟标签，支持 \${var}"),
            ),
            outputs = listOf("alarm_success", "alarm_error"),
            notes = "通过 Intent 调起系统时钟应用（不是静默创建），后台启动受限时可能没弹出来。",
        ),
        NodeSpec(
            F.ACTION_DND_MODE, "免打扰：查询或切换模式",
            params = listOf(
                ParamSpec(
                    "action", ParamType.STRING,
                    "get/query=只查询；on=完全静音；off=全部允许；priority=仅优先；alarms=仅闹钟",
                    enum = listOf("get", "query", "on", "off", "priority", "alarms"), default = "get",
                ),
            ),
            outputs = listOf("dnd_success", "dnd_error", "dnd_state"),
            requires = "on/off/priority/alarms 需免打扰权限（ACCESS_NOTIFICATION_POLICY，授了通知使用权也算）；get 不需要",
            notes = "dnd_state 取值 off/on/priority/alarms/unknown（off=未开免打扰）。",
        ),
        NodeSpec(
            F.ACTION_SHARE, "分享文字：弹出系统分享面板",
            params = listOf(
                ParamSpec("text", ParamType.STRING, "要分享的文字，支持 \${var}", required = true),
                ParamSpec("title", ParamType.STRING, "分享面板标题，支持 \${var}", default = "分享"),
                ParamSpec("type", ParamType.STRING, "MIME 类型", default = "text/plain"),
                ParamSpec("subject", ParamType.STRING, "可选：主题（如邮件标题），支持 \${var}"),
            ),
            outputs = listOf("share_success", "share_error"),
            notes = "只通过 EXTRA_TEXT 分享文字，不附带文件；改 type 也发不了图片。",
        ),
        NodeSpec(
            F.ACTION_SEND_BROADCAST, "发送广播 Intent",
            params = listOf(
                ParamSpec("action", ParamType.STRING, "广播 action，支持 \${var}", required = true),
                ParamSpec("package", ParamType.STRING, "可选：限定接收的应用包名，支持 \${var}"),
                ParamSpec("component", ParamType.STRING, "可选：指定接收组件 \"包名/类名\"（类名可写 .Xxx），支持 \${var}"),
                ParamSpec(
                    "extras", ParamType.ANY,
                    "可选：附加数据，JSON 对象或 JSON 字符串（如 {\"key\":\"v\",\"num\":5,\"flag\":true}）；字符串值支持 \${var}，解析失败则忽略 extras",
                ),
            ),
            outputs = listOf("broadcast_success", "broadcast_error"),
            notes = "Android 8+ 静态注册的接收器收不到隐式广播，发给第三方应用一般要给 package 或 component。broadcast_success 只表示已发出，不代表有人接收。",
        ),
        NodeSpec(
            F.ACTION_MEDIA_CONTROL, "媒体控制：向当前播放的应用发送播放/暂停/切歌等媒体键",
            params = listOf(
                ParamSpec(
                    "cmd", ParamType.STRING, "媒体键；play_pause=播放/暂停切换，prev 与 previous 相同",
                    enum = listOf("play", "pause", "play_pause", "next", "prev", "previous", "stop"), default = "play",
                ),
            ),
            outputs = listOf("media_success", "media_error"),
            requires = "需 Shizuku 或 root",
        ),
    )
}
