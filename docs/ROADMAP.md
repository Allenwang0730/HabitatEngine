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

**当前状态**：已实现的通话/来电相关节点（CALL_DIRECT、WAIT_CALL_END、WAIT_CALL_ANSWER、READ_CALL_LOG）不在上表。**阶段 C 16 个候选节点已全部实现（2026-08-22）**，见 `ENGINE_MASTER_PLAN.md` §4 状态；其中 6 个零权限 + 6 个中风险 + 4 个高风险（仅代码，待真机验收）。

## 4. 存储区（Habitat 的"接收的文件"）

**目标**：工作流抓取的东西（录音/截图/文本）统一落 `/sdcard/Habitat/received/`，配列表 UI（可播放/查看/删除）。

- 工作流用 `ACTION_FILE_OPERATION`（copy/write 到该目录）即可
- 音频做媒体扫描
- 这是引擎"抓取任意东西 → 仓库"能力的落地

**当前状态**：已设计，未实现。

## 5. 参考项目调研：值得补进引擎的节点/触发器（2026-08-22）

来源：Automate（380+ block）、MacroDroid、MaaFramework、Auto.js、Tasker。按"正常 App + 无 root（Shizuku/无障碍/权限）可行 + 有真实用途"标注可行性。

### 值得补的节点（按可行性排序）

| 节点 | 参考来源 | 权限/依赖 | 可行性 |
|------|---------|----------|--------|
| `ACTION_SET_WALLPAPER` | 各项目 | 无（需图片路径） | ★ 高，零权限 |
| `ACTION_GET_SCREEN_STATE` | Auto.js | 无 | ★ 高，`PowerManager.isInteractive` |
| `ACTION_GET_TRAFFIC` | 各项目 | 无 | ★ 高，`TrafficStats` |
| `ACTION_AIRPLANE_MODE` | MacroDroid | WRITE_SECURE_SETTINGS（adb 授权）或 shell | ★ 高，`settings put global` |
| `ACTION_MOBILE_DATA` | MacroDroid/Tasker | Shizuku shell `svc data` | ★ 高（有 shell 时） |
| `ACTION_MEDIA_CONTROL`（play/pause/next） | Tasker/Auto.js | 通知监听（控制他 app 播放） | ◐ 中，需 NLS |
| `ACTION_DIALOG_INPUT`（工作流中弹窗要用户输入/选择） | Automate | app 侧 UI | ◐ 中，需宿主配合 |
| `ACTION_SQLITE_QUERY` | Automate | 无（自己文件） | ◐ 中 |
| `ACTION_TEXT_OCR`（截图识字） | Auto.js | ML Kit 依赖 | ◐ 中 |
| `ACTION_STT`（语音转文字） | Automate | 网络/系统识别 | ◐ 中 |
| `ACTION_GEOFENCE`（进出区域） | Tasker/Automate | 定位权限 | ◐ 中（可先用 GET_LOCATION 轮询替代） |
| `ACTION_GET_WEATHER` | 各项目 | 网络 | ★ 高（HTTP+PARSE_JSON 已可实现，无需新节点） |
| `ACTION_APP_INSTALL/UNINSTALL` | MacroDroid | PackageInstaller/UI | ✗ 低，权限重 |

### 值得补的触发器（Trigger）

| 触发器 | 说明 | 可行性 |
|--------|------|--------|
| `battery_level` / `charger` | 电量低于阈值 / 插拔充电器 | ★ 高，注册广播即可 |
| `call_end`（通话结束） | 挂断后触发收尾工作流（ENGINE_EXTENDING 已提过） | ★ 高，已有 IncomingCallService 同款监听 |
| `app_launched`（应用打开） | 前台应用变化触发 | ◐ 中，需常驻监听/无障碍 |
| `wifi_connected` | 连上指定 WiFi | ◐ 中 |
| `shake`（摇一摇） | 传感器触发 | ◐ 中 |
| `location_enter`（进入区域） | 地理围栏 | ◐ 中（权限重） |

**建议**：下一批优先做 ★ 高 的零权限/低权限节点（SET_WALLPAPER / GET_SCREEN_STATE / GET_TRAFFIC / AIRPLANE_MODE / MOBILE_DATA），以及 battery/charger 与 call_end 触发器。

## 6. 引擎线程约束（重要，新增节点必读）

Handler 跑在 `Dispatchers.Default`（无 Looper）：
- ❌ 不用依赖 `Looper.myLooper()` 的回调 API（如 PhoneStateListener）→ 会崩
- ✅ 用同步查询（`tm.callState`）或自带 `Executor`/`HandlerThread`
- 详见 [ENGINE_EXTENDING.md](ENGINE_EXTENDING.md)

## 实施优先级建议

1. **自跑测试架**（先验证现有 51 节点，也是铺新节点的前提）→ 骨架已实现，41 条用例待真机验收
2. **引擎模块化**（NodeModule，地基）
3. **按模块实现待实现节点**（用测试架逐个验证）→ 阶段 C 16 个已实现，待真机验收
4. **下一批节点**（见 §5：SET_WALLPAPER / GET_SCREEN_STATE / GET_TRAFFIC / AIRPLANE_MODE / MOBILE_DATA）
5. **存储区**（Habitat 产品能力）
