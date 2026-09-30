# HANDOFF — Habitat 项目交接文档（新窗口/新 Agent 先读这份 + AGENTS.md）

> 生成时间：本文件随项目状态更新。**新窗口接手前：读本文件 + `AGENTS.md`（铁律/踩坑记忆）+ `docs/ENGINE_MASTER_PLAN.md`（总规划）。**

---

## 一、项目是什么 / 目标

**Habitat** = 安卓自动化引擎 + 端侧 agent 运行时。

- **原始目标（用户原话）**："造轮子：安卓的自动化控制库，LLM 友好，开箱即用"
- **延伸目标**："做成 agent 运行时工具"（让 LLM/AI agent 通过 MCP 直接操控手机）
- **形态**：JSON 工作流图 → 引擎执行（84 个语义节点）→ 无障碍 + 系统 API 双轨；对外暴露 **MCP server**（AI 客户端可调）。
- **核心卖点**：扁平 JSON（LLM 易生成）、语义节点（非 UI 原语）、本机离线、事件触发、可视化编辑、可嵌入 AAR。
- **姊妹项目**：`%USERPROFILE%\SmartCS`（智能客服 App，用 Habitat 引擎的早期拷贝）。

## 二、仓库 / 环境基本信息

| 项 | 值 |
|---|---|
| 引擎仓库 | `%USERPROFILE%\Habitat` · 远程 `git@github.com:Allenwang0730/HabitatEngine.git`（分支 main）|
| 姊妹仓库 | `%USERPROFILE%\SmartCS`（智能客服，有 git；**其 lib 不动，app 可改但做好版本管理**）|
| 技术栈 | Kotlin 2.3.20 · Compose M3 · AGP 9.0.1 · compileSdk 36 / minSdk 29 · Gson · Shizuku 13.1.5 · LiteRT-LM 0.11.0 |
| 模块 | `:lib`（纯引擎，零依赖 app）· `:app`（宿主 UI + 桥接实现）|
| 构建 | `./gradlew :app:assembleDebug`；APK 在 `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` |
| 真机 | iQOO（adb 序列号 `<è®¾å¤åºåå·>`，USB）；手机 WiFi `<ææº IP>`，PC `<çµè IP>` |
| adb | `%USERPROFILE%\AppData\Local\Android\Sdk\platform-tools\adb.exe` |
| MCP 服务 | `ws://<手机IP>:8931/mcp?token=habitat-dev-token`（端口/token 在 App「Agent」页可改）|
| 启动 App | `adb shell am start -n com.ailun.habitat/.app.HabitatMainActivity` |
| App 包名 | `com.ailun.habitat` |
| DeepSeek key | **不硬编码**，App「AI 生成」页填（存 SharedPreferences）|

## 三、记忆 / 规则位置（重要）

| 位置 | 作用 |
|---|---|
| **`AGENTS.md`**（仓库根）| **通用 Agent 指令 + 踩坑记忆（= 本项目"记忆"）**：敏感信息铁律（密钥绝不硬编码/提交）、权限声明铁律（节点用到的权限必须在 manifest 声明）、引擎约定。**改代码前必读** |
| `CLAUDE.md`（仓库根）| Claude Code 专属技术文档（build/架构/模块）。**不是通用记忆，别往里写规范** |
| `docs/ENGINE_MASTER_PLAN.md` | 引擎总规划 |
| `docs/ROADMAP.md` / `docs/AGENT.md` / `docs/SELFTEST.md` | 路线图 / agent 设计 / 自测说明 |
| `docs/ENGINE_USAGE.md` | 三方接入指南（AAR 依赖/初始化/执行/权限/LLM 接入）|
| `docs/ENGINE_EXTENDING.md` | 扩展/新节点开发 |
| `HABITAT_API.md` | 节点 API 手册（含 CLICK region/occurrence）|
| `README.md`（英文）· `README_CN.md`（中文）| 项目说明（两版语言分开，内容同步）|

## 四、关键文件路径

### 引擎核心（`lib/src/main/java/com/ailun/habitat/`）
| 文件 | 作用 |
|---|---|
| `HabitatExecutor.kt` | 工作流执行器（协程循环、1000 步上限、FORK/JOIN、stop 传播）|
| `HabitatExecutionService.kt` | 统一执行入口（start/stop/activeJobs、异常不重抛）|
| `WorkflowContext.kt` | 执行上下文（ConcurrentHashMap 变量、interpolate、stopRequested、forkJobs）|
| `NodeHandlerFactory.kt` | 节点注册表（type → handler；`ACTION_*` 常量）|
| `NodeModules.kt` | 模块化注册（CORE/INTERACTION/SYSTEM/TELEPHONY/MEDIA/NETWORK/SENSOR/AI）|
| `HabitatJson.kt` / `WorkflowGraph.kt` / `WorkflowNode.kt` | JSON 解析 + 模型 |
| `HabitatAccessibility.kt` | 无障碍操作封装（dispatchGestureAwait 等）|
| `WorkflowRepository.kt` / `HabitatStateStore.kt` | 工作流持久化 / 全局状态（runningStates、libraryVersion）|
| `api/IAccessibilityProvider.kt` · `api/IShellExecutor.kt` | 解耦接口（app 侧实现）|
| `handlers/*.kt`（84 个）| 各节点实现，如 `NodeCallDirectHandler.kt`、`NodeClickHandler.kt`、`NodeFindElementHandler.kt`、`NodeReadScreenHandler.kt` |
| `selftest/NodeSelfTests.kt` + `NodeSelfTest.kt` | 68 条回归自测（`NodeSelfTestRunner`）|

### App（`app/src/main/java/com/ailun/habitat/app/`）
| 文件 | 作用 |
|---|---|
| `HabitatMainActivity.kt` | 入口 Activity（launcher = `.app.HabitatMainActivity`）|
| `HabitatDashboard.kt` | 主界面：底部 4 tab（工作流/AI 生成/Agent/个人主页）+ **编辑器内嵌主页卡片** + runGraphJson（含 CALL_PHONE 权限请求）|
| `ui/AIWorkflowScreen.kt` | AI 工作流生成（聊天式、会话记忆=Activity 级 VM、返回键、imePadding、编辑/保存/挂载去重）|
| `ui/AgentScreen.kt` | Agent/MCP 控制页（服务开关、局域网 IP、端口、**token**）|
| `ui/ProfileTab.kt` | 个人主页（LLM 控制台入口、权限管理、AI 生成入口）|
| `AgentService.kt` | **MCP/WS 服务端**（Ktor Netty，`/health` `/tools` `/ws` `/mcp`，**token 鉴权**，`screenSnapshot`）|
| `ai/NodeKnowledge.kt` | **节点百科（单一数据源）**：节点清单 + 包名映射 + 规则 + few-shot → 动态生成 LLM 提示词 |
| `ai/WorkflowAIGenerator.kt` | LLM 生成工作流（buildSystemPrompt 从 NodeKnowledge 拼、parseAndValidate）|
| `ai/WorkflowAIClient.kt` | DeepSeek/OpenAI 兼容 chat 客户端（**key 从配置读，无硬编码**）|
| `HabitatAccessibilityService.kt` | 无障碍服务 |
| `bridge/AppAccessibilityProvider.kt` · `bridge/ShizukuShellExecutor.kt` · `HabitatShellManager.kt` | 桥接实现 |
| `TriggerManager.kt` + `triggers/*` | 触发器（通知/短信/定时/剪贴板/按键）|
| `dag/*` | DAG 可视化（DagView/DagLayoutEngine/DagModel/HabitatDagConverter）|
| `FloatWindowManager.kt` / `FloatWindowUI.kt` / `HabitatFloatService.kt` | 悬浮窗 |
| `NodeSelfTestActivity.kt` | 自测宿主（`--ez auto true`，结果写 `files/test_results.json`）|
| `HabitatMainActivity` manifest | **权限声明在 `app/src/main/AndroidManifest.xml`（加节点务必同步权限！）** |

### 脚本（`scripts/`）
| 文件 | 作用 |
|---|---|
| `agent_daemon.mjs` | 电脑侧常驻 MCP 客户端（长连接，本地 HTTP :18080，14× 加速）|
| `mcp_call.mjs` | 单次 MCP 调用（带 `?token=`）|
| `run_selftest.ps1` | 跑 68 条回归（默认 APK `app-arm64-v8a-debug.apk`）|
| `verify_workflow_gen.ps1` | LLM 生成正确率验证（13 场景；key 从 `-ApiKey`/`DEEPSEEK_API_KEY` 读）|
| `scan_secrets.sh` | 密钥扫描（pre-commit hook 调用）|
| `agent_ws.ps1` | WS 调试 |

## 五、当前状态（截至本文件）

**已完成能力**
- 引擎 **84 节点** + 68 条回归（最近一次真机：**53 PASS / 0 FAIL / 15 SKIP**，SKIP 为需权限/Shizuku/手工项）
- **MCP server**（85 工具）+ **token 鉴权**（`/ws` `/mcp` 需 `?token=`）
- **自主模式**（1.7.0）：读屏 → 决策（Jev / DeepSeek / 本地）→ 节点执行 → 循环。Agent 页可开；MCP `agent_start` / `agent_stop` / `agent_status`。Jev key 在 Agent 页填，不进代码
- **微信读屏**：系统设置里另开「Habitat 微信读屏」（`SelectToSpeakService`）。树为空时 ML Kit 中文离线 OCR。改过无障碍配置后要关掉再开一次
- **AI 生成工作流**：`NodeKnowledge` 动态提示词 → DeepSeek，**13 场景 100% 正确**；单次成本 ≈¥0.02–0.05
- **CLICK/FIND_ELEMENT 支持 `region` + `occurrence`**（多实例文本精准定位，如"下半屏的确定"）
- **独立 AAR 发布配置**（`:lib` maven-publish → `com.ailun:habitat-engine:1.7.0`）
- App UI：4 tab（工作流/AI 生成/Agent/个人主页），编辑器内嵌主页卡片
- 直拨 `ACTION_CALL_DIRECT` 走 `TelecomManager.placeCall`（后台可拨）
- 安全：API key 不硬编码、密钥扫描 hook、manifest 权限铁律

**已知问题 / 待办**
- **微信读屏**已接入伪装服务 + OCR 兜底；vivo/微信新版本是否仍放行节点，要以真机为准（开「Habitat 微信读屏」后进微信看 Agent 循环日志里的 `source`）
- `GET_SETTING` 在该 vivo 上对 App 进程不可用（SKIP）
- 部分 OEM（vivo）行为差异；`CALL_PHONE`/`READ_CALL_LOG` 已补声明
- 未做：MCP 更强鉴权、开发者 README、ROM 兼容专项
- SmartCS 侧仍有未修 app 级 bug（BUG-2/3/6/8–12，lib 不动）

**下一步方向（用户关注）**
1. ~~接 Jev + 自主循环 + 微信读屏~~ 已在 1.7.0 落地（本机执行替代 Mobilerun）。真机还没拿 Jev key 跑通一整轮
2. 自主循环的步数、失败恢复、更多动作（长按/等待元素）还粗
3. MCP 更强鉴权、开发者 README、ROM 兼容专项仍未做

## 六、硬约束（务必遵守）

1. **SmartCS 的 lib 完全不动**；SmartCS app 可改但必须 git 版本管理、随时可回退。
2. **密钥/机密绝不硬编码、绝不提交**（见 `AGENTS.md`）；遇泄露：`git filter-repo` 清全历史 + 强推 + 服务方作废 key。
3. **新节点必须同步在 `AndroidManifest.xml` 声明它用到的权限**，否则功能静默失败（CALL_PHONE 漏声明的教训）。
4. 引擎改动后**跑回归**（`scripts/run_selftest.ps1`）+ 编译验证（`./gradlew :app:assembleDebug`）。
5. handler 跑在 `Dispatchers.Default`（无 Looper），**禁用依赖 `Looper.myLooper()` 的 API**。
6. 改 UI/引擎后**装机测试**（`adb install -r` + 启动），真机验证为准。

## 七、常用命令速查

```bash
# 编译
./gradlew :app:assembleDebug            # APK: app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
./gradlew :lib:assembleRelease           # 引擎 AAR
./gradlew :lib:publishReleasePublicationToMavenLocal   # 发到 mavenLocal

# 装机 + 启动
adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
adb shell am start -n com.ailun.habitat/.app.HabitatMainActivity

# 自测（真机；auto=true 跑完自动 finish，结果读文件）
adb shell am start -n com.ailun.habitat/.app.NodeSelfTestActivity --ez auto true
adb shell run-as com.ailun.habitat cat files/test_results.json

# 权限（debug 可强制授予）
adb shell pm grant com.ailun.habitat android.permission.CALL_PHONE
adb shell dumpsys package com.ailun.habitat | grep 'granted'

# LLM 生成正确率验证
$env:DEEPSEEK_API_KEY="..."; ./scripts/verify_workflow_gen.ps1
```
