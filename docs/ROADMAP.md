# Habitat 引擎路线图

本文档沉淀引擎的演进方向。当前引擎 **51 个节点**、可嵌入、LLM 可生成工作流、`onResult` 返还内容。

## 1. 引擎模块化（NodeModule，按需引入）

**目标**：像 `from x import y` 一样，App 只引入自己需要的节点模块，从而可以放心铺全量节点目录。

```kotlin
enum class NodeModule { CORE, TELEPHONY, SYSTEM, MEDIA, NETWORK, SENSOR, DATA, LOGIC, UI, AI }

val factory = NodeHandlerFactory(
    a11y = provider,
    shell = shell,
    modules = setOf(NodeModule.CORE, NodeModule.TELEPHONY),  // App 按需引入
)
```

- 引擎提供**完整目录**（全部候选节点都实现，按模块组织）
- App 只注册它要的模块 → 无 bloat
- 后端 `VALID_NODE_TYPES` 随模块配置（App 引入 TELEPHONY → LLM 只生成通话节点）

**当前状态**：未实现。工厂 init 一次注册全部内置节点。

## 2. 自跑测试架（adb 驱动，免人工）

**目标**：我用 adb 直接驱动真机跑每个节点的测试工作流，读日志/结果文件自动验证，不用你一个个点。

- 一组**测试工作流**（每个节点一条），跑完写 pass/fail 到结果
- 覆盖所有**不需要物理交互**的节点：电池、设备信息、文件、时间、随机数、数学、剪贴板、文本、JSON/XML 解析、延时、日志、Toast、HTTP、读屏、剪贴板
- **需要真机交互的**（真实来电、真实通话录音）单独列，手工测

**当前状态**：骨架已实现（2026-08-22）—— `:lib` selftest 框架 + 41 条用例 + Habitat `:app` 宿主 `NodeSelfTestActivity` + `scripts/run_selftest.ps1`（详见 `docs/SELFTEST.md`）。**真机验收待做**（设备 adb 授权恢复后）。测试架已暴露 2 个引擎问题：`ACTION_LOG` 不走 `onLog` 回调、`ACTION_TRY_CATCH` error 分支不可达。宿主只放 Habitat app，SmartCS 不动，两份 `:lib` 测试架期间暂不同步。

## 3. 待实现节点目录（SDK 调研结论）

按模块，正常 App 可行 + 有真实用途：

| 模块 | 节点 | 权限 |
|------|------|------|
| 通话 | `ACTION_GET_CALL_STATE` | READ_PHONE_STATE |
| 通话 | `ACTION_GET_SIM_INFO` | READ_PHONE_STATE |
| 导航 | `ACTION_GLOBAL_NAV`（back/home/recents）| 无障碍 |
| 导航 | `ACTION_WAIT_FOR_ELEMENT` | 无障碍 |
| 系统 | `ACTION_GET_BATTERY` | 无 |
| 系统 | `ACTION_GET_DEVICE_INFO` | 无 |
| 系统 | `ACTION_SET_ALARM` | 无 |
| 系统 | `ACTION_DND_MODE` | ACCESS_NOTIFICATION_POLICY |
| 系统 | `ACTION_GET_STORAGE_INFO` | 无 |
| 媒体 | `ACTION_RECORD_AUDIO` | RECORD_AUDIO |
| 媒体 | `ACTION_CAPTURE_PHOTO` | CAMERA |
| 媒体 | `ACTION_PLAY_MEDIA` | 无 |
| 时间/传感 | `ACTION_WAIT_UNTIL_TIME` | 无 |
| 时间/传感 | `ACTION_GET_SENSOR` | 无 |
| 位置 | `ACTION_GET_LOCATION` | 定位 |
| 通知 | `ACTION_READ_NOTIFICATIONS` | 通知监听 |

**当前状态**：已实现的通话/来电相关节点（CALL_DIRECT、WAIT_CALL_END、WAIT_CALL_ANSWER、READ_CALL_LOG）不在上表。**阶段 C 16 个候选节点已全部实现（2026-08-22）**，见 `ENGINE_MASTER_PLAN.md` §4 状态；其中 6 个零权限 + 6 个中风险 + 4 个高风险（仅代码，待真机验收）。**第一批纯逻辑数据节点 5 个已实现（SQLITE_QUERY / ZIP / CRYPTO / ARRAY / PARSE_DATETIME，零权限，2026-08-22）**。

## 4. 存储区（Habitat 的"接收的文件"）

**目标**：工作流抓取的东西（录音/截图/文本）统一落 `/sdcard/Habitat/received/`，配列表 UI（可播放/查看/删除）。

- 工作流用 `ACTION_FILE_OPERATION`（copy/write 到该目录）即可
- 音频做媒体扫描
- 这是引擎"抓取任意东西 → 仓库"能力的落地

**当前状态**：已设计，未实现。

## 5. 参考项目调研：值得补进引擎的节点/触发器（2026-08-22）

来源：Automate（官方 416 页 block 索引）、Tasker（官方 A-Z 索引 + 6.6 更新日志）、MacroDroid（官方 Wiki 97 触发器）、Auto.js/AutoJs6（全系文档），4 个子代理抓取官方文档后交叉对照 Habitat 现有 67 个节点。**完整报告见 [RESEARCH_REFERENCE_PROJECTS.md](RESEARCH_REFERENCE_PROJECTS.md)**。

### 关键趋势
- Tasker 6.6 官方集成 Shizuku → 佐证 Habitat"无障碍 + Shizuku"路线正确
- Automate 也没有 PDF/SFTP/磁条卡/WebSocket/独立正则块 → 不是差距
- 事件模型：Automate "When changed"、MacroDroid 广播回调 → Habitat 落地为"节点 waitForChange 语义 + 常驻监听服务"

### 第一批候选（★高、零/低权限、纯逻辑或 Shizuku 一行命令）
Dialog 输入/确认/选择、SQLite 查询、ZIP 压缩/解压、文件加解密（AES-GCM）、媒体控制（play/pause/next）、壁纸设置、泛化系统设置读写（Shizuku settings）、飞行模式/移动数据（Shizuku）、数组/多变量 + 日期时间解析、屏幕状态/耳机/音乐播放查询、广播发送 + Intent Received 触发器、TTS 增强、天气/地理编码（HTTP）

### 第二批候选（◐中）
OCR、STT、热点、NFC 标签、通知点击、地理围栏、日历/联系人 CRUD、FTP、SMTP 邮件、Quick Settings 磁贴入口、定时/周期触发器（cron 调度器）

### 触发器候选（事件）
屏幕亮/灭/解锁、充电插拔、WiFi 连接、蓝牙设备、耳机插拔、前台应用变化/新窗口、通知点击/移除、应用安装/卸载、开机/关机、时间/时区变化、摇一摇/传感器阈值、来电状态、变量变化、Intent Received、日出日落（纯计算）

### 明确不做
Net Access 防火墙（VPNService）、Call Block/Divert（运营商级）、Matter/MIDI/Device Effects、远程多设备执行、Java/JS 万能后门、磁条卡/MIFARE、PDF

### 国内 ROM 适配（写文档的"坑"）
MIUI 无障碍易失效（自启动/省电白名单/神隐模式必改）；ColorOS 15 需"后台耗电管理+自启动"；OriginOS 高耗电白名单 + 后台弹出界面；EMUI 启动管理改三项；Android 10+ 剪贴板后台受限、13+ 悬浮窗单独授权、MediaProjection 每次弹窗。

## 6. 引擎线程约束（重要，新增节点必读）

Handler 跑在 `Dispatchers.Default`（无 Looper）：
- ❌ 不用依赖 `Looper.myLooper()` 的回调 API（如 PhoneStateListener）→ 会崩
- ✅ 用同步查询（`tm.callState`）或自带 `Executor`/`HandlerThread`
- 详见 [ENGINE_EXTENDING.md](ENGINE_EXTENDING.md)

## 实施优先级建议

1. **自跑测试架**（先验证现有 51 节点，也是铺新节点的前提）→ 骨架已实现，46 条用例待真机验收
2. **引擎模块化**（NodeModule，地基）
3. **按模块实现待实现节点**（用测试架逐个验证）→ 阶段 C 16 个已实现，待真机验收
4. **下一批节点**（见 §5：SET_WALLPAPER / GET_SCREEN_STATE / GET_TRAFFIC / AIRPLANE_MODE / MOBILE_DATA）
5. **存储区**（Habitat 产品能力）
