# Changelog

## [1.7.0] - 2026-09-22

### Added — 自主模式（Jev / DeepSeek / 本地）+ 微信读屏
- **Agent loop**：读屏 → 决策 → Habitat 节点执行 → 再读屏。决策器只从当前屏幕候选里选题，不发明坐标或包名。同一动作连续三次、或点击目标已离开原位置，则停止。
- 决策器可切换：
  - **Jev**（TypeSafe `https://api.typesafe.ai/v1/systemone`，模型默认 `jev-latest`，key 在 Agent 页填写，不进代码）
  - **DeepSeek**（复用 AI 生成页的 key）
  - **本地**（不联网：点目标原文里出现的控件，否则打开点名应用，再否则向下滚两次）
- 支付类按钮（转账/红包/付款/支付）默认不进入候选，除非目标原文写了同一个词。
- MCP/WS 增加 `agent_start` / `agent_stop` / `agent_status`（85 个工具）。
- **微信读屏**：新增无障碍服务，类名 `com.google.android.accessibility.selecttospeak.SelectToSpeakService`。树为空时用 ML Kit 捆绑中文模型离线 OCR（限频、失败退避）。升级后需把无障碍关掉再打开，截屏能力才生效。
- App `versionName` 对齐到 `1.7.0`（`versionCode` 3）。引擎 AAR 仍为 `1.6.1`（本版无引擎 API 变更）。

## [1.6.1] - 2026-09-22

### Added
- `ACTION_CLICK` 增加 `offset_x` / `offset_y`：相对目标中心偏移后再点击（屏幕 Y 向下为正，负值向上）。坐标形式的 `target`（`x,y`）不加偏移。
- `HABITAT_API.md` 与中英文 README 同步点击参数（含已有的 `region` / `occurrence`）。

## [1.6.0] - 2026-08-22

### Added — 引擎修复 + AI 工作流生成 + MCP Agent + 第二批节点
- **引擎一致性/并发修复（本次重点）**：
  - `on_error=stop` **树级终止**（新增 `WorkflowContext.stopRequested`，所有分支循环检查后真正停整棵树；原只停当前分支）
  - `HabitatExecutionService.stop` **级联取消**（修复 stop 无效：executor 内部独立 scope 不随外层取消 → `execJob.join()` finally 级联 `execJob.cancel()`）
  - FORK 分支改 `CoroutineScope(coroutineContext).launch`（挂到执行 job 下，随取消级联）+ 取消正确传播（不再用 `runCatching` 吞 CE）
  - `activeJobs` `ConcurrentHashMap` + `start` `@Synchronized`（防双执行）；`TriggerManager` entries/sourceInstances/pendingRuns 并发安全；`HabitatStateStore.setRunning` 用 `update{}` 原子（防丢更新）+ `notifyLibraryChanged` 用 AtomicLong 递增（防同毫秒漏刷新）
  - **触发器源审查修复**：Timer 双 start 泄漏/NPE、Key 触发器吞全部按键（改监听不拦截 + 仅 DOWN）、timer `interval_ms` 门控（修复 12 倍频率失控）、Notification/Sms/Clipboard 重复注册泄漏
  - **开关修复**：Agent 服务开关乐观更新 + 失败反馈；悬浮窗 `isRunning` 真实生命周期（不再乐观虚假）+ 挂载幂等
  - **FORK 树级终止回归用例**（自测 61 → 66 条）
- **AI 工作流生成**（App，DeepSeek）：`WorkflowAIClient`（OpenAI 兼容 chat completions，key/baseUrl 可配置）+ `WorkflowAIGenerator`（节点白名单+few-shot+格式约束的 prompt；JSON 提取 + `HabitatJson.fromJson` 验证）+ 聊天 UI（自然语言→生成→编辑→多轮修正→保存→挂载悬浮窗）。**DeepSeek 10 典型场景正确率 100%**
- **MCP Agent 服务**：`/mcp` JSON-RPC 2.0（initialize/tools/list/tools/call，82 工具 = 80 节点 + screen/ping）、端口可配置、Agent 控制页（开关/状态/IP/端口）、Dashboard Agent tab；电脑侧常驻 `agent_daemon.mjs`（一次握手长连接，**读屏 3.9s→277ms、节点调用 2s→54ms**）；`scripts/mcp_call.mjs`
- **新节点 4 个**：`ACTION_GEOCODE`（Nominatim 地理编码，免 key）、`ACTION_SAY_TO_FILE`（TTS 合成到 .wav，离线）、`ACTION_GET_TRAFFIC`（累计流量）、`ACTION_GET_SETTING`（读系统设置项）
- 引擎 **84 节点**；自测用例 **66 条**；后端白名单×2 + RAGService + 知识库 md 同步
- `scripts/verify_workflow_gen.ps1`（DeepSeek 生成正确率验证）

### Notes
- SmartCS 完全不动；两份 `:lib` 暂不同步。真机回归（66 条）待设备授权。

## [1.5.0] - 2026-08-22

### Added — 模块化（阶段 B）+ 异常处理/通用节点
- **NodeModule 模块化**：枚举 + 编译期分组注册表（`CoreNodes`/`InteractionNodes`/`SystemNodes`/`TelephonyNodes`/`MediaNodes`/`NetworkNodes`/`SensorNodes`/`AiNodes`）；`NodeHandlerFactory(modules: Set<NodeModule>? = null, extra: Map<String, NodeFactory>)`——null=全量（向后兼容）、extra 单节点/自定义注入、懒实例化、`availableTypes()`；FORK/JOIN 常量同步
- **异常处理**：`ACTION_RETRY`（target/times/interval_ms 失败重试）、`ACTION_ASSERT`（断言失败走错误流）
- **通用查询**：`ACTION_GET_SCREEN_STATE`（屏幕开关/锁定）、`ACTION_GET_AUDIO_STATE`（音乐/耳机）
- **系统/媒体**：`ACTION_SEND_BROADCAST`（自定义 action）、`ACTION_MEDIA_CONTROL`（shell keyevent）、`ACTION_GET_WEATHER`（Open-Meteo）、`ACTION_SET_WALLPAPER`
- executor：成功路径记录 `last_node`/`last_node_type`（RETRY 回跳判定依赖）
- 引擎 80 节点；自测用例 51 → 61；**真机验收：61 条 = 52 PASS / 0 FAIL / 9 SKIP**（含模块化冒烟：CORE 32 / 全量 78）
- 后端白名单×2 + RAGService + 知识库 md 同步

### Notes
- SmartCS 完全不动；两份 `:lib` 暂不同步。

## [1.4.1] - 2026-08-22

### Added — 架构加固（失败处理 / 超时 / 分叉 / Collision）
- **executor 失败处理**：节点级超时（`node_timeout_ms`，默认 30min）+ `require` 声明式成功条件 + `on_error`（`skip` 默认/`stop`）+ 错误变量（`last_error`/`last_error_msg`/`last_node`/`last_node_type`，成功清除；`run_errors`/`last_error_any` 累计不清，供事后检查）
- **ACTION_FORK / ACTION_JOIN**：并行分叉（executor 内建，共享线程安全 variables，分支各自步数与超时护栏）
- **ACTION_TRY_CATCH error 分支修复**：改读 executor 维护的 last_error（原 _last_error 机制不可达，已废弃）
- **TriggerManager on_busy 碰撞策略**：`ignore`（默认）/ `abort_existing` / `queue`（TriggerConfig.onBusy）
- 修复 ConcurrentHashMap null value NPE（putVariable null=remove 语义 + executor/Array 相关处改 remove）
- 自测用例 46 → 51；真机验收：**51 条 = 44 PASS / 0 FAIL / 7 SKIP**
- 后端白名单 + RAG + 知识库同步（FORK/JOIN + 健壮性参数说明）

## [1.4.0] - 2026-08-22

### Added — 第一批纯逻辑数据节点（零权限，调研结论落地）
- `ACTION_SQLITE_QUERY` — 任意 .db 文件 SQL（SELECT 转 JSON 行集 / 非 SELECT 走 changes()），支持 ? 占位符 args
- `ACTION_ZIP` — zip/unzip/list，目录递归 + zip-slip 防护，可选 delete_source
- `ACTION_CRYPTO` — 文件 AES-GCM 加解密（PBKDF2 口令派生，salt+IV+密文格式）
- `ACTION_ARRAY` — 数组 set/push/pop/get/length/join/clear/remove/contains（支持 JSON 数组或分隔串）
- `ACTION_PARSE_DATETIME` — now/parse/format，java.time，支持 add_days/hours/minutes/seconds 偏移
- 同步三处：后端白名单×2 + RAGService + 知识库 md；自测用例 41 → 46 条
- 来源：docs/RESEARCH_REFERENCE_PROJECTS.md（参考项目调研，4 项目官方文档交叉对照）

### Notes
- SmartCS 完全不动；两份 `:lib` 暂不同步。

## [1.3.0] - 2026-08-22

### Added — 阶段 C：16 个候选节点全部实现
- **零权限 6 个**：
  - `ACTION_GET_BATTERY` — battery_level(0-100)/battery_charging/battery_success
  - `ACTION_GET_DEVICE_INFO` — model/manufacturer/android version/screen size/density
  - `ACTION_GET_STORAGE_INFO` — total/available/used bytes + percent
  - `ACTION_PLAY_MEDIA` — MediaPlayer 同步 prepare，挂起至播放完/超时，finally release
  - `ACTION_WAIT_UNTIL_TIME` — clock("HH:mm[:ss]")/timestamp 两种模式，秒级轮询
  - `ACTION_GET_SENSOR` — light/proximity/accel，自带 HandlerThread 承载回调
- **中风险 6 个**：
  - `ACTION_GET_CALL_STATE` / `ACTION_GET_SIM_INFO` — READ_PHONE_STATE，同步查询
  - `ACTION_SET_ALARM` — ACTION_SET_ALARM + NEW_TASK（打开时钟 UI）
  - `ACTION_DND_MODE` — 通知使用权，on/off/priority/alarms/get
  - `ACTION_GLOBAL_NAV` — performGlobalAction（含 power_dialog/lock_screen/take_screenshot，API 30+）
  - `ACTION_WAIT_FOR_ELEMENT` — text/id/class 轮询，appear/disappear
- **高风险 4 个（仅代码+用例，真机验收时启用）**：
  - `ACTION_RECORD_AUDIO` — MediaRecorder 定时录音
  - `ACTION_CAPTURE_PHOTO` — Camera2 静默拍摄（无 Activity/预览），自带 HandlerThread
  - `ACTION_GET_LOCATION` — 最后一次已知位置（GPS→NETWORK）
  - `ACTION_READ_NOTIFICATIONS` — activeNotifications（需启用通知使用权）
- **同步三处**：后端白名单（WorkflowGenerationService / WorkflowJsonValidator）+ RAGService（分类列表 + 参数目录）+ 知识库 `habitat_api_knowledge.md`（16 个新节点章节）
- **自测用例 24 → 41 条**（框架新增 `skipReason`：需真实文件/权限/有界面副作用的用例自动 SKIP）
- **参考项目调研**：Automate/MacroDroid/MaaFramework/Auto.js/Tasker 节点触发器清单回填 ROADMAP §5

### Notes
- **SmartCS 完全不动**：新节点只在 Habitat 的 `:lib`，两份 `:lib` 暂不同步（待 SmartCS 下次收引擎改动时一并拷入）。
- Habitat app Manifest 新增权限：READ_PHONE_STATE / ACCESS_NOTIFICATION_POLICY / RECORD_AUDIO / CAMERA / ACCESS_FINE_LOCATION / ACCESS_COARSE_LOCATION。

## [1.2.0] - 2026-08-22

### Added
- **阶段 A · 自跑测试架（骨架）**：
  - `lib/.../selftest/NodeSelfTest.kt` — 测试框架：`SelfTestCase` / `SelfTestResult` / `NodeSelfTestRunner`（顺序执行、逐条超时、异常/解析失败隔离、无障碍未连接自动 SKIP）。
  - `lib/.../selftest/NodeSelfTests.kt` — 首批 24 条用例，覆盖逻辑控制/变量数据/工具时间/网络/UI/系统/感知（无障碍）。
  - `app/.../NodeSelfTestActivity.kt` — adb 驱动的测试宿主（`am start -n com.ailun.habitat/.NodeSelfTestActivity --ez auto true`），结果写 `filesDir/test_results.json` + Logcat `HABITAT_TEST_DONE` 标记。
  - `scripts/run_selftest.ps1` — 一条命令：检查设备 → 安装 → 跑全部用例 → 拉取报告（本机 `selftest_results/latest.json` + 设备 `/sdcard/Habitat/test_results.json`）。
  - `docs/SELFTEST.md` — 使用与扩展指南。
- **测试架暴露的问题（待修）**：
  1. `ACTION_LOG` 不走 `context.log()` 回调，只写 Logcat → 工作流日志通道收不到 LOG 节点输出。
  2. `ACTION_TRY_CATCH` 的 error 分支不可达：`_last_error` 只有 handler 自己读写，executor 捕获异常时直接终止流程、不写标记。

### Fixed
- `HabitatDashboard` / `FloatWindowManager` / `HabitatFloatService` 三处 `HabitatExecutionService.start(...)` 尾随 lambda 在 `onResult` 加入后被 Kotlin 绑定到 `onResult`（日志回调失效且编译失败）→ 改为命名参数 `onLog = {...}`。

### Notes
- 测试宿主只放 Habitat `:app`；**SmartCS 仓库完全不动**，两份 `:lib` 在测试架期间暂不同步（selftest 包只在 Habitat lib，SmartCS 下次收引擎改动时一并拷入）。

## [1.1.1] - 2026-08-22

### Added
- `HabitatExecutionService.start()` 新增 `onResult` 回调：工作流结束后的全部变量快照返还给调用方（在 `onComplete` 之前）。这是引擎向 App 返还内容的正式通道（此前只能靠文件副作用）。
- 同步 `NodeLaunchAppHandler`：启动应用时加 `REORDER_TO_FRONT | SINGLE_TOP`，把已有任务拉回前台。

## [1.1.0] - 2026-08-22

### Added
- **New nodes**:
  - `ACTION_CALL_DIRECT` — place call directly via `ACTION_CALL` (no accessibility needed), dual-SIM via `sim` param.
  - `ACTION_WAIT_CALL_END` — block until the call ends (polls `TelephonyManager.callState`).
- **Docs**: `docs/ENGINE_EXTENDING.md` — handler thread constraint (no-Looper `Dispatchers.Default`) + node extension guide.
- **README**: repositioned as an embeddable, LLM-driven automation runtime; added "Why not Tasker/MaaFramework/Auto.js" comparison.

### Fixed
- `ACTION_WAIT_CALL_END` thread safety: initial `PhoneStateListener` crashed on no-Looper background thread (`Looper.mQueue null`); switched to synchronous `callState` polling.

### Notes
- Handlers run on `Dispatchers.Default` (no Android Looper). Use synchronous queries or a self-owned `Executor`/`HandlerThread`; avoid Looper-dependent callbacks (`PhoneStateListener`).
