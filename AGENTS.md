# AGENTS.md — 通用 Agent 指令 / 踩坑记忆（跨 agent：DeepSeek、Claude 等都应遵守）

本项目给 AI agent 的硬性规范与踩坑记忆。任何 agent 在此仓库工作前先读本文件。

## 🔒 敏感信息铁律（反例：DeepSeek API key 硬编码导致泄露，已重写 git 历史清除）

**绝不把任何密钥/token/机密硬编码进代码或提交到 git。** 铁律。

- API key / token / password / secret：一律用**环境变量**（`DEEPSEEK_API_KEY`）或 App 内**运行时配置**（SharedPreferences/设置界面），**不落代码**。
- `.gitignore` 已保护 `.env`/`*.secret`/`*.pem`/`*.jks` 等；不要 `git add -f` 强推敏感文件。
- 提交前：`pre-commit` hook 会跑 `scripts/scan_secrets.sh`，命中 `sk-...`/`BEGIN PRIVATE KEY`/`AKIA`/`ghp_` 等疑似密钥**拒绝提交**。
- 若密钥泄露：立即 `git filter-repo --replace-text` 清**全部历史** + 强推覆盖远程；**同时必须去服务方作废该密钥**（git 清除≠key 失效）。

## ⚙️ 权限声明铁律（反例：CALL_PHONE 漏声明导致直拨不可用，排查多轮）

**任何 handler/节点用到的运行时权限，必须在 app `AndroidManifest.xml` 声明**，否则系统给不了权限 → 授权框不出现、`pm grant` 无效、功能静默失败。

- 反例：`ACTION_CALL_DIRECT` 直拨需 `CALL_PHONE`，但 Habitit manifest 漏声明 → 直拨必败（SmartCS 声明了能拨）。用户看到的"电话权限开"是 `READ_PHONE_STATE`（读状态），≠ `CALL_PHONE`（拨号）——**两个不同权限**。
- 新增/改节点：必查 handler 用到的权限是否在 manifest 声明（CALL_PHONE/READ_CALL_LOG/SEND_SMS/RECORD_AUDIO/CAMERA/定位/通知）。之前也发现 `READ_CALL_LOG` 同理漏声明。
- 验证：`adb shell dumpsys package <pkg> | grep '<PERM>: granted'`；`pm grant`（debug）强制授予。
- 危险权限（CALL_PHONE/READ_PHONE_STATE/CAMERA/RECORD_AUDIO/定位/READ_SMS 等）需运行时授权或 `pm grant`。

## 🧱 引擎/App 其他约定

- `lib` 引擎零依赖 app；handler 跑在 `Dispatchers.Default`（无 Looper），别用依赖 Looper 的 API（会崩）。
- 直拨 `ACTION_CALL_DIRECT` 走 `TelecomManager.placeCall`（后台可拨，比 `startActivity(ACTION_CALL)` 稳）。
- MCP/WS 连接需令牌（`Authorization: Bearer` 或 `?token=`）。令牌每台设备首次启动随机生成，在 Agent 页查看；服务默认只监听 127.0.0.1（电脑侧先 `adb forward tcp:8931 tcp:8931`）。远程调用高危节点默认拒绝。
- 拼 shell 命令的参数一律走 `ShellQuote`；`ACTION_SHELL` 的 `${var}` 会自动转义，别再手动加引号。
- 机密（API key / 令牌）存 `SecretStore`（加密），不要用普通 SharedPreferences。
- 单测：`./gradlew :lib:testDebugUnitTest :app:testDebugUnitTest`；自测脚本用 PowerShell 7 跑：`pwsh -File scripts/run_selftest.ps1`（Windows PowerShell 5 读 UTF-8 无 BOM 脚本会乱码报错）。
- 引擎改动后跑自测回归（`scripts/run_selftest.ps1`）验证。
