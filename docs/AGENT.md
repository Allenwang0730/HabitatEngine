# Habitat Agent 服务（AI 控制手机通道）

> 把 Habitat 引擎的节点通过 WebSocket 暴露为工具，让云端 LLM / AI 客户端实时控制手机。
> 状态：✅ 链路已跑通（2026-08-22，无线 WiFi 实测成功）

## 架构

```
云端 LLM / AI Agent ──WebSocket（WiFi 无线）──► 手机 Habitat AgentService（:8931）
                                                  ├─ /health  健康检查
                                                  ├─ /tools   节点清单（按 NodeModule 分组）
                                                  └─ /ws      工具调用协议
```

## 工具调用协议（WS JSON）

请求：
```json
{ "id": "1", "tool": "ACTION_READ_SCREEN", "params": { "keyword": "" } }
```

响应：
```json
{ "id": "1", "ok": true, "outputs": { "screen_data": false, "scr_text": "..." }, "error": null }
```

内置工具（非节点）：
- `ping` → 连通性
- `screen` → 无障碍树全屏文本 + 前台包名（AI 的"眼睛"，替代截图省 token）

## 启动与鉴权

```bash
# Android 14+ 后台启动受限：由前台 Activity 代启（该 Activity 需 DUMP 权限，只有 adb shell 能调）
adb shell am start -n com.ailun.habitat/.app.NodeSelfTestActivity --ez start_agent true
# 默认只监听 127.0.0.1：用 adb forward 从电脑访问
adb forward tcp:8931 tcp:8931
```

- AgentService 是前台服务（常驻通知），进后台不会被回收。
- **令牌**：首次启动随机生成 32 字节 hex，存加密存储，在 Agent 页查看/复制/重新生成。旧的 `habitat-dev-token` 已作废。
  连接时带 `Authorization: Bearer <token>`（或兼容的 `?token=`）。`/health` 免鉴权，`/tools` `/ws` `/mcp` 都要。
- **局域网访问**：默认关闭（只绑 127.0.0.1）。在 Agent 页打开后重启服务，才会监听 0.0.0.0。
- **高危节点**：`AgentService.REMOTE_DANGEROUS_TOOLS`（Shell、文件、拨号、短信/通话记录、加解密、压缩、SQLite、强停、广播、壁纸、录音、拍照、写语音文件）远程调用默认拒绝，`tools/list` 也不列出；需在 Agent 页手动开启。

## 客户端

```powershell
$env:AGENT_TOKEN = "<Agent 页复制的令牌>"
# 读屏（眼睛）
.\scripts\agent_ws.ps1 -Tool screen
# 打开设置（手）
.\scripts\agent_ws.ps1 -Tool ACTION_LAUNCH_APP -Params '{"package_name":"com.android.settings"}'
```

`scripts/agent_daemon.mjs` 需要 `--token` 或 `AGENT_TOKEN`；它的本地 HTTP 口要求请求头 `X-Habitat-Key`（启动时打印，或用 `HABITAT_KEY` 指定），并拒绝带 `Origin` 的浏览器请求。

## 实测验证（2026-08-22）

- WiFi 无线连接手机 `<ææº IP>:8931` ✅
- `screen` 返回 Habitat 主界面文本 + 前台应用 ✅
- `ACTION_LAUNCH_APP` 打开系统设置（`launch_success=True`），再 `screen` 确认设置页 ✅
- 完整 Agent 循环：观察 → 操作 → 验证 ✅

## 自主模式（1.7.0）

手机本机循环，Habitat 自己执行，不经过 Mobilerun：

`读屏 → 决策（Jev / DeepSeek / 本地）→ 节点执行 → 再读屏`

- 决策只从当前屏幕候选里选题。候选由代码生成（点击、滚动、输入目标原文、打开点名应用、返回、桌面、等待、完成、无法继续）。
- Jev 走 `POST https://api.typesafe.ai/v1/systemone`，key 存在加密存储（Agent 页填写），不进代码、不进日志。429/5xx/网络错误重试一次；停止按钮会立即取消在途请求。
- MCP/WS：`agent_start`（`goal` / `provider` / `max_steps`）、`agent_stop`、`agent_status`。启动后立即返回，不阻塞到循环结束。
- 微信：系统设置另开「Habitat 微信读屏」。类名是 `SelectToSpeakService`，不要改。树为空才 OCR。

### 安全闸（ActionCatalog）

- 敏感按钮（支付、转账、购买、删除、卸载、重置、允许/授权…）默认不进候选；**本机发起**且目标原文写了同一个词才放行。**远程发起**（MCP `agent_start`）的目标一律不能解锁。
- 验证/支付凭据页（出现"支付密码""验证指纹""人脸验证"等）不给任何点击和输入，只剩返回/桌面/等待/完成/无法继续。
- 系统权限弹窗与安装器（permissioncontroller / packageinstaller）：只有本机发起且目标里写了"授权/允许/权限/安装"才给点击。
- 密码框的文字不发给决策器（替换为"（密码框）"），也不作为输入目标。
- 注意：屏幕文字（包括微信聊天内容）会发给所选的云端决策器（Jev / DeepSeek）；本地决策器不联网。

### 排查「找不到控件」

```bash
node scripts/mcp_call.mjs debug_screen '{"goal":"打开设置，进入蓝牙页面"}'   # 窗口、元素、候选一次看全
node scripts/mcp_call.mjs --full agent_status '{}'                             # 完整步骤日志
```

- `source=none windows=0`：无障碍没连上（vivo 每次重装 APK 都会把无障碍服务关掉，需重新打开）。
- `locked=true`：手机锁屏，先解锁。
- TAP 候选很少：看元素列表里是否只有状态栏/一个大容器；容器聚合与系统窗口上限在 `ScreenObserver.select`。
- Jev 报 HTTP 451：服务端按地区拒绝，换网络或改用 DeepSeek。

### 循环停止条件

- 连续 3 次等待、点击目标在决策期间离开原位两次、连续 3 次低置信（< 0.15 跳过，0.15–0.3 照做）、步数上限（1–30，默认 12）。
- 重复/兜圈（同一动作连续 2 次，或最近 6 步里 3 次；滚动且屏幕在变不算）：先替模型换招（返回/滚动），换 2 次仍重复才停。
- 动作失败先换同操作的次优候选重试一次。
- 死路名单：无效的原地动作、点进外部 App 又被拉回、进子菜单又退出的入口，本次任务内不再出现在候选里。
- 每步执行后比较屏幕是否变化，并把结果、失败原因、页面标题、Activity 带给决策器。

## 已知问题 / 待办

- **端口**：8931（可配置）；Ktor Netty 在 Android 上绑 tcp6 双栈，IPv4 实测可连
- **微信读屏**：伪装服务 + OCR 已接入，具体 ROM / 微信版本是否放行要以真机为准。改无障碍配置后必须关掉再打开。该服务冒用 Google 包名，不能上架 Google Play
