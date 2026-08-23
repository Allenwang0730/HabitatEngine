# Habitat 引擎接入指南

Habitat 是**LLM 友好的安卓自动化引擎**：把 84 个"节点"（操作）暴露为一个工作流执行器，LLM 可生成工作流 JSON 驱动手机自动化。本文面向第三方集成（把你的项目变成"能调用引擎"的宿主）。

## 一、依赖（独立 AAR）

引擎位于 `:lib`，已配置 maven-publish，可发布到 mavenLocal 后作为依赖消费：

```bash
# 在引擎仓库根目录
./gradlew :lib:publishReleasePublicationToMavenLocal
```

在你的安卓项目 `settings.gradle.kts` 加 mavenLocal，`build.gradle.kts` 加依赖：
```kotlin
repositories { mavenLocal() }

dependencies { implementation("com.ailun:habitat-engine:1.6.0") }
```

或直接用 `:lib` 的 AAR 产物（`lib/build/outputs/aar/lib-release.aar`）文件依赖。

引擎核心 API（`com.ailun.habitat`）：
- `NodeHandlerFactory` — 节点注册表（84 节点）
- `HabitatExecutor` — 工作流执行器
- `WorkflowContext` — 执行上下文（线程安全变量）
- `HabitatJson.fromJson(json)` — 解析/校验工作流

## 二、最小使用示例（Kotlin）

```kotlin
// 1. 建工厂（无障碍 provider 可传 null；shell 可传 Shizuku 实现）
val factory = NodeHandlerFactory(a11yProvider, shellExecutor)

// 2. 一个工作流 JSON（打开设置→弹提示）
val json = """
{"start_node_id":"s1","nodes":{
  "s1":{"id":"s1","type":"ACTION_LAUNCH_APP","params":{"package_name":"com.android.settings"},"next":"s2"},
  "s2":{"id":"s2","type":"ACTION_TOAST","params":{"message":"已打开设置"}}}}
""".trimIndent()

// 3. 校验 + 执行
val graph = HabitatJson.fromJson(json)
val ctx = WorkflowContext(appContext)
HabitatExecutor(factory).execute(graph, ctx) { line -> Log.i("Habitat", line) }
// 或走统一服务带回调：
HabitatExecutionService.start(
    workflowId = "demo", jsonContent = json, androidContext = appContext,
    factory = factory,
    onLog = { Log.i("Habitat", it) },
    onResult = { vars -> /* 工作流结束，变量快照 */ },
)
```

## 三、节点体系

**节点 = 一个操作**，86 个覆盖：交互（CLICK/SWIPE/INPUT_TEXT）、系统（LAUNCH_APP/CALL_DIRECT/READ_SCREEN）、数据（SET_VARIABLE/MATH/PARSE_JSON/HTTP）、媒体、网络、传感、AI 等。常用节点见 `NodeKnowledge` 或 `scripts/` 下文档。

关键概念：
- **params**：节点参数；字符串支持 `${var}` 插值（变量跨节点传值）
- **next/branches**：节点跳转；`CONDITION_SWITCH` 条件分支
- **on_error**：节点失败处理（`skip` 默认 / `stop` 终止整棵树），`require` 声明成功条件
- **trigger**：工作流顶层 `{"trigger":{"type":"timer|notification|..."}}` 自动触发

## 四、运行节点所需的权限/前置

| 能力 | 前置 |
|---|---|
| READ_SCREEN / CLICK / FIND_ELEMENT / INPUT_TEXT | 无障碍服务 |
| CALL_DIRECT | CALL_PHONE 权限 |
| 截图 / 录音 / 相机 | 对应运行时权限 |
| shell 命令 | Shizuku/root（无则 Runtime.exec 兜底，部分失败）|
| HTTP / 天气 / 地理编码 | 网络 |

## 五、LLM 接入（推荐）

引擎暴露 **MCP server**（`/mcp`，JSON-RPC 2.0），任意支持 MCP 的 LLM 可调用 82 个工具。含授权（`?token=`）：
```
ws://<host>:8931/mcp?token=<token>   → initialize / tools/list / tools/call
```

或 LLM 生成工作流 JSON（引擎读 `NodeKnowledge` 动态生成提示词，DeepSeek 实测多场景 100% 正确率），`WorkflowAIGenerator.generate(context, 用户需求)` 直接出工作流。

## 六、工作流生成（App 内）

`app` 模块的 `WorkflowAIGenerator` 提供"自然语言 → 工作流 JSON"：
```kotlin
WorkflowAIGenerator.generate(context, "拨打10086并开启录音")  // 返回 GeneratedWorkflow
```
提示词由 `NodeKnowledge`（节点百科）动态生成——加新节点只改 NodKnowledge 一处。

## 七、可用的工程入口
- **App 内**：工作流库列表/编辑（主页卡片编辑）、AI 工作流生成（聊天式）、Agent 控制页（MCP 服务开关/端口/token）
- **电脑侧**：`scripts/agent_daemon.mjs`（常驻 MCP 客户端，长连接）、`scripts/mcp_call.mjs`、`scripts/run_selftest.ps1`（68 条自测回归）、`scripts/verify_workflow_gen.ps1`（LLM 生成正确率验证）
