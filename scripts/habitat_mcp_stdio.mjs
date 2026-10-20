#!/usr/bin/env node
// Habitat MCP 的 stdio 桥：给只支持 stdio 的 MCP 客户端（如 Claude Desktop）用。
// 从 stdin 读一行一条 JSON-RPC，转发到手机上的 `POST /mcp`（Streamable HTTP），回复写到 stdout。
// stdout 只输出 JSON-RPC，日志一律写 stderr。不依赖任何 npm 包（Node 18+）。
//
// 环境变量：
//   HABITAT_TOKEN  令牌（必填，手机 Habitat「Agent」页复制）
//   HABITAT_URL    默认 http://127.0.0.1:8931/mcp（USB：需要 adb forward tcp:8931 tcp:8931）
//   HABITAT_ADB    adb 路径；设置后启动时自动执行 adb forward（可选）
import { execFileSync } from 'node:child_process';
import { createInterface } from 'node:readline';

const TOKEN = process.env.HABITAT_TOKEN || process.env.AGENT_TOKEN;
const URL = process.env.HABITAT_URL || 'http://127.0.0.1:8931/mcp';
const log = (...a) => process.stderr.write(`[habitat-mcp] ${a.join(' ')}\n`);

if (!TOKEN) {
  log('缺少 HABITAT_TOKEN（手机 Habitat「Agent」页复制令牌）');
  process.exit(2);
}
if (process.env.HABITAT_ADB) {
  try {
    const port = new globalThis.URL(URL).port || '8931';
    execFileSync(process.env.HABITAT_ADB, ['forward', `tcp:${port}`, `tcp:${port}`], { stdio: 'ignore' });
    log(`adb forward tcp:${port} 完成`);
  } catch (e) {
    log(`adb forward 失败：${e.message}`);
  }
}

const write = (obj) => process.stdout.write(JSON.stringify(obj) + '\n');

/** 转发一条消息；网络/HTTP 错误时，对有 id 的请求回一个 JSON-RPC 错误，客户端不会一直等。 */
async function forward(line) {
  let msg;
  try {
    msg = JSON.parse(line);
  } catch {
    write({ jsonrpc: '2.0', id: null, error: { code: -32700, message: 'Parse error' } });
    return;
  }
  const id = Array.isArray(msg) ? null : msg.id;
  try {
    const res = await fetch(URL, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json, text/event-stream',
        Authorization: `Bearer ${TOKEN}`,
      },
      body: line,
    });
    if (res.status === 202) return; // 通知：没有回复
    const text = await res.text();
    if (!res.ok) throw new Error(`HTTP ${res.status} ${text.slice(0, 120)}`);
    const reply = JSON.parse(text);
    for (const r of Array.isArray(reply) ? reply : [reply]) write(r);
  } catch (e) {
    log(`转发失败：${e.message}（手机上 Agent 服务开着吗？USB 需要 adb forward tcp:8931 tcp:8931）`);
    if (id !== undefined && id !== null) {
      write({ jsonrpc: '2.0', id, error: { code: -32000, message: `Habitat 手机端不可达：${e.message}` } });
    }
  }
}

// 每条消息独立转发：慢工具（agent_status 轮询、点击等待）不阻塞后面的请求
const pending = new Set();
const rl = createInterface({ input: process.stdin, crlfDelay: Infinity });
rl.on('line', (line) => {
  if (!line.trim()) return;
  const p = forward(line).finally(() => pending.delete(p));
  pending.add(p);
});
rl.on('close', async () => {
  await Promise.allSettled([...pending]);
  process.exit(0);
});
log(`已启动，转发到 ${URL}`);
