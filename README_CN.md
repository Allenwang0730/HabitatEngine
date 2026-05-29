# Habitat — JSON 驱动的 Android 自动化工作流引擎

> 轻量、AI 友好、40+ 节点类型。工作流定义为扁平 JSON——专为 LLM 生成设计，非手工编写。

[![Kotlin](https://img.shields.io/badge/Kotlin-2.3.20-purple)]()
[![Min SDK](https://img.shields.io/badge/API-29+-green)]()
[![Nodes](https://img.shields.io/badge/Nodes-40+-blue)]()
[![Compose](https://img.shields.io/badge/UI-Compose_M3-orange)]()

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

## 节点类型速览 (40+)

### 交互 (6)
| 类型 | 功能 |
|------|------|
| `ACTION_CLICK` | 点击坐标/无障碍元素 |
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

### 媒体 & UI (6)
| 类型 | 功能 |
|------|------|
| `ACTION_SCREENSHOT` | 截屏 (Base64 或文件) |
| `ACTION_TEXT_TO_SPEECH` | 文字转语音 (zh / en / ja / ko) |
| `ACTION_TOAST` | Toast 消息 |
| `ACTION_VIBRATE` | 振动反馈 |
| `ACTION_SEND_NOTIFICATION` | 系统通知 |
| `ACTION_DYNAMIC_ISLAND` | 灵动岛悬浮通知 |

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
│       ├── handlers/             # 40+ INodeHandler 实现
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

**Habitat** · 40+ 节点类型 · AI 友好的扁平 JSON · `habitat-server` 配套后端
