# Habitat Agent 服务（AI 控制手机通道）

> 把 Habitat 引擎的 80 个节点通过 WebSocket 暴露为工具，让云端 LLM / AI 客户端实时控制手机。
> 状态：✅ 链路已跑通（2026-08-22，无线 WiFi 实测成功）

## 架构

```
云端 LLM / AI Agent ──WebSocket（WiFi 无线）──► 手机 Habitat AgentService（:8931）
                                                  ├─ /health  健康检查
                                                  ├─ /tools   80 节点清单（按 NodeModule 分组）
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

## 启动

```bash
# Android 14+ 后台启动受限：由前台 Activity 代启
adb shell am start -n com.ailun.habitat/.app.NodeSelfTestActivity --ez start_agent true
# 查手机 WiFi IP
adb shell "ip -f inet addr show wlan0 | grep inet"
```

## 客户端

```powershell
# 读屏（眼睛）
.\scripts\agent_ws.ps1 -Tool screen
# 打开设置（手）
.\scripts\agent_ws.ps1 -Tool ACTION_LAUNCH_APP -Params '{"package_name":"com.android.settings"}'
```

## 实测验证（2026-08-22）

- WiFi 无线连接手机 `<ææº IP>:8931` ✅
- `screen` 返回 Habitat 主界面文本 + 前台应用 ✅
- `ACTION_LAUNCH_APP` 打开系统设置（`launch_success=True`），再 `screen` 确认设置页 ✅
- 完整 Agent 循环：观察 → 操作 → 验证 ✅

## 自主模式（1.7.0）

手机本机循环，Habitat 自己执行，不经过 Mobilerun：

`读屏 → 决策（Jev / DeepSeek / 本地）→ 节点执行 → 再读屏`

- 决策只从当前屏幕候选里选题。候选由代码生成（点击、滚动、输入目标原文、打开点名应用、返回、桌面、等待、完成、无法继续）。
- Jev 走 `POST https://api.typesafe.ai/v1/systemone`，key 存在 Agent 页，不进代码、不进日志。
- MCP/WS：`agent_start`（`goal` / `provider` / `max_steps`）、`agent_stop`、`agent_status`。启动后立即返回，不阻塞到循环结束。
- 微信：系统设置另开「Habitat 微信读屏」。类名是 `SelectToSpeakService`，不要改。树为空才 OCR。

## 已知问题 / 待办

- **鉴权**：`/ws` `/mcp` 已要求 `?token=`。生产环境应改掉默认令牌
- **服务生命周期**：Activity 代启后进程被系统回收则服务停（Android 后台限制）；长期运行需转前台服务（startForeground + 通知）
- **端口**：8931（可配置）；Ktor Netty 在 Android 上绑 tcp6 双栈，IPv4 实测可连
- **微信读屏**：伪装服务 + OCR 已接入，具体 ROM / 微信版本是否放行要以真机为准。改无障碍配置后必须关掉再打开
