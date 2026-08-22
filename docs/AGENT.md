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

- WiFi 无线连接手机 `192.168.0.103:8931` ✅
- `screen` 返回 Habitat 主界面文本 + 前台应用 ✅
- `ACTION_LAUNCH_APP` 打开系统设置（`launch_success=True`），再 `screen` 确认设置页 ✅
- 完整 Agent 循环：观察 → 操作 → 验证 ✅

## 已知问题 / 待办

- **无鉴权**：当前局域网裸奔，生产必须加 token（握手时校验）
- **服务生命周期**：Activity 代启后进程被系统回收则服务停（Android 后台限制）；长期运行需转前台服务（startForeground + 通知）
- **端口**：8931（可配置）；Ktor Netty 在 Android 上绑 tcp6 双栈，IPv4 实测可连
- **云端 Agent 循环**：目前是"人/LLM 手动发 WS 消息"；接 MCP 或自建 Agent 循环后即可让任意 AI 客户端调用（见 ENGINE_MASTER_PLAN 阶段 E 构想）
