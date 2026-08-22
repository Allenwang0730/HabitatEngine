# Habitat 节点自测（阶段 A · 自跑测试架）

> 目标：一条 adb 命令跑完全部低风险节点测试并输出 pass/fail 报告，免人工。
> 对应总纲：`ENGINE_MASTER_PLAN.md` 阶段 A。

## 真机验收结果（2026-08-22，iQOO / OriginOS）

**61 条用例 = 52 PASS / 0 FAIL / 9 SKIP** ✅（v1.4.2 模块化 + RETRY/ASSERT/状态/广播/天气节点后，含模块化冒烟检查）全部可自动验证的用例通过。

设计内 SKIP（7 条）：ACTION_PLAY_MEDIA（需真实媒体文件）、ACTION_GLOBAL_NAV（导航副作用）、ACTION_RECORD_AUDIO / ACTION_CAPTURE_PHOTO / ACTION_GET_LOCATION / ACTION_READ_NOTIFICATIONS（高风险需权限）、ACTION_SET_ALARM（vivo/BBK 需系统签名权限 SET_ALARM，其他 ROM 可手工验证）。

验收过程中修复的 bug：
1. `NodeSqliteQueryHandler` — openDatabase 缺 CREATE_IF_NECESSARY，新建库文件打不开
2. `NodeSelfTestActivity` — 结果 UI 更新未切主线程（后台线程改 TextView 崩溃）
3. 用例缺陷：WAIT_FOR_ELEMENT / SIM_INFO 的 verify 与 output_var 不匹配；SQLITE 用例不幂等（重复 INSERT 累积行数）
4. 脚本：Activity 类名（`.app` 子包）、安装后等无障碍服务重连（8s）

## 一条命令

```powershell
# 直接跑（用已构建的 SmartCS debug APK）
.\scripts\run_selftest.ps1

# 先构建再跑
.\scripts\run_selftest.ps1 -Build

# 只跑指定用例
.\scripts\run_selftest.ps1 -Only "ACTION_MATH"

# 不重装 APK
.\scripts\run_selftest.ps1 -SkipInstall
```

脚本流程：检查设备 → `adb install -r` → 清 logcat → `am start` TestActivity（auto 模式）→ 轮询 `HABITAT_TEST_DONE` 标记 → `run-as` 拉取结果 JSON → push 到 `/sdcard/Habitat/test_results.json` → 本机报告 `selftest_results/latest.json`。

## 架构

```
lib/src/main/java/com/ailun/habitat/selftest/
├── NodeSelfTest.kt    # 框架：SelfTestCase / SelfTestResult / NodeSelfTestRunner
└── NodeSelfTests.kt   # 用例目录（首批 61 条）

app: NodeSelfTestActivity.kt   # adb 驱动的测试宿主（Habitat app，com.ailun.habitat）
```

> **SmartCS 完全不动**：测试宿主只放在 Habitat 仓库的 `:app`；
> 两份 `:lib` 在测试架期间**暂不同步**（selftest 包只在 Habitat lib，
> SmartCS 下次收引擎改动时一并拷入，见 ENGINE_MASTER_PLAN 阶段 A）。

### 工作原理

- 每条用例 = 一个**测试工作流 JSON**（真实走 `HabitatJson.fromJson` → `HabitatExecutor` 全链路）+ **verify 断言**（对执行后的变量快照做判断）。
- 工作流统一以 `ACTION_SET_VARIABLE` 写 `wf_completed=true` 结尾；断言通常同时检查 `wf_completed` 与节点特定输出变量（如 `file_success`、`http_response`）。
- 测试前自动注入 `filesDir` / `cacheDir` 变量，供工作流插值（如 `"path": "${filesDir}/selftest.txt"`）。
- 逐条独立：JSON 解析失败 / handler 异常 / 超时（默认 30s）→ FAIL；需无障碍的用例在服务未连接时 → SKIP。
- 顺序执行（避免剪贴板/文件等共享状态互相干扰）。

### 新增一条用例

1. 在 `NodeSelfTests.kt` 写一个 `private fun testXxx() = SelfTestCase(...)`，加入 `all()` 列表。
2. 工作流 JSON 用 `trimIndent()` 包裹，注意 `${var}` 在 Kotlin 原始字符串里要写成 `${'$'}{var}`。
3. 跑 `run_selftest.ps1 -Only "用例名"` 单条验证。
4. 同步两份 `:lib`（`diff -rq` 确认 0 差异）。

## 首批 61 条用例

| 类别 | 用例 |
|------|------|
| 逻辑控制 | CONDITION_SWITCH·true/false 分支、CONDITION_ADVANCED_SWITCH·数值比较、ACTION_LOOP·计数循环、ACTION_TRY_CATCH·成功分支、ACTION_LOG |
| 变量与数据 | ACTION_SET_VARIABLE、ACTION_TEXT_OPERATION、ACTION_MATH、ACTION_CLIPBOARD、ACTION_PARSE_JSON、ACTION_PARSE_XML、ACTION_BASE64、ACTION_FILE_OPERATION、ACTION_SQLITE_QUERY、ACTION_ZIP、ACTION_CRYPTO、ACTION_ARRAY、ACTION_PARSE_DATETIME |
| 工具/时间 | ACTION_GET_TIME、ACTION_RANDOM、ACTION_DELAY、ACTION_GET_BATTERY、ACTION_GET_DEVICE_INFO、ACTION_GET_STORAGE_INFO、ACTION_WAIT_UNTIL_TIME、ACTION_GET_SENSOR |
| 网络 | ACTION_HTTP_REQUEST、ACTION_NETWORK_STATUS |
| UI | ACTION_TOAST、ACTION_VIBRATE |
| 系统 | ACTION_SHELL（Runtime.exec 兜底）、ACTION_GET_CALL_STATE、ACTION_GET_SIM_INFO、ACTION_DND_MODE（get） |
| 感知（需无障碍） | ACTION_READ_SCREEN、ACTION_GET_APP_INFO、ACTION_WAIT_FOR_ELEMENT·消失判定、ACTION_WAIT_FOR_ELEMENT·出现超时 |
| 阶段 C 新增（待真机验收，skipReason 标记 SKIP） | ACTION_PLAY_MEDIA、ACTION_GLOBAL_NAV、ACTION_SET_ALARM、ACTION_RECORD_AUDIO、ACTION_CAPTURE_PHOTO、ACTION_GET_LOCATION、ACTION_READ_NOTIFICATIONS |

> 说明：SKIP 用例（skipReason）会在自动跑时如实标记 SKIP，真机验收时移除/按需启用。

**不覆盖**（需真机手工测）：真实通话（CALL_DIRECT / WAIT_CALL_ANSWER / WAIT_CALL_END / READ_CALL_LOG）、录音/拍照/定位/通知读取（高风险，见上）、WIFI/蓝牙/亮度等改设备状态的节点、FLASHLIGHT、DND 写入（on/off）。

## 测试架已暴露的问题（发现 ≠ 用例错误）

1. **ACTION_LOG 不走 onLog 回调**：`NodeLogHandler` 只写 Logcat（tag=HabitatLog），不调 `context.log()`，所以工作流日志通道收不到 LOG 节点输出（LOG 用例只断言正常完成）。
2. **ACTION_TRY_CATCH 的 error 分支不可达**：`_last_error` 标记只有 `NodeTryCatchHandler` 自己读写，`HabitatExecutor` 捕获 handler 异常时直接终止流程、不写标记 → 错误恢复分支实际从未触发。
3. **SmartCS 缺 INTERNET 权限**（已在本阶段补上）：此前 `ACTION_HTTP_REQUEST` 在 SmartCS 里必然 SecurityException。

## 手工可验证项

- 界面模式：`am start -n com.ailun.habitat/.NodeSelfTestActivity`（不带 auto）→ 留在界面展示结果。
- 结果文件：`adb shell run-as com.ailun.habitat cat files/test_results.json`



