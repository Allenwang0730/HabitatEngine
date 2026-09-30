# Changelog

## [1.8.0] - 2026-09-30

App `versionName` 1.8.0（`versionCode` 4）；引擎 AAR `com.ailun:habitat-engine:1.7.0`。

### ⚠️ 行为变更（升级前请看）
- **Agent 服务**：默认只监听 `127.0.0.1`，电脑侧先 `adb forward tcp:8931 tcp:8931`；局域网直连需在 Agent 页打开「局域网访问」。旧令牌 `habitat-dev-token` 作废，改为每台设备随机生成（Agent 页查看/复制/重新生成），支持 `Authorization: Bearer`。远程调用高危节点（Shell、文件、拨号、短信/通话记录、加解密、压缩、SQLite、强停、广播、壁纸、录音、拍照、写语音文件）默认拒绝。
- **变量插值只替换一轮**：变量值里的 `${...}` 不再展开（防外部文本二次展开读出其他变量）。
- **`ACTION_SHELL`**：`command` 里每个 `${var}` 自动单引号转义为一个参数，不要再自己加引号；`raw_interpolation: true` 恢复旧行为。
- **Shell 回退**：选了 Shizuku 模式时 Shizuku 不可用直接失败，不再静默改走 `su`。
- **文件类节点**：加路径守卫（拒绝系统目录、他人 `Android/data`）；删除目录需 `recursive: true`，存储根和一级公共目录不可删。
- **`ACTION_WAIT_UNTIL_TIME`**：等待超过 29 分钟需设 `node_timeout_ms: 0`（`0` = 不设节点超时，新语义）。
- **`ACTION_SQLITE_QUERY`**：数据库不存在时默认不再自动创建（`create: true` 开启）。
- **release 包**：非 https 且非本机地址的服务器不再发送登录凭据。
- 关闭应用备份（`allowBackup=false`）：加密存储的密钥不随备份迁移，恢复到新设备会崩溃。

### Security
- 自测 Activity 加 `DUMP` 权限，只有 adb 能拉起（此前任何 App 可借它启动 Agent）。
- 强停、亮屏解锁等节点的命令注入修复（`ShellQuote`）；短信/通知内容作为变量拼进 shell 不再可注入。
- Zip Slip 修复；解压总量上限；XML 解析禁用 DOCTYPE/外部实体。
- 登录接口不再把密码和 JWT 打进 Logcat；用户 CA 只在 debug 信任。
- API key / Agent 令牌改存 `SecretStore`（EncryptedSharedPreferences），自动迁移旧明文。
- `agent_daemon.mjs` 本地 HTTP 口加 `X-Habitat-Key` 鉴权、拒绝浏览器跨站请求；脚本不再硬编码令牌。

### Fixed — 自主模式 / Jev 链路
- 读屏按窗口优先级取控件（活动窗口 > 应用 > 输入法 > 状态栏）：此前按 y 截前 40 个会被状态栏图标占满，前台包被认成 systemui（真机实测）。
- 远程发起的目标不能解锁敏感按钮；敏感词扩到支付/删除/卸载/授权等；支付密码/刷脸页只允许返回；系统权限弹窗需本机目标写明"授权"。
- 密码框文字不发给决策器。低置信（< 0.3）不执行；连续等待 3 次停止；判重改用"操作+文字+前台包"；每步回报屏幕是否变化。
- 点击前复查与 OCR 限频冲突导致点击必被拦；Jev 请求可取消（停止立即生效）、429/5xx 重试一次；响应字段类型错误不再抛异常；启动/停止竞态。
- OCR 截图/位图泄漏；OCR 坐标换算改用整屏尺寸。

### Fixed — 引擎
- 连续/嵌套 `ACTION_FORK` 互相覆盖；分支结束隐式等待未 join 的子分支。
- 节点抛 `Error`（OOM、LinkageError）不再让 App 崩溃。
- 执行服务竞态：同一工作流可能并发两份、`stop` 停不掉、`onComplete` 回调两次。
- 工作流库解析失败不再用默认列表覆盖用户数据（先备份，再逐项宽松解析）。
- 大整数解析丢精度。
- 新增 `HabitatJson.lint(graph, knownTypes)`：悬空连线、未注册类型、不可达节点。

### Fixed — 节点
- WiFi/蓝牙开关在新系统上失效（改走 `svc`）；`quick_settings`/`power_dialog` 按键映射错误（后者会直接锁屏）；媒体 play/pause 反转；息屏误亮屏。
- 解密密码错误会清空原文件；长按祖先节点死循环；读通知只能读到本 App 的（改走通知监听服务）。
- 多处向 ConcurrentHashMap 写 null 导致 NPE；SQLite `limit` 不生效；重试节点多节点链路误判；Click `occurrence` 越界静默点第一个。
- `ACTION_SET_VARIABLE` 字符串值现在会插值；数字参数接受字符串/变量（`ParamUtils`）。
- 阻塞 IO 移到 `Dispatchers.IO`；Shell 读 stdout/stderr 死锁与无超时；拍照尺寸不合法与相机泄漏；TTS 重复 resume；HTTP 响应上限、PATCH 通过 `X-HTTP-Method-Override`。

### Fixed — 触发器 / 生命周期
- 旋转屏幕、切深色模式会停掉所有工作流并清空触发器（初始化移到 `HabitatApp`）；进程重启后恢复已启用触发器。
- 排队补跑、`repeat` 并发无上限且禁用停不掉、编辑后仍执行旧 JSON、通知触发器自触发死循环。
- 定时触发器改为每工作流独立调度（精确到 `interval_ms`，默认不立即触发）。
- 部分 ROM 一次复制回调两次导致剪贴板触发器跑两遍（真机实测 vivo）。
- 按键触发器从未生效（缺 `canRequestFilterKeyEvents`）；去掉会改变整机触控的触摸浏览 flag。
- Agent 服务改为前台服务；悬浮窗前台服务启动失败不再崩溃；本地 LLM 线程安全与原生内存泄漏。

### Changed — 界面
- 新设计系统：配色（完整 M3 色阶、深色模式）、字体层级、形状/间距/动效令牌、语义色与节点类别色；通用组件库。
- 工作流列表：搜索、按触发类型筛选、实时状态、滑动删除可撤销。
- 编辑器：JSON/结构图切换、行号、格式化、实时 lint、未保存提示；单行 JSON 打开时自动展开。
- 结构图：修复带循环时节点全叠在 (0,0)、连线方向错误、顶部被裁；节点详情面板；适配缩放不低于 0.75，保证文字可读。
- Agent 页：令牌管理、局域网/高危开关、自主模式时间线、首次使用云端决策器的隐私提示。
- 设置/引导：Android 13+ 通知权限申请；授权返回后自动刷新；Root 检测移出主线程（此前可能 ANR）。
- 删除无效的「API 密钥管理」（只存打码前缀、无人读取）。

### Tests
- 新增 JVM 单测：引擎 32 条（解析、lint、插值、执行器、仓库解析），Agent 策略 14 条。
- 真机（vivo V2329A / Android 16）节点自测 68 条：53 通过、0 失败、15 跳过（需人工/特定权限）。

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
