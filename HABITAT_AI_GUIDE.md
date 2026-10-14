# Habitat 工作流 AI 编写指南

> 面向 AI / LLM 的工作流 JSON 生成规范。依据此文可独立编写可正确执行的 Habitat 工作流。

---

## 1. JSON 结构定义

### 1.1 顶层 Schema

```json
{
  "start_node_id": "<节点ID>",
  "nodes": {
    "<节点ID>": { "id": "...", "type": "...", "params": {...}, "next": "...", "branches": {...} }
  }
}
```

| 字段 | 必需 | 类型 | 说明 |
|------|------|------|------|
| `start_node_id` | 是 | String | 入口节点 ID，必须存在于 `nodes` 中 |
| `nodes` | 是 | Map<String, Object> | 所有节点的映射表，key 为节点 ID |

### 1.2 节点 Schema

| 字段 | 必需 | 类型 | 说明 |
|------|------|------|------|
| `id` | 是 | String | 节点唯一标识符 |
| `type` | 是 | String | 节点类型，必须是 `NodeHandlerFactory` 中注册的常量 |
| `params` | 否 | Map<String, Any> | 节点参数，数字会自动转为最窄类型（Int→Long→Double） |
| `next` | 否 | String / null | 下一个节点 ID。为 null 或缺失时表示流程结束 |
| `branches` | 否 | Map<String, String?> | 分支映射。仅条件节点使用。`"true"` 和 `"false"` 为常用 key |

### 1.3 执行流规则

- 执行从 `start_node_id` 开始
- 每个节点执行后返回 `next`（或 `branches` 中的分支）指定的下一个节点 ID
- 返回 `null` 或缺失 `next` → 流程结束
- 最大执行 1000 步，超出强制终止
- 节点列表是扁平的——不存在嵌套，流程通过 `next`/`branches` 连接

---

## 2. 节点类型参考

### 2.1 逻辑控制

| 类型 | 功能 | key 参数 | 输出变量 |
|------|------|----------|---------|
| `CONDITION_SWITCH` | 表达式条件分支 | `expression`: 表达式字符串，如 `"var == value"` 或 `"wifi_enabled == true"` | branches 中对应 key 的 next |
| `CONDITION_ADVANCED_SWITCH` | 分离式条件分支 | `var`: 变量名, `operator`: `==`/`!=`/`>`/`<`/`>=`/`<=`/`contains`/`startswith`/`endswith`, `value`: 比较值；也支持 `expression` 合并格式 | 同上 |
| `ACTION_DELAY` | 延迟等待 | `millis` 或 `ms`: 毫秒数 (max 60000) | 无 |
| `ACTION_LOOP` | 循环控制 | `mode`: `"count"`/`"while"`, `times`/`condition_expr`, `body_node`/`counter_var`/`max_iterations` | 无 |
| `ACTION_TRY_CATCH` | 异常分支 | `catch_var`: 异常信息存储变量 | branches: `"success"`/`"error"` |
| `ACTION_LOG` | 日志输出 | `message`: 日志内容 | 无 |

### 2.2 变量与数据

| 类型 | 功能 | key 参数 | 输出变量 |
|------|------|----------|---------|
| `ACTION_SET_VARIABLE` | 设置变量 | `name`/`key`: 变量名, `value`: 值, `type`: `"string"`/`"int"`/`"bool"`/`"float"`/`"auto"` | 写入 `context.variables[name]` |
| `ACTION_TEXT_OPERATION` | 文本操作 | `action`/`operation`: `replace`/`substring`/`split`/`uppercase`/`lowercase`/`trim`/`append`/`prepend`, `input`/`source_var`, `output_var`, 相关参数 | `output_var` 指定变量 |
| `ACTION_MATH` | 数学运算 | `operation`: `add`/`subtract`/`multiply`/`divide`/`modulo`/`power`, `a`: 数值或变量名, `b`: 数值或变量名, `output_var` | `output_var`（默认 `math_result`） |
| `ACTION_CLIPBOARD` | 剪贴板读写 | `action`: `"get"`/`"set"`, `text`(set时), `output_var`(get时) | `clipboard_content`, `clipboard_success` |
| `ACTION_PARSE_JSON` | JSON 解析 | `json`: JSON 字符串, `path`: 点分隔路径, `output_var` | `output_var` 指定变量 |
| `ACTION_PARSE_XML` | XML 解析 | 同上 | `output_var` 指定变量 |
| `ACTION_BASE64` | Base64 编解码 | `action`: `"encode"`/`"decode"`, `input`, `output_var` | `output_var` 指定变量 |
| `ACTION_FILE_OPERATION` | 文件读写 | `action`: `"read"`/`"write"`/`"delete"`/`"exists"`, `path`, `content`(write时), `output_var` | `output_var` 指定变量 |

### 2.3 交互（需无障碍服务）

| 类型 | 功能 | key 参数 | 输出变量 |
|------|------|----------|---------|
| `ACTION_CLICK` | 点击 | `target`: 坐标 `"x,y"` 或 UI 文本/描述 | 无 |
| `ACTION_SWIPE` | 滑动 | `x1,y1,x2,y2`: 坐标, `duration`(ms, 默认400) | `swipe_success` |
| `ACTION_LONG_PRESS` | 长按 | `target`: 坐标或文本, `duration`(ms) | 无 |
| `ACTION_INPUT_TEXT` | 输入文字 | `text`: 内容, `mode`: `"accessibility"`/`"shell"`/`"clipboard"` | 无 |
| `ACTION_GLOBAL_KEY` | 全局按键 | `key`: `back`/`home`/`recents`/`notifications`/`enter`/`delete`/`space`/`tab`/`quick_settings`/`power_dialog`/`screenshot_toggle` | `key_success` |
| `ACTION_FIND_ELEMENT` | 查找 UI 元素 | `text`/`id`/`desc`: 匹配条件, `timeout_ms` | `element_found`, `element_x`, `element_y` 等 |

### 2.4 系统控制（需 Shizuku 或 Root）

| 类型 | 功能 | key 参数 | 输出变量 |
|------|------|----------|---------|
| `ACTION_SHELL` | 执行 Shell | `command`: 命令（`${var}` 自动转义，勿加引号）, `mode`: `"auto"`/`"shizuku"`/`"root"` | `shell_output`, `shell_success` |
| `ACTION_LAUNCH_APP` | 启动应用 | `package_name`: 包名 | 无 |
| `ACTION_FORCE_STOP_APP` | 强制停止 | `package_name`: 包名 | 无 |
| `ACTION_SCREEN_WAKE` | 唤醒/锁屏 | `action`: `"wake"`/`"sleep"` | 无 |
| `ACTION_WIFI` | WiFi 控制 | `action`: `"on"`/`"off"`/`"toggle"`/`"status"` | `wifi_success`, `wifi_enabled`, `wifi_state` |
| `ACTION_BLUETOOTH` | 蓝牙控制 | `action`: `"on"`/`"off"`/`"toggle"`/`"status"` | `bluetooth_success`, `bluetooth_enabled` |
| `ACTION_VOLUME` | 音量控制 | `action`: `"set"`/`"up"`/`"down"`/`"mute"`, `stream`: `"music"`/`"ring"`/`"alarm"`, `value` | `volume_success` |
| `ACTION_BRIGHTNESS` | 亮度控制 | `action`: `"set"`/`"auto"`, `value`: 0-255 | `brightness_success` |
| `ACTION_FLASHLIGHT` | 手电筒 | `action`: `"on"`/`"off"`/`"toggle"` | `flashlight_success` |
| `ACTION_CALL_PHONE` | 拨打电话 | `number`: 号码 | `call_success` |
| `ACTION_SHARE` | 系统分享 | `text`: 内容, `title`, `type`, `subject` | `share_success` |

### 2.5 媒体

| 类型 | 功能 | key 参数 | 输出变量 |
|------|------|----------|---------|
| `ACTION_SCREENSHOT` | 截屏 | `format`: `"base64"`/`"file"`, `output_var`, `quality`(1-100) | `screenshot_success`, `screenshot_width/height`, 输出变量 |
| `ACTION_TEXT_TO_SPEECH` | 文字转语音 | `text`: 内容, `language`, `pitch`, `speed` | `tts_success` |

### 2.6 网络

| 类型 | 功能 | key 参数 | 输出变量 |
|------|------|----------|---------|
| `ACTION_HTTP_REQUEST` | HTTP 请求 | `url`, `method`: `"GET"`/`"POST"`, `headers`, `body`, `output_var`, `timeout`(ms) | `http_success`, `http_status_code`, `http_error`, 输出变量 |
| `ACTION_NETWORK_STATUS` | 网络状态 | `action`: `"status"`/`"ip"`/`"all"` | `network_connected`, `network_type`, `wifi_ip`, `network_success` |

### 2.7 工具

| 类型 | 功能 | key 参数 | 输出变量 |
|------|------|----------|---------|
| `ACTION_GET_TIME` | 获取时间 | `format`(默认 `yyyy-MM-dd HH:mm:ss`), `output_var`(默认 `current_time`), `timestamp`(boolean) | `current_time`, `current_year/month/day/hour/minute/second` |
| `ACTION_RANDOM` | 随机数 | `min`, `max`, `type`: `"int"`/`"float"`/`"boolean"`, `output_var` | `random_value`, `random_success` |

### 2.8 感知（需无障碍服务）

| 类型 | 功能 | key 参数 | 输出变量 |
|------|------|----------|---------|
| `ACTION_READ_SCREEN` | 读屏 | `keyword`: 搜索关键字 | `screen_data`(boolean), `screen_text` |
| `ACTION_READ_SMS` | 读短信 | `filter_by`: `"latest"`/`"sender"`/`"content"`/`"both"`, `extract_code`(boolean), `max_scan`(默认20) | `sms_found`, `sms_sender`, `sms_content`, `verification_code` |
| `ACTION_GET_APP_INFO` | 获取应用信息 | `package_name` | `app_name`, `app_version`, `app_installed` |
| `ACTION_FOREGROUND_APP` | 前台应用 | `output_var` | 输出变量 |
| `ACTION_APP_SEARCH` | 搜索应用 | `query` | `search_results` |

### 2.9 UI 反馈

| 类型 | 功能 | key 参数 | 输出变量 |
|------|------|----------|---------|
| `ACTION_TOAST` | Toast 消息 | `message`: 内容 | 无 |
| `ACTION_VIBRATE` | 振动 | `duration`(ms), `amplitude`(0-255) | 无 |

### 2.10 AI

| 类型 | 功能 | key 参数 | 输出变量 |
|------|------|----------|---------|
| `ACTION_AI_CHAT` | LLM 推理 | `prompt`: 提示词, `output_var`(默认 `llm_result`) | 输出变量 |

---

## 3. 变量系统

### 3.1 变量引用语法

- `${varName}` — 引用上下文变量，引擎自动插值替换
- 变量作用域：整个工作流执行期间（`WorkflowContext.variables`，ConcurrentHashMap 线程安全）
- 插值只替换一轮：变量值里如果本身含有 `${...}`，会原样保留，不再展开（防止短信/通知等外部文本二次展开读出其他变量）
- 变量名允许字母、数字、下划线和点（`${a.b}` 查找名为 `a.b` 的变量，不做路径解析）
- `ACTION_SHELL` 的 `command` 里每个 `${var}` 替换时会自动用单引号转义成一个完整的 shell 参数，**不要再自己给 `${var}` 加引号**（`echo '${x}'` 会变成双重引号）。确需旧行为时设 `raw_interpolation: true`

### 3.2 变量读写

- `ACTION_SET_VARIABLE` 写入变量
- `ACTION_MATH`、`ACTION_CLIPBOARD`、`ACTION_HTTP_REQUEST` 等通过 `output_var` 写入
- `context.interpolate(text)` 在执行时自动替换所有 `${...}` 占位符
- 未定义的变量解析为字符串 `"null"`

### 3.3 内置输出变量

所有节点自动输出以下变量（无需显式定义）：

- `ACTION_*` 类节点通常输出 `<type>_success` (Boolean) 表示操作是否成功
- 失败时可能输出 `<type>_error` (String) 包含错误信息
- 具体输出变量见上表

---

## 4. 权限依赖

| 功能类别 | 所需权限 | 获取方式 |
|----------|---------|---------|
| 点击/滑动/读屏/输入/查找元素 | 无障碍服务 | 系统设置 → 无障碍 → 开启 Habitat |
| Shell/WiFi/蓝牙/截屏/按键/音量/亮度 | Shizuku | 安装 Shizuku App 并授权 |
| 截屏（Shell 方式） | Shizuku（uid=2000 足够） | `screencap` 命令 |
| 短信读取 ContentProvider | `READ_SMS` 运行时权限 | 系统设置 → 权限管理 |
| 短信读取 Shizuku 降级 | Shizuku | `content query --uri content://sms/inbox` |
| 手电筒 | `CAMERA` 权限 | 部分设备需要 |
| 悬浮窗 | `SYSTEM_ALERT_WINDOW` | 系统设置 → 悬浮窗权限 |
| 文件读写 | `MANAGE_EXTERNAL_STORAGE`（Android 11+） | 系统设置 |
| 通知监听 | 通知监听服务 | 系统设置 → 通知使用权 |
| LLM 推理 | LiteRT-LM 模型文件 | 模型需下载到本地 |

### 权限自动降级

- **短信读取**：`READ_SMS` 失败 → 自动降级为 Shizuku shell 方式
- **WiFi 控制**：`WifiManager.setWifiEnabled()`（可能静默失败）→ 降级为 `svc wifi enable/disable` Shell 命令
- **Shell 命令**：优先 Shizuku → 降级为 Runtime.exec()

---

## 5. 校验规则

提交 JSON 前确认以下事项，确保可通过 `HabitatJson.fromJson()` + `WorkflowGraph.validate()` 校验：

### 5.1 结构校验
- [ ] JSON 本身是合法的 JSON（无多余逗号、引号配对）
- [ ] 顶层包含 `start_node_id` 和 `nodes` 字段
- [ ] `nodes` 为非空对象（不能是 `{}`）
- [ ] `start_node_id` 的值在 `nodes` 中存在

### 5.2 节点校验
- [ ] 每个 node 包含 `id`（非空字符串）
- [ ] 每个 node 包含 `type`（非空字符串）
- [ ] `type` 值在 `NodeHandlerFactory` 注册表中存在（见上表）
- [ ] 所有 `next` 引用的节点 ID 都在 `nodes` 中存在
- [ ] 所有 `branches` 中引用的节点 ID 都在 `nodes` 中存在

### 5.3 逻辑校验
- [ ] 没有孤立节点（所有被引用的节点都可以从 start_node_id 到达）
- [ ] 条件节点必须包含 `branches` 字段，否则始终走 `next`
- [ ] 循环工作流有明确的终止条件
- [ ] `ACTION_READ_SMS` 这类依赖权限的节点，在无权限设备上需有 Shizuku 降级路径

---

## 6. 常见错误与规避

| 错误现象 | 原因 | 规避方法 |
|----------|------|---------|
| `JSON 语法错误: ...` | JSON 格式不合法 | 用 JSON 校验工具检查 |
| `start_node_id 'xxx' 在 nodes 中不存在` | 入口 ID 拼写错误或遗漏 | 确保 start_node_id 与某个 node 的 id 一致 |
| `节点 'xxx' 缺少 'id' 字段` | 某个 node 对象缺少 id | 每个 node 必须含 `"id"` |
| `⚠ No handler for type 'xxx'` | type 值拼写错误或不存在 | 对照上表确认 type 名称 |
| 变量不展开/显示 `${xxx}` | `ACTION_SET_VARIABLE` 的 `name` 不是 `key` | 使用 `"name"` 或 `"key"` 作为变量名参数 |
| `1.0 + 1.0 = 2.0` 固定不变 | Math 的 `a`/`b` 是数字而非变量名 | 引用变量用字符串 `"a": "i"`，字面量用数字 `"b": 1` |
| 循环永不终止 | 条件表达式引用的变量没被更新 | 确保循环体内有 `ACTION_SET_VARIABLE` 或 `ACTION_MATH` 修改变量 |
| 验证码读取失败 (SecurityException) | 缺少 `READ_SMS` 权限 | 安装后手动授权，或确保 Shizuku 已连接（自动降级） |
| 截屏失败 | Shizuku 未连接或输出路径无权限 | 截屏写入 DCIM 公共目录，确保 Shizuku 在线 |
| Flow 突然结束 | 某节点 `next` 指向不存在的节点 | 检查所有 next 引用 |
| `执行错误: ...` / Toast 显示错误 | 节点执行时抛出未捕获异常 | 检查参数格式、权限状态 |

---

## 7. 最小可运行示例

```json
{"start_node_id":"hello","nodes":{"hello":{"id":"hello","type":"ACTION_TOAST","params":{"message":"Hello Habitat"}}}}
```

## 8. 完整示例（验证码提取 + 剪贴板）

```json
{"start_node_id":"read","nodes":{"read":{"id":"read","type":"ACTION_READ_SMS","params":{"filter_by":"latest","extract_code":true,"max_scan":20},"next":"check"},"check":{"id":"check","type":"CONDITION_SWITCH","params":{"expression":"sms_found == true"},"branches":{"true":"copy","false":"fail"}},"copy":{"id":"copy","type":"ACTION_CLIPBOARD","params":{"action":"set","text":"${verification_code}"},"next":"ok"},"ok":{"id":"ok","type":"ACTION_TOAST","params":{"message":"验证码已复制"}},"fail":{"id":"fail","type":"ACTION_TOAST","params":{"message":"未找到验证码"}}}}
```

---

**文档版本**: 1.0  
**对应代码**: `NodeHandlerFactory.kt` (com.ailun.habitat), `WorkflowGraph.kt`, `HabitatExecutor.kt`
