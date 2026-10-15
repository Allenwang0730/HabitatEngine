# Habitat — 让 AI Agent 操控安卓手机

> **可嵌入的安卓自动化运行时**，自带**手机端 MCP 服务**和**自主 Agent**（DeepSeek 规划、TypeSafe Jev 逐屏选动作、代码验证）。
> 工作流是 LLM 能直接生成的扁平 JSON，执行走无障碍 + 系统 API 双轨。

[![CI](https://github.com/Allenwang0730/HabitatEngine/actions/workflows/ci.yml/badge.svg)](https://github.com/Allenwang0730/HabitatEngine/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/Allenwang0730/HabitatEngine)](https://github.com/Allenwang0730/HabitatEngine/releases)
[![JitPack](https://jitpack.io/v/Allenwang0730/HabitatEngine.svg)](https://jitpack.io/#Allenwang0730/HabitatEngine)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3.20-purple)]()
[![Min SDK](https://img.shields.io/badge/API-29+-green)]()
[![MCP](https://img.shields.io/badge/MCP-server-green)]()
[![License](https://img.shields.io/badge/License-MIT-blue)](LICENSE)

[English](README.md)

**能做什么**

- **一句话让手机自己做**——"打开设置进入蓝牙""亮度调到最高""现在电量多少"。Agent 先规划，能用设备工具就直接用，不能就在界面上点，最后按屏幕证据复核。真机评测：有规划时 Jev、DeepSeek 两种执行器都是 **28/28**（2026-10-09，无规划 13–18/28）——[方法与原始数据](docs/EVAL.md)。
- **手机上的 MCP 服务**——任意 MCP 客户端（Claude、Cursor、自己的 Agent）经 `ws://127.0.0.1:8931/mcp` 读屏、调用约 80 个自动化节点；令牌鉴权，高危节点默认关闭。
- **可嵌入的引擎**——把 `:lib` 引入你自己的 App（JitPack），运行 LLM 生成的 JSON 工作流：触发器、分支、循环、变量、Shell、HTTP、传感器、通知。

## 两分钟上手

1. 从 [Releases](https://github.com/Allenwang0730/HabitatEngine/releases) 下载安装包装到 Android 10+ 手机：绝大多数手机用 `app-standard-arm64-v8a-release.apk`，不确定就用 `universal`。
2. 打开 Habitat，按提示开启**无障碍服务**。
3. 底栏 **Agent** → 选执行器（Jev / DeepSeek / 本地）→ 填 API Key（只加密存在手机上）→ 输入目标 → **开始**。
   - 没有 key：选「本地」，离线可用（策略简单）。
   - 国内网络直连 Jev 会返回 451，见下文 [Jev 网络](#自主模式规划deepseek-执行jev-代码控制)。

想用工作流：底栏 **AI 生成** → 用一句话描述 → DeepSeek 生成 JSON → 保存运行。

### 在你的 App 里使用引擎

```kotlin
// settings.gradle.kts
dependencyResolutionManagement { repositories { maven("https://jitpack.io") } }
// build.gradle.kts
implementation("com.github.Allenwang0730:HabitatEngine:v1.9.0")
```

初始化与权限见 [docs/ENGINE_USAGE.md](docs/ENGINE_USAGE.md)。

---

## 设计理念

Habitat 工作流是**扁平 JSON 图**。每个节点只有两种控制流机制——`next`（线性）和 `branches`（条件分支）——再无其他。

这是刻意为之：

- **LLM 擅长生成 JSON**——结构越扁平，出错越少。
- **单一变量语法**（`${varName}`）——没有 `{{}}`、`[[]]` 等容易混淆的分隔符。
- **校验简单**——检查 `start_node_id` 是否存在、`next` 引用是否有效。错误描述足够清晰，可反馈给 LLM 自动修正。

---

## 快速开始

### 最简工作流

```json
{
  "start_node_id": "hello",
  "nodes": {
    "hello": {
      "id": "hello",
      "type": "ACTION_TOAST",
      "params": { "message": "Hello, Habitat!" }
    }
  }
}
```

### 代码中执行

```kotlin
val factory = NodeHandlerFactory(
    a11y = AppAccessibilityProvider,
    shell = ShizukuShellExecutor(context),
)
val graph = HabitatJson.fromJson(jsonString)
HabitatExecutionService.start(workflowId, jsonString, context, factory) { log ->
    println(log)
}
```

---

## 节点类型速览 (86)

引擎共有 86 种节点，分多类。常用/核心节点如下（与 `README.md` 英文版为同一清单，`NodeKnowledge` 是 AI 生成提示词 + 节点参考的单一数据源）：

| 类型 | 参数 | 用途 | 前置条件 |
|------|------|------|----------|
| ACTION_SET_VARIABLE | name, value, type | 设变量 | |
| ACTION_LOG | message | 记录日志 | |
| ACTION_DELAY | millis | 延时 | |
| CONDITION_SWITCH | expression, branches | 条件分支 | |
| ACTION_TOAST | message | 弹提示 | |
| ACTION_GET_TIME | format, output_var | 取时间 | |
| ACTION_MATH | operation, a, b, output_var | 数学 | |
| ACTION_TEXT_OPERATION | action, input, output_var | 文本处理 | |
| ACTION_CLIPBOARD | action, text, output_var | 剪贴板 | |
| ACTION_PARSE_JSON | json, path, output_var | 解析 JSON | |
| ACTION_HTTP_REQUEST | url, method, output_var, timeout | HTTP 请求 | 需网络 |
| ACTION_LAUNCH_APP | package_name | 打开应用 | |
| ACTION_CLICK | target, region, occurrence, offset_x, offset_y | 点击 | 需无障碍 |
| ACTION_INPUT_TEXT | text, mode | 输入文字 | 需无障碍 |
| ACTION_READ_SCREEN | keyword, output_var | 读屏幕 | 需无障碍 |
| ACTION_FIND_ELEMENT | selector, search_mode, region, timeout_ms, output_var | 找元素 | 需无障碍 |
| ACTION_GLOBAL_KEY | key | 全局按键 | 需无障碍 |
| ACTION_SHELL | command, mode | shell 命令 | 需 Shizuku/root |
| ACTION_GET_BATTERY | output_var | 电量 | |
| ACTION_NETWORK_STATUS | action | 网络 | |
| ACTION_GET_DEVICE_INFO | | 设备信息 | |
| ACTION_SCREENSHOT | format, output_var | 截图 | 需截图权限 |
| ACTION_VIBRATE | duration | 振动 | |
| ACTION_TEXT_TO_SPEECH | text, language | 语音 | |
| ACTION_FILE_OPERATION | action, path, content, output_var | 文件 | |
| ACTION_AI_CHAT | prompt, output_var | 本地 AI 对话 | |
| ACTION_CALL_DIRECT | phone_number, sim | 直接拨号 | 需 CALL_PHONE |
| ACTION_CALL_PHONE | phone_number | 拨打 | 需 CALL_PHONE |
| ACTION_SEND_NOTIFICATION | title, message, channel_id | 发通知 | |
| ACTION_SWIPE | x1,y1,x2,y2,duration_ms | 滑动 | 需无障碍 |
| ACTION_MEDIA_CONTROL | cmd | 媒体控制 | |

完整分类见各 `NodeModules`；`NodeKnowledge` 是 AI 生成提示词 + 节点参考的单一数据源。

---

## AI 工作流生成（核心卖点，LLM-friendly）

**自然语言 → 工作流 JSON**，由 DeepSeek 生成（提示词从 `NodeKnowledge` 动态生成，含安卓操作指南）：

```kotlin
WorkflowAIGenerator.generate(context, "拨打10086并开启录音")  // → GeneratedWorkflow(workflowJson, ...)
```

- **13 个典型场景生成正确率 100%**（含"直接拨号用 CALL_DIRECT 而非 UI 模拟"、"多实例文本用 region"校验）
- 提示词含：86 节点清单 + 常见包名映射 + 优先规则（直接 API 优先）+ 权限前置 + few-shot
- 单次生成成本 ≈ 0.02-0.05 元（DeepSeek）
- App 内「AI 生成」独立底栏：多轮对话记忆（history）、编辑后保存、挂载悬浮窗

## MCP 移动端 Agent（AI 控制手机）

引擎暴露 **MCP server**（JSON-RPC 2.0）：

```bash
adb forward tcp:8931 tcp:8931          # 服务默认只监听手机 127.0.0.1
ws://127.0.0.1:8931/mcp                # Authorization: Bearer <令牌>（或 ?token=）
```

- `initialize / tools/list / tools/call` 标准 MCP，任意 LLM 客户端可接入
- **鉴权**：令牌每台设备首次启动随机生成，在 App「Agent」页查看/复制/重新生成；局域网访问默认关闭；Shell、文件、短信、拨号等高危节点远程默认拒绝，需在 Agent 页手动开启
- 支持 `region`/`occurrence` 精准定位多实例文本（如"点下半屏的确定"）
- 微信读屏：只在 `wechat` 渠道包里（`./gradlew :app:assembleWechatDebug`）。微信只对类名为 `SelectToSpeakService` 的服务返回节点，这等于冒用 Google 组件身份，**仅限自用，不能上架分发**。默认的 `standard` 包不含它，树为空时用 ML Kit 中文离线 OCR
- 电脑侧 `scripts/agent_daemon.mjs`（常驻长连接）/ `mcp_call.mjs`（单次调用，`--full` 看完整字段）

### 自主模式：规划（DeepSeek）+ 执行（Jev）+ 代码控制

```
目标 ─► DeepSeek 规划：拆成几步，每步带屏幕上可验证的完成条件；能用设备工具就直接用
          ▼
   每一步：工具步直接调设备功能 ／ 界面步由执行器（Jev / DeepSeek / 本地）逐屏选一个候选动作
          │  卡住（兜圈 / 无变化 / 低置信）→ 从当前屏幕重规划剩下的部分（≤2 次）
          ▼
   DeepSeek 复核：按屏幕证据判断是否真的完成；提问类目标在这里读出答案
```

- **模型只做选择，控制在代码**：执行器只能从代码生成的候选里选；步骤推进、导航栈与页面图、判重、换招、安全闸都在代码里
- **设备工具**：设置页直达（15 页）、开关 WLAN/蓝牙/手电/免打扰、调亮度音量、读电量/时间/设备/网络；会改设备状态的工具必须由用户目标原文点名
- **页面状态**：包名 + Activity + 标题识别当前页；开关开/关、滑块百分比、输入框内容进入候选；导航栈告诉模型"在哪一层、返回到哪、哪些入口试过"
- **安全**：规划器输出一律不可信——工具/参数白名单、输入文字必须是目标原文子串、支付/权限弹窗只看用户原文放行
- **入口**：Agent 页（执行器可选 Jev / DeepSeek / 本地，「DeepSeek 规划与复核」开关）；MCP `agent_start`（`goal` / `provider` / `max_steps` / `planner`）、`agent_stop`、`agent_status`
- **Jev 网络**：国内网络直连 Jev 返回 451。手机开代理/VPN，或在 Agent 页「Jev 代理」填 `127.0.0.1:<电脑代理端口>` 并 `adb reverse tcp:<端口> tcp:<端口>`

**真机评测**（一台 vivo，Android 16；结果用 adb/读屏独立验证，不看 agent 自己说的 DONE）。方法、原始数据与局限见 [docs/EVAL.md](docs/EVAL.md)。

| 执行器 | 有规划 | 无规划 | 日期 / 提交 |
|---|:---:|:---:|---|
| Jev | 28/28 | 13/28 | 2026-10-09 · `09b0632`，7 类任务 × 4 次 |
| DeepSeek | 28/28 | 18/28 | 2026-10-09 · `09b0632`，7 类任务 × 4 次 |
| DeepSeek | 10/10 | 7/10 | 2026-10-14 · `9069155`，10 类任务（新增发消息/打电话） |

任务：进蓝牙页、设置内搜索、进入关于手机、时钟切到秒表、亮度调到最高、问电量、问安卓版本号；2026-10-14 起另有不带引号的搜索、给 QQ 联系人输入消息、按名字给运营商打电话。复现：`node scripts/agent_eval.mjs`（任务在 `scripts/agent_eval_tasks.json`）。详见 [docs/AGENT.md](docs/AGENT.md)。

## 测试

- 单元测试（JVM，无需真机）：`./gradlew :lib:testDebugUnitTest :app:testDebugUnitTest`（126 条）
- 引擎自测：`scripts/run_selftest.ps1`（**68 条回归 = 53 PASS / 0 FAIL / 15 SKIP**，需真机）
- 自主模式真机评测：`node scripts/agent_eval.mjs`（`scripts/agent_eval_show.mjs` 看明细）
- LLM 生成正确率：`scripts/verify_workflow_gen.ps1`（13 场景）

## 独立发布（AAR）

`:lib` 已配置 maven-publish：
```bash
./gradlew :lib:publishReleasePublicationToMavenLocal   # 出 habitat-engine:1.8.0 AAR
```
三方接入见 `docs/ENGINE_USAGE.md`。

---

### 交互 (6)
| 类型 | 功能 |
|------|------|
| `ACTION_CLICK` | 点击坐标或无障碍元素（`offset_x`/`offset_y` 相对目标中心偏移） |
| `ACTION_SWIPE` | 直线滑动 |
| `ACTION_LONG_PRESS` | 长按 |
| `ACTION_INPUT_TEXT` | 输入文字 (a11y / shell / clipboard) |
| `ACTION_GLOBAL_KEY` | 全局按键 (back / home / recents / …) |
| `ACTION_FIND_ELEMENT` | 查找 UI 元素 |

### 系统控制 (11)
| 类型 | 功能 |
|------|------|
| `ACTION_SHELL` | 执行 Shell 命令 (Shizuku / Root) |
| `ACTION_LAUNCH_APP` | 启动应用 |
| `ACTION_FORCE_STOP_APP` | 强制停止应用 |
| `ACTION_SCREEN_WAKE` | 锁屏唤醒 / 休眠 / 解锁 |
| `ACTION_WIFI` | WiFi 开关 / 切换 / 状态 |
| `ACTION_BLUETOOTH` | 蓝牙开关 / 切换 / 状态 |
| `ACTION_VOLUME` | 音量设置 / 获取 / 增减 / 静音 |
| `ACTION_BRIGHTNESS` | 亮度设置 / 获取 / 自动 |
| `ACTION_FLASHLIGHT` | 手电筒开关 |
| `ACTION_CALL_PHONE` | 打开拨号器 |
| `ACTION_SHARE` | 系统分享 |

### 数据处理 (8)
| 类型 | 功能 |
|------|------|
| `ACTION_SET_VARIABLE` | 设置变量 (string / int / float / bool) |
| `ACTION_TEXT_OPERATION` | 文本处理 (替换 / 分割 / 子串 / 大小写) |
| `ACTION_MATH` | 数学运算 (加减乘除 / 取模 / 幂) |
| `ACTION_CLIPBOARD` | 剪贴板读写 |
| `ACTION_PARSE_JSON` | JSON 解析 (点号路径) |
| `ACTION_PARSE_XML` | XML 解析 (XPath) |
| `ACTION_BASE64` | Base64 编解码 |
| `ACTION_FILE_OPERATION` | 文件读写 / 删除 / 存在检查 / 列表 |

### 逻辑控制 (6)
| 类型 | 功能 |
|------|------|
| `CONDITION_SWITCH` | 条件分支 (`branches: {true, false}`) |
| `CONDITION_ADVANCED_SWITCH` | 高级条件 (== != > < >= <= contains …) |
| `ACTION_DELAY` | 延迟等待 (ms, 最大 60000) |
| `ACTION_LOOP` | 循环 (计数 / 表达式, `branches: {loop, end}`) |
| `ACTION_TRY_CATCH` | 异常捕获 (`branches: {success, error}`) |
| `ACTION_LOG` | 日志输出 (debug / info / warn / error) |

### 感知 (5)
| 类型 | 功能 |
|------|------|
| `ACTION_READ_SCREEN` | 无障碍读屏 (关键词搜索) |
| `ACTION_READ_SMS` | 读取短信 / 提取验证码 |
| `ACTION_GET_APP_INFO` | 前台应用包名 + Activity |
| `ACTION_FOREGROUND_APP` | 前台应用 (增强版) |
| `ACTION_APP_SEARCH` | 搜索已安装应用 |

### 网络 (2)
| 类型 | 功能 |
|------|------|
| `ACTION_HTTP_REQUEST` | HTTP GET / POST / PUT / DELETE (含 headers + body) |
| `ACTION_NETWORK_STATUS` | 查询网络类型、WiFi IP、移动网络 IP |

### 媒体 & UI (8)
| 类型 | 功能 |
|------|------|
| `ACTION_SCREENSHOT` | 截屏 (Base64 或文件) |
| `ACTION_TEXT_TO_SPEECH` | 文字转语音 (zh / en / ja / ko) |
| `ACTION_TOAST` | Toast 消息 |
| `ACTION_VIBRATE` | 振动反馈 |
| `ACTION_SEND_NOTIFICATION` | 系统通知 |
| `ACTION_DYNAMIC_ISLAND` | 灵动岛悬浮通知 |
| `ACTION_MINI_SCREEN` | 悬浮小屏：工作流 / Agent / MCP 脚本实时写入文字、图片、进度（可拖动、折叠；Agent 点击时自动让开） |
| `ACTION_WORKSPACE` | 本机工作区：结果、笔记、图片的写/追加/读/列/删（在 App「工作区」页查看，只存本机） |

### AI (1)
| 类型 | 功能 |
|------|------|
| `ACTION_AI_CHAT` | 本地 LLM 推理 (LiteRT-LM / Gemma 4 2B) |

### 工具 (2)
| 类型 | 功能 |
|------|------|
| `ACTION_GET_TIME` | 获取当前时间 (自定义格式) |
| `ACTION_RANDOM` | 随机数 (int / float / bool) |

完整参数参考 → [HABITAT_API.md](HABITAT_API.md)
共享契约（客户端 + 服务端）→ 服务端仓库 `docs/API_CONTRACT.md`

---

## 工作流示例

### 验证码自动填入

```json
{
  "start_node_id": "read",
  "nodes": {
    "read": {
      "type": "ACTION_READ_SMS",
      "params": { "filter_by": "both", "sender": "验证码", "extract_code": true },
      "next": "check"
    },
    "check": {
      "type": "CONDITION_SWITCH",
      "params": { "expression": "sms_found == true" },
      "branches": { "true": "copy", "false": "fail" }
    },
    "copy": {
      "type": "ACTION_CLIPBOARD",
      "params": { "action": "set", "text": "${verification_code}" },
      "next": "input"
    },
    "input": {
      "type": "ACTION_INPUT_TEXT",
      "params": { "text": "${verification_code}", "mode": "a11y" }
    },
    "fail": {
      "type": "ACTION_TOAST",
      "params": { "message": "未找到验证码" }
    }
  }
}
```

### 一键系统设置

```json
{
  "start_node_id": "s1",
  "nodes": {
    "s1":   { "type": "ACTION_WIFI",       "params": { "action": "on" },               "next": "s2" },
    "s2":   { "type": "ACTION_BRIGHTNESS", "params": { "action": "set", "value": 80 }, "next": "s3" },
    "s3":   { "type": "ACTION_VOLUME",     "params": { "action": "set", "stream": "music", "value": 40 }, "next": "done" },
    "done": { "type": "ACTION_TOAST",      "params": { "message": "已配置完成" } }
  }
}
```

更多示例 → `HabitatSamples` ([HabitatJson.kt](lib/src/main/java/com/ailun/habitat/HabitatJson.kt))

---

## DAG 可视化编辑器

编辑器通过自定义 Canvas View 将工作流渲染为交互式有向无环图。

| 特性 | 说明 |
|------|------|
| 布局 | Sugiyama 风格分层自动布局 |
| 节点颜色 | 按功能类别着色（交互 / 系统 / 数据 / 逻辑 / 网络 / 感知） |
| 边类型 | 灰色实线 (`next`)、绿色 (`true` 分支)、红色 (`false` 分支)、橙色虚线 (循环) |
| 手势 | 单指平移、双指缩放 (0.3×–3.0×)、双击复原、点击选中 |
| 切换 | JSON 文本编辑 ⇄ DAG 流程图一键切换 |

节点的 `label` 和 `description` 字段控制 DAG 显示标签（可选，完全向前兼容）。

```
EditorTab (Compose)
  ├── OutlinedTextField: 名称 / 描述 / JSON
  ├── 切换按钮: "JSON" ⇄ "流程图"
  ├── AndroidView → DagView (Canvas)
  │     ├── DagLayoutEngine (自动布局)
  │     └── HabitatDagConverter (WorkflowGraph → DagGraph)
  └── 执行日志 (LazyColumn)
```

---

## 本地 AI (LiteRT-LM)

Habitat 内置 Google LiteRT-LM 实现本地大模型推理——无需联网。

- **模型**: `.litertlm` 格式，推荐 Gemma 4 2B (~2.4 GB)
- **路径**: `/storage/emulated/0/Download/llm/`
- **权限**: 需要「所有文件访问权限」才能读取模型

在「个人主页」→「LLM 控制台」中加载模型并测试。工作流中通过 `ACTION_AI_CHAT` 调用：

```json
{
  "type": "ACTION_AI_CHAT",
  "params": {
    "prompt": "请总结以下内容：${screen_content}",
    "output_var": "summary"
  },
  "next": "next_step"
}
```

---

## 项目结构

```
Habitat/                          # 根项目
├── lib/                          # :lib — 引擎（零 Android UI 依赖）
│   └── com/ailun/habitat/
│       ├── api/                  # IAccessibilityProvider, IShellExecutor
│       ├── handlers/             # 86 INodeHandler 实现
│       ├── HabitatExecutor.kt    # 协程式执行循环
│       ├── HabitatJson.kt        # JSON 解析 + 示例工作流
│       ├── WorkflowGraph.kt      # 图模型 (start_node_id + nodes)
│       ├── WorkflowNode.kt       # 节点模型 (id, type, params, next, branches)
│       ├── WorkflowContext.kt    # 运行时状态, ${var} 插值
│       ├── NodeHandlerFactory.kt # 类型字符串 → handler 注册表
│       └── INodeHandler.kt       # 单一方法 handler 接口
│
├── app/                          # :app — Compose UI, 桥接, 服务
│   └── com/ailun/habitat/app/
│       ├── ui/                   # ProfileTab, HabitatSettingsActivity
│       ├── bridge/               # AppAccessibilityProvider, ShizukuShellExecutor
│       ├── ai/                   # LLMServiceProvider, LiteRtLLMServiceImpl
│       ├── dag/                  # DagView, DagLayoutEngine, DagModel
│       ├── triggers/             # 通知 / 短信 / 定时 / 剪贴板 / 按键 触发器
│       ├── HabitatDashboard.kt   # 主 Scaffold + 三 Tab 导航
│       ├── FloatWindowUI.kt      # 悬浮球 + 展开面板
│       ├── HabitatFloatService.kt
│       └── HabitatTheme.kt       # Material 3 青绿色主题
│
├── TODO.md                       # 任务追踪 (客户端)
├── README.md                     # 英文 README
└── README_CN.md                  # 本文件
```

### 依赖解耦

引擎 (`:lib`) 对应用模块 (`:app`) **零编译时依赖**。所有系统访问通过两个接口：

```
INodeHandler
    ↑
    ├── IAccessibilityProvider   (getService, foregroundPackage, foregroundActivity)
    └── IShellExecutor           (exec: suspend (cmd, asRoot) → String)
            ↑
    bridge/  (应用模块实现)
    ├── AppAccessibilityProvider   ← 弱引用持有无障碍服务
    └── ShizukuShellExecutor       ← 委托给 HabitatShellManager
```

要在其他应用中使用引擎，只需实现这两个接口。

---

## 云端后端 (Habitat Server)

Spring Boot 3.5 后端提供 AI 驱动的工作流生成与网络服务发布。详见服务端仓库 `README.md`。

| 功能 | 端点 |
|------|------|
| AI 工作流生成 | `POST /api/v1/workflows/generate` |
| 工作流 CRUD | `GET/PUT/DELETE /api/v1/workflows/{id}` |
| API 密钥管理 | `POST/GET/DELETE /api/v1/keys` |
| 发布为网络服务 | `POST /api/v1/endpoints` |

---

## 权限要求

| 功能 | 所需权限 |
|------|---------|
| 点击 / 滑动 / 读屏 / 输入 | 无障碍服务 |
| 读短信 | `READ_SMS` |
| 发通知 | `POST_NOTIFICATIONS` |
| 振动 | `VIBRATE` |
| Shell (系统级) | Shizuku 或 Root |
| 截屏 | 无障碍服务 |
| 手电筒 | `CAMERA` |
| 拨号 | 无需权限 (使用 `ACTION_DIAL`) |

---

## 扩展 Handler

```kotlin
class MyHandler : INodeHandler {
    override suspend fun handle(node: WorkflowNode, ctx: WorkflowContext): String? {
        val param = ctx.interpolate(node.params?.get("key")?.toString() ?: "")
        ctx.variables["result"] = "done"
        return node.next
    }
}

// 注册
factory.register("MY_ACTION", MyHandler())
```

---

## 常见问题

| 问题 | 解决方案 |
|------|---------|
| 工作流无法启动 | 检查 `start_node_id` 是否存在于 `nodes` 中 |
| 变量解析为 `"null"` | 检查变量是否已通过 `ACTION_SET_VARIABLE` 设置 |
| 无限循环 | 检查循环条件；硬上限为 1000 步 |
| 无障碍操作无效 | 确认无障碍服务已开启并运行中 |
| Shell 命令无输出 | 检查 Shizuku / Root 连接状态 |
| WiFi / 蓝牙操作无效 | Android 10+ 限制，可能需要系统权限 |

---

**Habitat** · 86 节点类型 · AI 工作流生成（LLM-friendly）· MCP Agent · `habitat-server` 配套后端
