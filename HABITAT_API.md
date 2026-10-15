# Habitat 工作流系统完整 API 文档

## 概述

Habitat 是一个轻量级的 JSON 驱动工作流执行引擎，支持 **40+** 种节点类型，覆盖交互、系统控制、数据处理、网络、感知、媒体等全方位 Android 自动化能力。

---

## 核心数据结构

### WorkflowGraph

```json
{
  "start_node_id": "node_1",
  "nodes": { "node_1": {...}, "node_2": {...} }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `start_node_id` | String | 起始节点 ID |
| `nodes` | Map | 节点映射表 |

### WorkflowNode

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | String | 唯一标识 |
| `type` | String | 节点类型（ACTION_* / CONDITION_*） |
| `params` | Map | 参数 |
| `next` | String\|null | 下一节点 ID |
| `branches` | Map\|null | 条件分支（仅条件节点） |

### 变量引用

在 JSON 参数中可使用 `${var_name}` 引用工作流上下文中的变量，引擎会自动插值替换。

- 只替换一轮，变量值里的 `${...}` 不会再被展开；未定义变量替换为 `"null"`。
- `ACTION_SHELL` 的 `command` 中，每个变量值会被单引号转义为一个独立参数（防注入），不要自己再加引号；`raw_interpolation: true` 可恢复原样拼接。

---

## 节点类型索引

### 交互 (Interaction) — 6 个节点

| 类型 | 说明 |
|------|------|
| [ACTION_CLICK](#action_click) | 点击坐标或无障碍元素 |
| [ACTION_SWIPE](#action_swipe) | 直线滑动 |
| [ACTION_LONG_PRESS](#action_long_press) | 长按 |
| [ACTION_INPUT_TEXT](#action_input_text) | 输入文字 |
| [ACTION_GLOBAL_KEY](#action_global_key) | 全局按键（返回/主页/多任务等） |
| [ACTION_FIND_ELEMENT](#action_find_element) | 查找 UI 元素 |

### 系统控制 (System) — 9 个节点

| 类型 | 说明 |
|------|------|
| [ACTION_SHELL](#action_shell) | 执行 Shell 命令 |
| [ACTION_LAUNCH_APP](#action_launch_app) | 启动应用 |
| [ACTION_FORCE_STOP_APP](#action_force_stop_app) | 强制停止应用 |
| [ACTION_SCREEN_WAKE](#action_screen_wake) | 锁屏唤醒/休眠 |
| [ACTION_WIFI](#action_wifi) | WiFi 控制 |
| [ACTION_BLUETOOTH](#action_bluetooth) | 蓝牙控制 |
| [ACTION_VOLUME](#action_volume) | 音量控制 |
| [ACTION_BRIGHTNESS](#action_brightness) | 亮度控制 |
| [ACTION_FLASHLIGHT](#action_flashlight) | 手电筒 |

### 数据 (Data) — 8 个节点

| 类型 | 说明 |
|------|------|
| [ACTION_SET_VARIABLE](#action_set_variable) | 设置变量 |
| [ACTION_TEXT_OPERATION](#action_text_operation) | 文本处理 |
| [ACTION_MATH](#action_math) | 数学运算 |
| [ACTION_CLIPBOARD](#action_clipboard) | 剪贴板读写 |
| [ACTION_PARSE_JSON](#action_parse_json) | JSON 解析 |
| [ACTION_PARSE_XML](#action_parse_xml) | XML 解析 |
| [ACTION_BASE64](#action_base64) | Base64 编解码 |
| [ACTION_FILE_OPERATION](#action_file_operation) | 文件操作 |

### 逻辑 (Logic) — 6 个节点

| 类型 | 说明 |
|------|------|
| [CONDITION_SWITCH](#condition_switch) | 条件分支 |
| [CONDITION_ADVANCED_SWITCH](#condition_advanced_switch) | 高级条件分支 |
| [ACTION_DELAY](#action_delay) | 延迟等待 |
| [ACTION_LOOP](#action_loop) | 循环 |
| [ACTION_TRY_CATCH](#action_try_catch) | 异常捕获 |
| [ACTION_LOG](#action_log) | 日志输出 |

### 网络 (Network) — 1 个节点

| 类型 | 说明 |
|------|------|
| [ACTION_HTTP_REQUEST](#action_http_request) | HTTP 请求 |
| [ACTION_NETWORK_STATUS](#action_network_status) | 网络状态与 IP |

### 工具 (Utility) — 2 个节点

| 类型 | 说明 |
|------|------|
| [ACTION_GET_TIME](#action_get_time) | 获取当前时间 |
| [ACTION_RANDOM](#action_random) | 生成随机数 |

### 感知 (Sensor) — 5 个节点

| 类型 | 说明 |
|------|------|
| [ACTION_READ_SCREEN](#action_read_screen) | 读屏（无障碍） |
| [ACTION_READ_SMS](#action_read_sms) | 读取短信 |
| [ACTION_GET_APP_INFO](#action_get_app_info) | 获取前台 App 信息 |
| [ACTION_FOREGROUND_APP](#action_foreground_app) | 获取前台 App（增强版） |
| [ACTION_APP_SEARCH](#action_app_search) | 搜索已安装应用 |

### 媒体 (Media) — 2 个节点

| 类型 | 说明 |
|------|------|
| [ACTION_SCREENSHOT](#action_screenshot) | 截屏 |
| [ACTION_TEXT_TO_SPEECH](#action_text_to_speech) | 文字转语音 |

### UI — 5 个节点

| 类型 | 说明 |
|------|------|
| [ACTION_TOAST](#action_toast) | 显示 Toast |
| [ACTION_VIBRATE](#action_vibrate) | 振动 |
| [ACTION_SEND_NOTIFICATION](#action_send_notification) | 发送通知 |
| [ACTION_DYNAMIC_ISLAND](#action_dynamic_island) | 灵动岛悬浮通知 |
| [ACTION_MINI_SCREEN](#action_mini_screen) | 悬浮小屏（文字 / 图片 / 进度，实时反馈） |

### 工作区 — 1 个节点

| 类型 | 说明 |
|------|------|
| [ACTION_WORKSPACE](#action_workspace) | 本机工作区：结果、笔记、图片的读写 |

### 其他 — 2 个节点

| 类型 | 说明 |
|------|------|
| [ACTION_CALL_PHONE](#action_call_phone) | 拨打电话 |
| [ACTION_SHARE](#action_share) | 分享内容 |
| [ACTION_AI_CHAT](#action_ai_chat) | LLM 推理 |

---

## 交互节点详解

### ACTION_CLICK

点击屏幕坐标或无障碍元素。

```json
{
  "type": "ACTION_CLICK",
  "params": {
    "target": "500,1200"
  }
}
```

```json
{
  "type": "ACTION_CLICK",
  "params": {
    "target": "录音",
    "region": "0,0.5,1,1",
    "occurrence": 0,
    "offset_y": -50
  }
}
```

| 参数 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `target` | String | — | 坐标 `"x,y"`；无障碍文本（精确匹配不到时退回子串匹配，含列表行的子文字）；或 `"id:com.android.settings:id/title"` 按 resource-id 匹配（同名行配合 `occurrence`） |
| `region` | String | 无 | 仅文本匹配：限定匹配节点所在屏幕区域，`"top"/"bottom"/"left"/"right"/"center"` 或归一化 `"x1,y1,x2,y2"`（0-1 相对屏幕，如 `0,0.5,1,1` = 下半屏） |
| `occurrence` | Int | 0 | 仅文本匹配：region 过滤后取第几个匹配（按屏幕从上到下排序，0 起） |
| `offset_x` | Int | 0 | 点击坐标相对目标中心的 X 偏移（px） |
| `offset_y` | Int | 0 | 点击坐标相对目标中心的 Y 偏移（px，屏幕 Y 向下为正，负值=向上偏移） |

输出: `click_success` (Boolean)

### ACTION_SWIPE

直线滑动手势。

```json
{
  "type": "ACTION_SWIPE",
  "params": { "x1": 500, "y1": 1500, "x2": 500, "y2": 500, "duration": 400 }
}
```

| 参数 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `x1, y1` | Int | — | 起点坐标 |
| `x2, y2` | Int | — | 终点坐标 |
| `duration` | Long | 400 | 滑动持续时间 (ms) |

输出: `swipe_success` (Boolean)

### ACTION_LONG_PRESS

长按坐标或无障碍元素。

```json
{
  "type": "ACTION_LONG_PRESS",
  "params": { "target": "500,1200", "duration": 800 }
}
```

| 参数 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `target` | String | — | 坐标 "x,y" 或文本/ID 选择器 |
| `duration` | Long | 800 | 长按持续时间 (ms) |

输出: `long_press_success` (Boolean)

### ACTION_INPUT_TEXT

向输入框键入文字。

```json
{
  "type": "ACTION_INPUT_TEXT",
  "params": { "text": "Hello World", "mode": "a11y" }
}
```

| 参数 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `text` | String | — | 文字内容，支持 `${var}`；`submit=true` 时可为空（只提交不改内容） |
| `mode` | String | "a11y" | 输入模式: a11y / shell / ime。a11y 写入后读回核对，没生效时退回剪贴板粘贴（中文可用） |
| `target` | String | — | 可选，`"x,y"`：写入包含该点的输入框（先让它获得焦点）。不传时写入当前焦点输入框 |
| `submit` | Boolean | false | 输入后在输入框按 IME 回车（搜索/发送/确认）。走无障碍 `ACTION_IME_ENTER`，不需要 Shizuku；不可用时退回 `input keyevent 66` |
| `allow_empty` | Boolean | false | 允许把输入框整框设为空（清空） |

输出: `input_success` (Boolean；a11y 模式下框里确实是这段文字才为 true)

### ACTION_GLOBAL_KEY

发送全局按键事件。

```json
{
  "type": "ACTION_GLOBAL_KEY",
  "params": { "key": "home" }
}
```

| 参数 | 类型 | 说明 |
|------|------|------|
| `key` | String | back / home / recents / notifications / quick_settings / power / power_dialog / screenshot / menu |

输出: `key_success` (Boolean)

### ACTION_FIND_ELEMENT

通过无障碍查找 UI 元素。

```json
{
  "type": "ACTION_FIND_ELEMENT",
  "params": { "selector": "设置", "search_mode": "text", "timeout_ms": 3000 }
}
```

| 参数 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `selector` | String | — | 匹配文本 |
| `search_mode` | String | "text" | text / id / class / content_desc |
| `timeout_ms` | Int | 3000 | 超时 (ms) |
| `output_var` | String | — | 可选输出变量 |

输出: `element_found`, `element_bounds`, `element_text`, `element_clickable`

---

## 系统控制节点详解

### ACTION_SHELL

执行 Shell 命令。

```json
{
  "type": "ACTION_SHELL",
  "params": { "command": "echo hello", "mode": "auto" }
}
```

| 参数 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `command` | String | — | Shell 命令 |
| `mode` | String | "auto" | auto / shizuku / root |
| `raw_interpolation` | Boolean | false | true 时变量原样拼进命令（有注入风险） |
| `timeout_ms` | Number | 60000 | 无 Shizuku 时本地执行的超时 |

输出: `shell_output`, `shell_success`

`${var}` 会被自动转义成一个参数：`"command": "am force-stop ${pkg}"` 是对的，`"echo '${x}'"` 会得到带引号的输出。

### ACTION_LAUNCH_APP

启动应用。

```json
{
  "type": "ACTION_LAUNCH_APP",
  "params": { "package_name": "com.android.settings" }
}
```

| 参数 | 类型 | 说明 |
|------|------|------|
| `package_name` | String | 目标包名 |
| `activity` | String | 可选，指定 Activity |

输出: `launch_success` (Boolean)

### ACTION_FORCE_STOP_APP

强制停止应用。

```json
{
  "type": "ACTION_FORCE_STOP_APP",
  "params": { "package_name": "com.example.app" }
}
```

输出: `force_stop_success` (Boolean)

### ACTION_SCREEN_WAKE

屏幕唤醒/休眠控制。

```json
{
  "type": "ACTION_SCREEN_WAKE",
  "params": { "action": "wake_and_unlock", "password": "1234" }
}
```

| 参数 | 类型 | 说明 |
|------|------|------|
| `action` | String | wake / sleep / wake_and_unlock |
| `password` | String | 可选，解锁密码 |

输出: `screen_wake_success` (Boolean)

### ACTION_WIFI

WiFi 开关控制。

```json
{
  "type": "ACTION_WIFI",
  "params": { "action": "on" }
}
```

| 参数 | 类型 | 说明 |
|------|------|------|
| `action` | String | on / off / toggle / status |

输出: `wifi_success`, `wifi_enabled`, `wifi_state`

### ACTION_BLUETOOTH

蓝牙开关控制。

```json
{
  "type": "ACTION_BLUETOOTH",
  "params": { "action": "toggle" }
}
```

| 参数 | 类型 | 说明 |
|------|------|------|
| `action` | String | on / off / toggle / status |

输出: `bluetooth_success`, `bluetooth_enabled`

### ACTION_VOLUME

音量控制。

```json
{
  "type": "ACTION_VOLUME",
  "params": { "action": "set", "stream": "music", "value": 50 }
}
```

| 参数 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `action` | String | — | set / get / up / down / mute / unmute |
| `stream` | String | "music" | music / ring / alarm / notification / system / call |
| `value` | Int | — | 音量百分比 0-100 (set 时) |

输出: `volume_current`, `volume_max`

### ACTION_BRIGHTNESS

屏幕亮度控制。

```json
{
  "type": "ACTION_BRIGHTNESS",
  "params": { "action": "set", "value": 128 }
}
```

| 参数 | 类型 | 说明 |
|------|------|------|
| `action` | String | set / get / auto_on / auto_off |
| `value` | Int | 亮度值 0-255 (set 时) |

输出: `brightness_value`, `brightness_mode`

### ACTION_FLASHLIGHT

手电筒控制。

```json
{
  "type": "ACTION_FLASHLIGHT",
  "params": { "action": "toggle" }
}
```

| 参数 | 类型 | 说明 |
|------|------|------|
| `action` | String | on / off / toggle |

输出: `flashlight_on`

---

## 数据节点详解

### ACTION_SET_VARIABLE

设置工作流变量。

```json
{
  "type": "ACTION_SET_VARIABLE",
  "params": { "name": "count", "value": "42", "type": "int" }
}
```

| 参数 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `name` | String | — | 变量名 |
| `value` | Any | — | 变量值 |
| `type` | String | "string" | string / int / float / bool |

### ACTION_TEXT_OPERATION

文本字符串处理。

```json
{
  "type": "ACTION_TEXT_OPERATION",
  "params": { "action": "replace", "input": "${text}", "old": "foo", "new": "bar", "output_var": "result" }
}
```

| 参数 | 类型 | 说明 |
|------|------|------|
| `action` | String | replace / substring / split / uppercase / lowercase / trim / append / prepend |
| `input` | String | 输入文本，支持 `${var}` |
| `output_var` | String | 输出变量名 |

### ACTION_MATH

数学运算。

```json
{
  "type": "ACTION_MATH",
  "params": { "operation": "add", "a": 10, "b": 5, "output_var": "result" }
}
```

| 参数 | 类型 | 说明 |
|------|------|------|
| `operation` | String | add / subtract / multiply / divide / modulo / power |
| `a`, `b` | Number | 操作数，支持 `${var}` |

### ACTION_CLIPBOARD

系统剪贴板读写。

```json
{
  "type": "ACTION_CLIPBOARD",
  "params": { "action": "get", "output_var": "clip_text" }
}
```

| 参数 | 类型 | 说明 |
|------|------|------|
| `action` | String | get / set |
| `text` | String | 写入文本 (set 时)，支持 `${var}` |
| `output_var` | String | 存储变量名 (get 时) |

输出: `clipboard_success`, `clipboard_content`

### ACTION_PARSE_JSON

解析 JSON 字符串。

```json
{
  "type": "ACTION_PARSE_JSON",
  "params": { "json": "${api_response}", "path": "data.items[0].name", "output_var": "name" }
}
```

| 参数 | 类型 | 说明 |
|------|------|------|
| `json` | String | JSON 字符串，支持 `${var}` |
| `path` | String | 点分隔路径，支持 `items[0]` 数组索引 |
| `output_var` | String | 输出变量名 |

输出: `parse_success`, 路径对应的值

### ACTION_PARSE_XML

解析 XML 字符串。

```json
{
  "type": "ACTION_PARSE_XML",
  "params": { "xml": "${xml_data}", "xpath": "root.item.name", "output_var": "value" }
}
```

输出: `xml_parsed_value`, `xml_success`

### ACTION_BASE64

Base64 编解码。

```json
{
  "type": "ACTION_BASE64",
  "params": { "action": "encode", "data": "Hello World", "output_var": "encoded" }
}
```

| 参数 | 类型 | 说明 |
|------|------|------|
| `action` | String | encode / decode |
| `data` | String | 输入数据 |

输出: `base64_result`, `base64_success`

### ACTION_FILE_OPERATION

文件系统操作。

```json
{
  "type": "ACTION_FILE_OPERATION",
  "params": { "action": "write", "path": "/sdcard/test.txt", "content": "Hello Habitat" }
}
```

| 参数 | 类型 | 说明 |
|------|------|------|
| `action` | String | read / write / delete / exists / list |
| `path` | String | 文件路径，支持 `${var}` |
| `content` | String | 写入内容 (write 时) |
| `append` | Boolean | 追加模式 (write 时)，默认 false |

输出: `file_success`, `file_result`, `file_exists` (exists), `file_count` (list)

---

## 逻辑节点详解

### CONDITION_SWITCH

根据表达式分支执行。

```json
{
  "type": "CONDITION_SWITCH",
  "params": { "expression": "is_daytime == true" },
  "branches": { "true": "node_yes", "false": "node_no" }
}
```

支持表达式: `sms_found == true`, `screen_data == true`, `is_daytime == true`

### CONDITION_ADVANCED_SWITCH

根据变量值分支执行。

```json
{
  "type": "CONDITION_ADVANCED_SWITCH",
  "params": { "var": "current_app", "operator": "contains", "value": "wechat" },
  "branches": { "true": "node_wechat", "false": "node_other" }
}
```

| 参数 | 类型 | 说明 |
|------|------|------|
| `var` | String | 变量名 |
| `operator` | String | == / != / > / < / >= / <= / contains / starts_with / ends_with / is_empty |
| `value` | String | 比较值 |

### ACTION_DELAY

等待指定毫秒数。

```json
{
  "type": "ACTION_DELAY",
  "params": { "millis": 2000 }
}
```

### ACTION_LOOP

循环控制。

```json
{
  "type": "ACTION_LOOP",
  "params": { "count": 5, "mode": "count" },
  "branches": { "loop": "loop_body", "end": "after_loop" }
}
```

| 参数 | 类型 | 说明 |
|------|------|------|
| `count` | Int | 循环次数 (mode=count) |
| `mode` | String | count / expression |
| `expression` | String | 循环条件 (mode=expression) |

### ACTION_TRY_CATCH

异常捕获。

```json
{
  "type": "ACTION_TRY_CATCH",
  "branches": { "try": "work_node", "catch": "error_node" }
}
```

### ACTION_LOG

输出日志。

```json
{
  "type": "ACTION_LOG",
  "params": { "message": "当前值: ${count}", "level": "info" }
}
```

| 参数 | 类型 | 说明 |
|------|------|------|
| `message` | String | 日志内容 |
| `level` | String | debug / info / warn / error |

---

## 媒体 & 感知节点

### ACTION_SCREENSHOT

截取当前屏幕。

```json
{
  "type": "ACTION_SCREENSHOT",
  "params": { "format": "base64", "output_var": "screenshot" }
}
```

| 参数 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `format` | String | "base64" | base64 / file |
| `output_var` | String | — | 存储变量名 |

输出: `screenshot_widt`, `screenshot_height`, `screenshot_success`

### ACTION_TEXT_TO_SPEECH

文字转语音朗读。

```json
{
  "type": "ACTION_TEXT_TO_SPEECH",
  "params": { "text": "你好世界", "language": "zh" }
}
```

| 参数 | 类型 | 说明 |
|------|------|------|
| `text` | String | 朗读文字 |
| `language` | String | zh / en / ja / ko 等 |

### ACTION_READ_SCREEN

通过无障碍读取屏幕文字。

```json
{
  "type": "ACTION_READ_SCREEN",
  "params": { "keyword": "设置", "output_var": "screen_text" }
}
```

| 参数 | 类型 | 说明 |
|------|------|------|
| `keyword` | String | 查找关键字 |
| `output_var` | String | 全屏文本输出变量 |

输出: `screen_data` (Boolean — 是否找到关键字)

### ACTION_READ_SMS

读取短信收件箱。

```json
{
  "type": "ACTION_READ_SMS",
  "params": { "filter_by": "both", "sender": "银行", "extract_code": true }
}
```

| 参数 | 类型 | 说明 |
|------|------|------|
| `filter_by` | String | latest / sender / content / both |
| `sender` | String | 发件人过滤 |
| `content` | String | 内容过滤 |
| `extract_code` | Boolean | 提取验证码 |

输出: `sms_found`, `sms_sender`, `sms_content`, `verification_code`

### ACTION_GET_APP_INFO

获取前台应用信息。

```json
{
  "type": "ACTION_GET_APP_INFO",
  "params": { "output_var": "current_app", "activity_var": "current_activity" }
}
```

输出: `current_app`, `current_activity`

### ACTION_FOREGROUND_APP

获取前台应用（增强版，含 Shell 兜底）。

```json
{
  "type": "ACTION_FOREGROUND_APP",
  "params": { "output_var": "fg_package" }
}
```

输出: `foreground_package`, `foreground_activity`, `foreground_class`

### ACTION_APP_SEARCH

搜索已安装应用。

```json
{
  "type": "ACTION_APP_SEARCH",
  "params": { "query": "微信", "include_system": false, "output_var": "app_list" }
}
```

| 参数 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `query` | String | — | 搜索词（匹配包名或应用名） |
| `include_system` | Boolean | false | 是否包含系统应用 |
| `output_var` | String | — | 输出变量名 |

输出: `app_count`, `app_list` (JSON 数组)

---

## 网络 & 通信节点

### ACTION_HTTP_REQUEST

发送 HTTP 请求。

```json
{
  "type": "ACTION_HTTP_REQUEST",
  "params": {
    "url": "https://api.example.com/data",
    "method": "POST",
    "headers": "{\"Authorization\": \"Bearer token123\"}",
    "body": "{\"key\": \"value\"}",
    "output_var": "response"
  }
}
```

| 参数 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `url` | String | — | 请求 URL |
| `method` | String | "GET" | GET / POST / PUT / DELETE |
| `headers` | String | — | JSON 或 "key:value;key:value" 格式 |
| `body` | String | — | 请求体 |
| `timeout` | Int | 30000 | 超时 (ms) |
| `output_var` | String | — | 响应体存储变量 |

输出: `http_status_code`, `http_response`, `http_success`

### ACTION_CALL_PHONE

拨打电话（打开拨号盘）。

```json
{
  "type": "ACTION_CALL_PHONE",
  "params": { "phone_number": "10086" }
}
```

### ACTION_SHARE

调用系统分享。

```json
{
  "type": "ACTION_SHARE",
  "params": { "text": "分享内容", "title": "分享到", "type": "text/plain" }
}
```

---

## UI 节点

### ACTION_TOAST

```json
{ "type": "ACTION_TOAST", "params": { "message": "Hello ${name}" } }
```

### ACTION_VIBRATE

```json
{ "type": "ACTION_VIBRATE", "params": { "duration": 300, "amplitude": 128 } }
```

### ACTION_SEND_NOTIFICATION

```json
{
  "type": "ACTION_SEND_NOTIFICATION",
  "params": { "title": "提醒", "message": "任务完成", "channel_id": "habitat" }
}
```

### ACTION_DYNAMIC_ISLAND

iOS 风格灵动岛悬浮通知。

```json
{
  "type": "ACTION_DYNAMIC_ISLAND",
  "params": { "title": "执行中...", "message": "步骤 2/5", "duration": 3000 }
}
```

### ACTION_MINI_SCREEN

悬浮小屏：任务进行中给用户看实时内容（AI 回复、进度、截图）。可拖动、折叠、关闭。由宿主 App 注册（`:app`），需要无障碍服务或悬浮窗权限。

| 参数 | 说明 |
|------|------|
| `action` | `show`（替换内容，默认）/ `append`（追加一段）/ `progress`（只更新进度）/ `hide`（隐藏，保留内容）/ `clear`（隐藏并清空） |
| `title`、`text` | 标题、正文（支持 `${var}`，正文最多 4000 字，最多保留 20 段） |
| `image` | 图片文件路径（经路径守卫）或 base64（可带 `data:image/...;base64,` 前缀），宽度缩到 1080px 以内 |
| `progress` | 0–100 |
| `position` | `top` / `center` / `bottom`；用户拖动过就按用户的位置 |
| `duration_ms` | 大于 0 时到时自动隐藏 |

输出：`mini_screen_success`、`mini_screen_shown`（false = 用户在本次运行里关掉了小屏，只更新了内容不再弹出）、`mini_screen_error`。

- 来源标签（工作流名 / Agent / MCP）由调用方决定，不能用参数指定；MCP 写入的内容固定标「MCP」并用警示色。
- 同一时刻只显示一个来源的内容：另一个来源 `append` 等同替换。
- Agent 派发手势或截图时，小屏会自动让开 / 暂时隐藏，不会挡住点击，也不会被截进去。

```json
{
  "type": "ACTION_MINI_SCREEN",
  "params": { "title": "剪贴板摘要", "text": "${summary}", "position": "top" }
}
```

### ACTION_WORKSPACE

本机工作区（`filesDir/workspace`）：把结果、笔记、图片存下来，用户在 App 的「工作区」页查看、分享、置顶。只存本机。

| 参数 | 说明 |
|------|------|
| `action` | `write`（新建，同名同类默认覆盖）/ `append`（文本追加）/ `read` / `list` / `delete` |
| `name` | 显示名（落盘一律用 id，不会穿越目录）；`read`/`delete` 也可以给 `id` |
| `content` | 文字或 JSON（支持 `${var}`）；`kind=image` 时为 base64 |
| `kind` | `text`（默认）/ `json`（会校验）/ `image`（PNG/JPEG/WEBP/GIF，看文件头） |
| `image_path` | `kind=image` 时也可以给图片路径（经路径守卫） |
| `tags` | 逗号分隔 |
| `overwrite` | `write` 时同名是否覆盖，默认 true；false 则自动改名「名字 (2)」 |
| `tag`、`limit` | `list` 的筛选（`kind` 也可用，另有 `record` 表示运行记录） |
| `output_var` | `read` 的内容 / `list` 的 JSON 数组写到这里（默认 `workspace_content` / `workspace_list`） |

输出：`workspace_success`、`workspace_id`、`workspace_error`；`read` 另有 `workspace_kind`，图片给 `workspace_path`（可直接交给 `ACTION_MINI_SCREEN` 的 `image`）；`list` 另有 `workspace_count`。

- 上限：文本/JSON 每条 1MB，图片每条 8MB，总共 200MB，超出从最早的未置顶条目删起。
- 运行记录（`record`）只能由 App 写入（Agent 任务、打开了「运行结果存到工作区」的工作流），节点不能写，记录里的来源可信。
- 远程（MCP）调用：`write`/`append`/`read`/`list` 默认放行；`delete` 需要开启「允许远程高危工具」。

```json
{
  "type": "ACTION_WORKSPACE",
  "params": { "action": "write", "name": "今日天气", "content": "${weather}", "tags": "天气" }
}
```

### ACTION_AI_CHAT

LLM 推理。

```json
{
  "type": "ACTION_AI_CHAT",
  "params": { "prompt": "总结以下内容：${text}", "output_var": "summary" }
}
```

### ACTION_NETWORK_STATUS

查询网络连接状态和设备 IP。

```json
{
  "type": "ACTION_NETWORK_STATUS",
  "params": { "action": "all" }
}
```

| 参数 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `action` | String | "status" | status / ip / all |

输出: `network_connected`, `network_type` (wifi/cellular/ethernet/none), `wifi_ip`, `mobile_ip`, `network_success`

### ACTION_GET_TIME

获取当前时间。

```json
{
  "type": "ACTION_GET_TIME",
  "params": { "format": "yyyy-MM-dd HH:mm:ss", "output_var": "now" }
}
```

| 参数 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `format` | String | "yyyy-MM-dd HH:mm:ss" | SimpleDateFormat 格式 |
| `output_var` | String | "current_time" | 输出变量名 |
| `timestamp` | Boolean | false | true 返回 Unix 毫秒时间戳 |

输出: `current_time`, `current_timestamp`, `current_year/month/day/hour/minute/second`, `time_success`

### ACTION_RANDOM

生成随机数。

```json
{
  "type": "ACTION_RANDOM",
  "params": { "min": 1, "max": 100, "type": "int", "output_var": "lucky" }
}
```

| 参数 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `min` | Int | 0 | 最小值（含） |
| `max` | Int | 100 | 最大值（含） |
| `type` | String | "int" | int / float / boolean |
| `output_var` | String | "random_value" | 输出变量名 |

---

## 架构设计

### 依赖解耦

Habitat 通过 `api/` 和 `bridge/` 两层实现了解耦：

```
habitat/
  api/IAccessibilityProvider.kt   ← 无障碍服务接口
  api/IShellExecutor.kt           ← Shell 命令接口
  bridge/AppAccessibilityProvider.kt ← 自有无障碍桥接
  bridge/ShizukuShellExecutor.kt     ← 自有 Shell 桥接
  handlers/                       ← 40+ 节点处理器
```

独立部署时，只需实现 `IAccessibilityProvider` 和 `IShellExecutor` 接口即可。

### 节点处理器模式

所有处理器实现 `INodeHandler` 接口：

```kotlin
interface INodeHandler {
    suspend fun handle(node: WorkflowNode, context: WorkflowContext): String?
}
```

- 返回 `node.next` 表示正常流转
- 返回特定节点 ID 用于跳转/分支
- 通过构造函数注入 `IAccessibilityProvider` 和 `IShellExecutor`
- 使用 `context.interpolate()` 进行变量替换
- 结果存储在 `context.variables` 中
