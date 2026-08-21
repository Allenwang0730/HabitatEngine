# Changelog

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
