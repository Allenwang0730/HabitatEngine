# 用 MCP 客户端控制手机

Habitat 在手机上跑一个 MCP 服务（端口 8931，默认只监听手机本机）。电脑上的 AI 客户端经 USB 转发连上后，可以读屏、调用全部节点（每个都带参数定义）、启动 Agent 任务、往悬浮小屏推内容、读写工作区。

## 准备

1. 手机：打开 Habitat →「Agent」页 → 打开服务，复制**访问令牌**。
2. 电脑：`adb forward tcp:8931 tcp:8931`（USB 连接，每次插上手机后执行一次）。
3. 检查：`curl -s -H "Authorization: Bearer <令牌>" -H "Content-Type: application/json" -d '{"jsonrpc":"2.0","id":1,"method":"ping"}' http://127.0.0.1:8931/mcp`，返回 `{"jsonrpc":"2.0","id":1,"result":{}}` 即通。

## 接入方式

| 客户端 | 传输 | 配置 |
|---|---|---|
| Claude Code | Streamable HTTP | `claude mcp add --transport http habitat http://127.0.0.1:8931/mcp --header "Authorization: Bearer <令牌>"` |
| Cursor | Streamable HTTP | `~/.cursor/mcp.json`：见下 |
| Claude Desktop | stdio（经桥） | `claude_desktop_config.json`：见下 |
| 自己的脚本 | HTTP 或 WebSocket | `POST /mcp`（JSON-RPC）；或 `ws://127.0.0.1:8931/mcp?token=<令牌>`（`scripts/mcp_call.mjs`） |

Cursor（`~/.cursor/mcp.json`）：

```json
{
  "mcpServers": {
    "habitat": {
      "url": "http://127.0.0.1:8931/mcp",
      "headers": { "Authorization": "Bearer <令牌>" }
    }
  }
}
```

Claude Desktop（只支持 stdio，用仓库里的桥 `scripts/habitat_mcp_stdio.mjs`，需要 Node 18+）：

```json
{
  "mcpServers": {
    "habitat": {
      "command": "node",
      "args": ["C:/path/to/HabitatEngine/scripts/habitat_mcp_stdio.mjs"],
      "env": {
        "HABITAT_TOKEN": "<令牌>",
        "HABITAT_ADB": "C:/Users/<你>/AppData/Local/Android/Sdk/platform-tools/adb.exe"
      }
    }
  }
}
```

设了 `HABITAT_ADB`，桥启动时会自动执行 `adb forward`。

## 能做什么

- `screen`：当前屏幕文字和前台应用；`debug_screen`：观测快照 + 候选动作（排查用）。
- 所有节点（`ACTION_*`、`CONDITION_*`）：参数见 [NODES.md](NODES.md)，`tools/list` 里也带完整参数定义。
- `agent_start` / `agent_status` / `agent_stop`：把一句话目标交给手机上的 Agent。
- `mini_screen_status`：读回悬浮小屏当前内容。

试一句：「用 habitat 打开手机设置进到蓝牙页，然后把电量显示在手机小屏上。」

## 安全

- 令牌每台手机随机生成，可在「Agent」页重新生成；局域网访问默认关闭（开了是明文 `ws://` / `http://`，同网段的人可能截到令牌）。
- Shell、文件、短信、通话记录、拨号、录音拍照等高危节点远程默认拒绝，工作区删除也是；需要在「Agent」页手动开启。
- 远程发起的 Agent 任务：发消息只填不发、打电话只填拨号盘、支付/授权类按钮一律不能点。
- `POST /mcp` 拒绝带非本机 `Origin` 的浏览器请求（防 DNS 重绑定）。
- 屏幕文字、笔记会发给你接入的 AI 客户端：按你对该客户端的信任程度使用。
