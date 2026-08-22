# Habitat 引擎全能化 · 总纲（Master Plan）

> 本文档是**唯一权威指南**。换窗口/接手的人，读完这份文档就能无缝继续。
> 最后更新：2026-08-22

---

## 0. 一句话目标

把 Habitat 引擎从"51 个节点的自动化引擎"升级成**"安卓自动化的全能底座"**：模块化（按需引入）+ 全量节点目录 + 自跑测试架 + LLM 生成 + 可嵌入任意 App。

---

## 0.5 本地路径速查（先找路）

**项目仓库：**
- Habitat 引擎本体（原厂）：`C:\Users\wang\Habitat`
  - 引擎源码：`C:\Users\wang\Habitat\lib\src\main\java\com\ailun\habitat\`
  - Habitat App：`C:\Users\wang\Habitat\app\`
  - 引擎文档：`C:\Users\wang\Habitat\docs\`（ENGINE_EXTENDING.md / ROADMAP.md / ENGINE_MASTER_PLAN.md / CHANGELOG.md）
- SmartCS 智能客服（嵌引擎拷贝）：`C:\Users\wang\SmartCS`
  - 引擎拷贝：`C:\Users\wang\SmartCS\lib\src\main\java\com\ailun\habitat\`
  - SmartCS App：`C:\Users\wang\SmartCS\app\src\main\java\com\ailun\smartcs\`
- Java 后端（RAG/LLM 生成/工作流校验）：`C:\Users\wang\Desktop\IDEA Projects\habitat`
  - 白名单：`...\src\main\java\com\habitat\service\WorkflowGenerationService.java` 和 `WorkflowJsonValidator.java`
  - RAG：`...\src\main\java\com\habitat\service\RAGService.java`
  - 知识库：`...\src\main\resources\docs\habitat_api_knowledge.md`

**工具：**
- adb：`C:\Users\wang\AppData\Local\Android\Sdk\platform-tools\adb.exe`
- Android SDK：`C:\Users\wang\AppData\Local\Android\Sdk`
- Maven：后端用 `./mvnw`（在 `C:\Users\wang\Desktop\IDEA Projects\habitat`）
- Gradle：两仓库各自 `./gradlew`

**设备：**
- 测试机：iQOO（1440×3200），通过 USB adb 连接
- 用 `adb shell input tap X Y` 驱动 UI，`adb exec-out screencap -p > f.png` 截图（`MSYS_NO_PATHCONV=1` 避免路径转换）

---

## 1. 项目现状（接手必读）

### 两个仓库

| 仓库 | 路径 | 说明 |
|------|------|------|
| **Habitat**（引擎本体）| `C:\Users\wang\Habitat` | 原厂。`:lib` 引擎 + `:app`(Habitat UI)。GitHub: `Allenwang0730/HabitatEngine`（MIT 开源）|
| **SmartCS**（智能客服）| `C:\Users\wang\SmartCS` | 独立 App，嵌了引擎拷贝。版本 v0.3.3 |

### 引擎现状
- **84 个节点**（`:lib` 注册 82 + 2 个 app 侧 SEND_NOTIFICATION/DYNAMIC_ISLAND）—— 51 个既有 + 阶段 C 16 个 + 第一批纯逻辑 5 个 + 第二批零权限 4 个（GEOCODE/SAY_TO_FILE/GET_TRAFFIC/GET_SETTING）
- `HabitatExecutionService.start(...)` 支持 `initialVars / onComplete / onLog / onResult`（onResult 是"引擎返还内容"的正式通道）
- 新增节点：`ACTION_CALL_DIRECT`、`ACTION_WAIT_CALL_END`、`ACTION_WAIT_CALL_ANSWER`、`ACTION_READ_CALL_LOG`、阶段 C 16 个、第一批数据节点 5 个、第二批 4 个（GEOCODE/SAY_TO_FILE/GET_TRAFFIC/GET_SETTING）
- **本批健壮性/一致性修复（2026-08-22）**：`on_error=stop` 树级终止（`stopRequested`）、工作流 `stop` 级联取消（含 FORK 分支）、fork 取消正确传播不吞 CE、`HabitatExecutionService.activeJobs` 线程安全 + `@Synchronized` 防双执行、`TriggerManager`/`HabitatStateStore` 并发安全、触发器源 F1/F2/F3/F8（Timer 泄漏/NPE、Key 吞按键、timer 间隔门控、重复注册）
- **App 新功能**：AI 工作流生成（DeepSeek，聊天→生成→编辑→多轮→保存→挂载；10 场景 100% 正确率）、MCP Agent 服务（/mcp JSON-RPC 2.0）+ Agent 控制页 + 常驻 MCP daemon（长连接提速 14×）
- 两份 `:lib`（SmartCS + Habitat）**暂不同步**（测试架/新节点只在 Habitat，SmartCS 下次收引擎改动时一并拷入）

### 已交付（勿重复做）
- 去电双模式（无障碍点录音 / 系统自动录音）工作流版
- 来电识别 + 归档（IncomingCallService 常驻、读屏匹配、READ_CALL_LOG 兜底）
- 拨打记录（CallRecordEntity）、主页同步
- SmartCS 版本 v0.1.0 → v0.3.3（git + tag）
- 引擎文档：`docs/ENGINE_EXTENDING.md`、`docs/ROADMAP.md`、CHANGELOG.md
- **阶段 A 自跑测试架（骨架，2026-08-22）**：`:lib` 的 `selftest/` 框架 + 41 条用例 + Habitat `:app` 宿主 `NodeSelfTestActivity` + `scripts/run_selftest.ps1` + `docs/SELFTEST.md`。**决策**：宿主放 Habitat app，**SmartCS 完全不动**，两份 `:lib` 测试架期间暂不同步（详见 SELFTEST.md）。真机验收待做（等设备授权）。
- **阶段 C 16 个候选节点（2026-08-22）**：全部实现并注册（零权限 6 + 中风险 6 + 高风险 4 仅代码），后端白名单×2 + RAGService + 知识库 md 已同步，`Habitat :app:assembleDebug` 与后端 `mvn compile` 均通过；每条配自测用例。真机验收待做。
- **参考项目调研（2026-08-22）**：节点/触发器清单已回填 ROADMAP §5（下一批候选：SET_WALLPAPER / GET_SCREEN_STATE / GET_TRAFFIC / AIRPLANE_MODE / MOBILE_DATA + battery/call_end 触发器）。

---

## 2. 关键约束与坑（写任何节点前必读）

### ⚠️ 线程约束（最重要）
**Handler 跑在 `Dispatchers.Default`（无 Looper 的后台协程线程）。**
- ❌ 不要用依赖 `Looper.myLooper()` 的回调 API（`PhoneStateListener`、`Handler` 派发）→ 会 `Looper.mQueue null` 崩溃
- ✅ 用**同步查询**（如 `TelephonyManager.callState`）或自带 `Executor`/`HandlerThread`/`registerTelephonyCallback(Executor,...)`

### 3 处同步（加一个节点必改）
1. 两份 `:lib` 的 `NodeHandlerFactory.kt`（注册 + 常量）—— SmartCS 与 Habitat 各一份，用 `diff -rq` 确认 0 差异
2. Java 后端白名单：`WorkflowGenerationService.VALID_NODE_TYPES` + `WorkflowJsonValidator.VALID_NODE_TYPES`
3. RAG 文档：`habitat_api_knowledge.md` + `RAGService.java`（硬编码列表 + few-shot 示例）

### 已踩过的坑（写代码避免）
- **vivo FGS 崩溃**：`startForegroundService` 调用成功但系统不注册 → 改普通 service + 悬浮窗
- **`ACTION_CALL` 从 application context 启动**：必须 `FLAG_ACTIVITY_NEW_TASK`
- **通知小图标**：必须用 App 资源 drawable，不能用 `android.R.drawable`
- **Gson 不尊重 Kotlin 默认值**：新增布尔字段在旧 JSON 缺失时变 false → `load()` 里补默认
- **来电号码**：Android 12+ call-state 回调不给号码 → 用 `READ_CALL_LOG`（挂断后）或读屏（实时）

---

## 3. 详细路线图（按优先级）

### 阶段 A：自跑测试架（先做，是铺节点的前提）
**目标**：adb 驱动真机自动跑每个节点的测试工作流，读结果验证，免人工。

**状态：骨架已实现（2026-08-22），真机验收完成（39 PASS / 0 FAIL / 7 SKIP）。**
- 测试框架 + 46 条用例：`lib/.../selftest/`（NodeSelfTest.kt / NodeSelfTests.kt）
- 宿主：Habitat `:app` 的 `NodeSelfTestActivity`（SmartCS 不动）
- 驱动：`scripts/run_selftest.ps1`（一条命令构建→安装→跑→报告）
- 文档：`docs/SELFTEST.md`（含验收记录）
- 验收暴露并修复：SQLITE CREATE_IF_NECESSARY、Activity UI 线程崩溃、3 处用例缺陷、脚本类名/无障碍重连
- 剩余：测试架暴露的 2 个引擎问题（ACTION_LOG 不走 onLog、TRY_CATCH error 分支不可达）待修；7 条设计内 SKIP 需手工/授权后验证

**设计（实现时遵循）**：
- 每个节点一个**测试工作流**（JSON），跑完写 `wf_completed=true`，由 verify 断言读变量判断 pass/fail
- 测试工作流格式：`ACTION_<NODE> → … → done(ACTION_SET_VARIABLE)`，结果经 onResult/变量快照读取
- adb 驱动：`am start` TestActivity → 依次跑测试工作流 → 结果写 `filesDir/test_results.json`（run-as 读）→ push `/sdcard/Habitat/test_results.json`
- 覆盖**不需要物理交互**的节点；需要真实通话/录音的单独列（只能真机手工测）

**验收**：一条 adb 命令能跑完全部低风险节点测试并输出 pass/fail 报告。

### 阶段 B：引擎模块化（NodeModule）
**目标**：`from x import y` 式按需引入，才能放心铺全量节点。

**状态：✅ 已实现（2026-08-22，v1.5.0）**
- `enum NodeModule { CORE, INTERACTION, SYSTEM, TELEPHONY, MEDIA, NETWORK, SENSOR, AI }`
- 编译期分组注册表：`CoreNodes` / `InteractionNodes` / …（无反射、R8 安全）
- `NodeHandlerFactory(a11y, shell, modules: Set<NodeModule>? = null, extra: Map<String, NodeFactory>)`
  - `modules = null`（默认）→ 全量注册（向后兼容，老代码不传不变）
  - `extra` → 单节点/自定义节点注入
  - 懒实例化 + `availableTypes()`
- 自测：模块化冒烟（CORE 32 节点 / 全量 78；INTERACTION 缺失验证）
- 文档偏离说明：原案默认 `setOf(CORE)` 会破坏老调用，改为 `null = 全量`

```kotlin
// 用法：App 按需引入
val factory = NodeHandlerFactory(a11y, shell, modules = setOf(NodeModule.CORE, NodeModule.TELEPHONY))
```

### 阶段 C：按模块实现节点目录（16 个候选，逐个用测试架验证）
见下表。**实现顺序建议**：低风险优先（零权限的），高风险后做。

**状态：16 个节点全部实现（2026-08-22）**：
- 零权限 6 个：GET_BATTERY / GET_DEVICE_INFO / GET_STORAGE_INFO / PLAY_MEDIA / WAIT_UNTIL_TIME / GET_SENSOR ✅
- 中风险 6 个：GET_CALL_STATE / GET_SIM_INFO / SET_ALARM / DND_MODE / GLOBAL_NAV / WAIT_FOR_ELEMENT ✅
- 高风险 4 个（仅代码+用例，真机验收时启用）：RECORD_AUDIO / CAPTURE_PHOTO / GET_LOCATION / READ_NOTIFICATIONS ✅
- 每个节点：Handler + Habitat factory 注册 + 后端白名单×2 + RAGService（分类+目录）+ 知识库 md + 自测用例；`Habitat :app:assembleDebug` 与后端 `mvn compile` 均通过
- **SmartCS 完全不动**（lib 也未同步，待下次收引擎改动时一并拷入）

### 阶段 D：存储区（Habitat"接收的文件"）
- `/sdcard/Habitat/received/` + 列表 UI（播放/查看/删除）
- 工作流 `ACTION_FILE_OPERATION` copy/write 到该目录即可

---

## 4. 待实现节点规格表（16 个）

> 每个节点实现后：① 写 Handler（遵守线程约束）② 注册到两个 factory ③ 后端白名单+RAG 同步 ④ 知识库文档 ⑤ 用测试架验证 ⑥ git 提交+tag

| # | 节点 | params | 输出 | 权限 | 实现要点 | 状态 |
|---|------|--------|------|------|---------|------|
| 1 | `ACTION_GET_CALL_STATE` | — | `call_state`(int: 0/1/2) | READ_PHONE_STATE | 同步查 `tm.callState` | ✅ 2026-08-22 |
| 2 | `ACTION_GET_SIM_INFO` | `output_var` | SIM 数/运营商/号码 | READ_PHONE_STATE | `TelephonyManager` + `SubscriptionManager` | ✅ 2026-08-22 |
| 3 | `ACTION_GLOBAL_NAV` | `action`(back/home/recents/notifications/power_dialog/lock_screen/take_screenshot) | `nav_success` | 无障碍 | `performGlobalAction`（无障碍服务） | ✅ 2026-08-22 |
| 4 | `ACTION_WAIT_FOR_ELEMENT` | `selector`/`mode`/`timeout`/`wait_for`(appear/disappear) | `element_found` | 无障碍 | 轮询 `findAccessibilityNodeInfos`，事件等待节点 | ✅ 2026-08-22 |
| 5 | `ACTION_GET_BATTERY` | `output_var` | `battery_level`/`battery_charging` | 无 | `BatteryManager`/`ACTION_BATTERY_CHANGED` | ✅ 2026-08-22 |
| 6 | `ACTION_GET_DEVICE_INFO` | — | model/version/screen | 无 | `Build` + `DisplayMetrics` | ✅ 2026-08-22 |
| 7 | `ACTION_SET_ALARM` | `hour`/`minute`/`label` | `alarm_success` | 无 | `Intent.ACTION_SET_ALARM`（打开时钟 UI） | ✅ 2026-08-22 |
| 8 | `ACTION_DND_MODE` | `action`(on/off/get/priority/alarms) | `dnd_state` | ACCESS_NOTIFICATION_POLICY | `NotificationManager.setInterruptionFilter` | ✅ 2026-08-22 |
| 9 | `ACTION_GET_STORAGE_INFO` | — | 总/可用空间 | 无 | `StatFs` | ✅ 2026-08-22 |
| 10 | `ACTION_RECORD_AUDIO` | `duration`/`path`/`output_var` | `record_success`/文件 | RECORD_AUDIO | `MediaRecorder`（非通话录音） | ✅ 代码，待真机 |
| 11 | `ACTION_CAPTURE_PHOTO` | `path`/`output_var` | `photo_success` | CAMERA | Camera2 静默拍摄（无 Activity） | ✅ 代码，待真机 |
| 12 | `ACTION_PLAY_MEDIA` | `path`/`output_var` | `play_success` | 无 | `MediaPlayer`（注意释放） | ✅ 2026-08-22 |
| 13 | `ACTION_WAIT_UNTIL_TIME` | `time`/`mode`/`timeout_ms`/`output_var` | `time_reached` | 无 | 轮询 `System.currentTimeMillis`，事件等待 | ✅ 2026-08-22 |
| 14 | `ACTION_GET_SENSOR` | `type`(light/proximity/accel)/`output_var` | `sensor_value` | 无 | `SensorManager` 读一次（自带 HandlerThread） | ✅ 2026-08-22 |
| 15 | `ACTION_GET_LOCATION` | `output_var` | `latitude`/`longitude` | 定位权限 | `LocationManager` 最后一次已知位置 | ✅ 代码，待真机 |
| 16 | `ACTION_READ_NOTIFICATIONS` | `output_var`/`limit`/`package` | 通知列表 | 通知监听 | `NotificationManager.activeNotifications`（需启用 NLS） | ✅ 代码，待真机 |

**高风险节点**（10/11/15/16）建议在测试专用机/模拟器验证，别在带个人数据的真机上跑。

---

## 5. 参考项目（夜间学习清单）

| 项目 | 学什么 |
|------|--------|
| **Automate（LlamaLab）** | 380+ block 的节点设计、触发器模型、LLM 集成思路 |
| **MacroDroid** | 触发-条件-动作的组织方式、权限处理 |
| **MaaFramework** | 任务图 JSON 结构、可嵌入框架的设计 |
| **Auto.js / AutoX.js** | 无障碍自动化脚本的常用操作模式、国内 ROM 适配经验 |
| **Tasker + AutoInput** | 最成熟的动作集、条件评估、变量系统 |

**任务**：从这些项目里整理一份"值得补进引擎的节点/触发器"清单，标注可行性，回填到 ROADMAP。
**状态**：✅ 已完成（2026-08-22），清单已回填 ROADMAP §5（下一批候选：SET_WALLPAPER / GET_SCREEN_STATE / GET_TRAFFIC / AIRPLANE_MODE / MOBILE_DATA + battery/call_end 触发器）。

---

## 6. 夜间工作安排（无手机时）

1. **上网研究**：参考项目节点目录、安卓 SDK 接口（见上表）→ ✅ 已产出 ROADMAP §5 清单
2. **写引擎节点**：先实现**零权限**的（5/6/9/12/13/14），用测试架思路自测（在 SmartCS 调试页跑）→ ✅ 16 个全部实现
3. **搭测试架**：先做出"测试工作流 + 结果收集"的骨架（不需要真机也能写代码）→ ✅ 已完成（41 条用例）
4. **整理节点规格**：把 16 个候选的详细 params/输出/实现要点写成文档 → ✅ 规格表已更新 + 知识库同步
5. **版本管理**：每个完成节点 git 提交 + tag

**有手机后**：adb 驱动测试架 → 逐个验证节点 → 修问题 → 高风险节点在专用机/模拟器上测。

---

## 7. 版本管理约定

- SmartCS：`v0.x.y`，每次功能完成 `git commit + tag`
- Habitat：版本在 `app/build.gradle.kts`（versionName），CHANGELOG 记变更
- 每次引擎改动：两份 `:lib` 必须同步（`diff -rq` 确认 0 差异）

---

## 8. 交接检查清单（新窗口第一步）

- [ ] 读本文档 + `docs/ENGINE_EXTENDING.md` + `docs/ROADMAP.md`
- [ ] `diff -rq` 两份 `:lib` 确认同步
- [ ] 跑 SmartCS `:app:assembleDebug` 确认可编译
- [ ] 看 `git log --oneline` 确认版本状态
- [ ] 从"阶段 A：自跑测试架"开始
