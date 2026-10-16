# Changelog

## [1.9.0] - 2026-10-14

App `versionName` 1.9.0（`versionCode` 6）；引擎 AAR `com.ailun:habitat-engine:1.8.0`（`ACTION_LAUNCH_APP` 新增 `intent_action`；`ACTION_INPUT_TEXT` 新增 `target`，写入后读回核对，失败退回剪贴板粘贴）。JitPack：`com.github.Allenwang0730:HabitatEngine:v1.9.0`。

**真机评测**（vivo / Android 16，adb/读屏独立验证，方法与原始数据见 [docs/EVAL.md](docs/EVAL.md)）：2026-10-09，7 类任务 × 4 次，有规划时 Jev、DeepSeek 都是 28/28，无规划 13/28、18/28；2026-10-14，10 类任务（新增发消息、打电话），DeepSeek 有规划 10/10、无规划 7/10。

### Added — 意图槽位与固定做法（「只点输入框不输入」「打电话变打开应用」）
- **`IntentParser`**：规则解析「打电话给 X」「给 X 发消息说 Y」「输入/搜索 X」，每个槽位都是目标原文子串，不做自由改写；「输入电池」不再要求空格。
- **拨号工具 `call`**：号码只在本机查（目标里的号码 → 通讯录 → 内置客服号码表），不进任何提示词；本机发起且有拨号权限才直接拨出，远程发起和紧急号码只填拨号盘。联系人与应用同名（「中国移动」）时不再作为打开候选。新增 `READ_CONTACTS` 权限，Agent 页开始前按需申请。
- **`SkillPlans`**：打电话、QQ/微信发消息直接出固定步骤；完成条件由代码看屏幕判定（`StepCheck`：已进入会话 / 输入框正好是 Y / 已发送）；会话行由代码按名字点；整件事有终点判定，重规划也遵守。
- **输入链路**：只有一个输入框时不要求它报告焦点；`TYPE_TEXT` 按坐标写入指定输入框；输好后「发送/搜索/确定」排到候选最前；本地决策器会输入和提交。
- 评测新增 `settings_search_nospace`、`qq_my_computer`、`call_10086`；`scripts/agent_eval_export.mjs` 导出可公开的结果（不含屏幕文字）到 `docs/eval/`。

### Added — 自主模式重构：规划（DeepSeek）+ 执行（Jev）+ 代码控制
- **规划器**（`Planner` / `DeepSeekPlanner`）：先把目标拆成 1~6 步，每步带屏幕上可验证的完成条件，能用设备工具就直接用；执行卡住时从当前屏幕重规划剩下的部分（最多 2 次）；全部完成后按屏幕证据**复核**，提问类目标在复核里读出答案。Agent 页新增「DeepSeek 规划与复核」开关（默认开，需 DeepSeek key）；MCP `agent_start` 新增 `planner` 参数。
- **设备工具**（`DeviceTools`）：`open_settings`（15 个设置页直接跳转）、`open_app`、`set_wifi/bluetooth/flashlight/dnd`、`set_brightness`、`set_volume`、`read_battery/time/device_info/network`。执行器每屏也能看到相关的 `USE_TOOL` 快捷方式。会改设备状态的工具必须由用户目标原文点名。
- **导航栈 + 页面图**（`NavigationState`）：当前位置（`设置 > 蓝牙`，第几层、返回到哪）、本页试过的入口与结果、是否已滚到底，带给执行器和重规划。
- **控件状态**：开关 `[已开启]/[已关闭]`（含"包着一个开关的列表行"）、滑块百分比、灰掉的控件进入观测和候选；新增 `SET_SLIDER`（拖到最大/最小/中间）。
- **步骤完成判定**：Jev 在同一次请求里额外回答 `step_status`（当前步骤完成条件是否成立），≥0.7 由代码推进到下一步，不加延迟。
- `ACTION_LAUNCH_APP` 新增 `intent_action`（只接受 `android.settings.*` 与电池页），用于直接跳转设置页。
- 评测脚本 `scripts/agent_eval.mjs` + `agent_eval_tasks.json`：固定任务集，adb/读屏独立验证，跑完恢复设备状态，支持按执行器 × 有/无规划对比。

### Fixed（真机评测暴露）
- QQ 聊天页：整条输入栏容器吞掉了真正的发送按钮（借了「发送」二字，中心落在输入框上）；顶栏聚合行里读不出标题，页面标题落到正文里的「下载」。
- vivo 设置首页的搜索框只是入口：第一次允许点击，这一步点过之后才只给输入。
- 控件一览标出不在候选里的控件（模型曾连续 6 次选无效序号）。
- 拖滑块、拨开关时文字不变，旧"屏幕是否变化"判定把成功当成无变化后反复操作；现在控件状态也算。
- 工具失败后把「亮度设为最高」偷换成「设为中等」；现在只能换别的工具，不换同一工具的另一档。失败过的工具本次任务不再提供。
- 读数工具不改屏幕被判"无变化"而反复调用；读到值算进展，已知事实对应的工具不再提供。
- 搜索框残留文字被当成同名入口反复点：输入框在候选里写成「输入框，当前内容：…」，已聚焦的输入框不作点击候选。
- 灵动岛/状态栏控件被当成可点目标；`FrameLayout` 等 View 类名被当成 Activity。
- 决策器不可用（key 无效、HTTP 451）时不再无意义地重规划，直接结束并提示。

### Changed
- 默认 `standard` 包不含微信读屏服务（它冒用 Google `SelectToSpeakService` 类名），移到 `wechat` 渠道（`assembleWechatDebug`），只适合自用。
- 新增 GitHub Actions（单元测试 + 构建 APK/AAR）与 `jitpack.yml`。
- 默认最多步数 12 → 20，上限 30 → 40；多步计划里每步最多 8 步。
- 页面标题识别跳过状态栏时钟。
- Jev 可配 HTTP 代理（Agent 页「Jev 代理」，只存本机）：国内网络直连返回 451 时，经 `adb reverse` 走电脑代理。
- 文档与脚本去掉个人信息：设备序列号、局域网 IP、本机路径（`run_selftest.ps1` 改用 `%LOCALAPPDATA%` 与脚本相对路径）。

### Security
- 「发送」与聊天页回车列为敏感动作：目标写了「发消息/发给/回复」等才放行；远程发起只填好消息，不发送。
- 规划器输出一律当不可信：工具/参数白名单与枚举、输入文字必须是目标原文子串、敏感按钮与权限弹窗的放行只看用户目标原文（规划器写进步骤说明的文字不能解锁）。
- 规划器开启时屏幕文字也会发给 DeepSeek：即使执行器选 Jev/本地，首次也会弹出 DeepSeek 的隐私提示。

## [1.8.1] - 2026-10-09

App `versionName` 1.8.1（`versionCode` 5）；引擎 AAR `com.ailun:habitat-engine:1.7.1`。

### Fixed — 自主模式「找不到控件 / 干不了活」（真机 vivo V2329A / Android 16 定位）
- **列表行进不了候选**：设置页 19 个可点节点里 17 个是没有自带文字的行容器，文字在子 TextView 上，旧读屏只收"有文字"的节点。现在可点容器聚合后代文字成一个元素，`clickable` 改为真实语义（自身/祖先/后代可点），不再把"有文字"当可点。设置首页 TAP 候选 2 → 11。
- **状态栏挤占名额**：通知图标一条一个节点，页面节点少时占满剩余名额（实测 53 个元素里 41 个是"xx通知："）。非活动系统窗口只留 6 个可点元素。
- **桌面图标被吞**：整屏可点容器（桌面图标网格）把 59 个图标聚合成 1 个元素；面积超过屏幕 35% 的容器不再参与聚合/去重。
- **`OPEN_APP` 找不到应用**：`queryIntentActivities` 用了 `MATCH_DEFAULT_ONLY`，大多数桌面入口不声明 `CATEGORY_DEFAULT` 被滤掉（「打开时钟」永远没有候选）。
- **提示里的编号与候选对不上**：控件一览按元素序号，TAP 候选按排序后重新编号，模型照一览选号会点错或报"候选里没有"。统一用元素序号；选到候选外的目标改为重选（最多 2 次），不整轮报废。
- **转场中误 OCR**：页面切换时树文字少，旧逻辑立刻 OCR，坐标是猜的，点进了错误的 App。现在先等 600ms 再读一次树，树里有可点控件就不 OCR。
- **回车无效**：`PRESS_ENTER` 原来走 `input keyevent`，没开 Shizuku 时必失败；改走无障碍 `ACTION_IME_ENTER`（`ACTION_INPUT_TEXT` 新增 `submit`、`allow_empty` 参数）。`CLEAR_TEXT` 改为真正清空。
- **锁屏时盲目决策**：锁屏读到的只有验证指纹页；现在直接报"屏幕已锁定，请先解锁"。

### Changed — 循环策略
- 低置信不再丢步：两个云端模型实测都给 0.2~0.3，旧阈值 0.3 等于全丢。现在 < 0.15 才跳过，0.15~0.3 照做并记日志。
- 动作失败换同操作的次优候选重试一次；失败原因写进 history 供模型换招。
- 重复/兜圈不再直接停：先替模型换一次招（重复的是滚动就先返回），换两次还在重复才停。新增窗口内循环检测（最近 6 步同一动作 3 次）；滚动且屏幕在变的不算兜圈。
- 死路名单：原地动作（提交/清空/输入/长按）做了没变化、点进外部 App 又被拉回、进子菜单又退出来的入口，本次任务内不再提供。
- 提示词补"操作手机的先验"（列表行可点、找不到先翻到底、进过的子菜单不再进、页面标题已是目标就 DONE 等），并给决策器 `pageTitle`、`activity`、控件类型与 `viewId`。
- 新增动作：`LONG_PRESS`、`PRESS_ENTER`、`CLEAR_TEXT`。
- `ACTION_CLICK` 支持 `id:<resource-id>` 选择器；文本精确匹配不到时退回递归子串匹配（行容器的聚合文字）。

### Added
- Agent 调试工具 `debug_screen`（WS/MCP）：每个窗口的类型/活动状态/包名/节点数 + 元素列表 + 当前候选。
- `scripts/mcp_call.mjs --full`：不截断长字段（看完整步骤日志）。

### Known issues
- **Jev 在国内网络返回 HTTP 451**（服务端按地区拒绝，与 key 无关），现在会给出明确提示；需换网络或改用 DeepSeek。
- 真机 DeepSeek 结果（最终版之前一轮）：蓝牙 3/3、设置内搜索 2/2、时钟秒表 3/3、「关于手机」0/3。针对「关于手机」的修复（编号对齐、子菜单死路、先翻到底）单测已覆盖，**未能在真机复测**（USB 连接反复断开）。

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
