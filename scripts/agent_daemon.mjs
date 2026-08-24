#!/usr/bin/env node
// Habitat Agent 常驻客户端（优化：一次握手，长连接复用，本地 HTTP 转发）
// 用法：
//   node scripts/agent_daemon.mjs                # 启动（默认连 127.0.0.1:8931，本地 18080）
//   AGENT_HOST=IP AGENT_PORT=PORT LOCAL_PORT=18080 node scripts/agent_daemon.mjs
// 本地接口：
//   GET  http://127.0.0.1:18080/status          # 连接状态
//   POST http://127.0.0.1:18080/call             # {"tool":"ACTION_CLICK","params":{...}}
//   GET  http://127.0.0.1:18080/screen           # 快捷读屏
import http from 'node:http';

const HOST = process.env.AGENT_HOST || '127.0.0.1';
const PORT = process.env.AGENT_PORT || '8931';
const TOKEN = process.env.AGENT_TOKEN || 'habitat-dev-token';
const LOCAL_PORT = parseInt(process.env.LOCAL_PORT || '18080', 10);

const ws = new WebSocket(`ws://${HOST}:${PORT}/mcp?token=${encodeURIComponent(TOKEN)}`);
let nextId = 1;
const pending = new Map();
let initialized = false;

function send(method, params) {
  const id = nextId++;
  ws.send(JSON.stringify({ jsonrpc: '2.0', id, method, params }));
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject });
    setTimeout(() => {
      if (pending.has(id)) { pending.delete(id); reject(new Error('MCP 调用超时')); }
    }, 60000);
  });
}

async function callTool(tool, params) {
  if (!initialized) throw new Error('MCP 未初始化');
  const res = await send('tools/call', { name: tool, arguments: params || {} });
  const r = res.result || {};
  let parsed = null;
  try { parsed = JSON.parse(r.content?.[0]?.text ?? ''); } catch { parsed = r.content?.[0]?.text ?? ''; }
  return { ok: !r.isError, data: parsed, error: r.isError ? r.content?.[0]?.text : null };
}

ws.onopen = async () => {
  try {
    await send('initialize', {
      protocolVersion: '2025-06-18',
      capabilities: {},
      clientInfo: { name: 'habitat-agent-daemon', version: '0.1' },
    });
    ws.send(JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' }));
    initialized = true;
    console.log(`[daemon] MCP connected ${HOST}:${PORT}，本地端口 ${LOCAL_PORT}`);
  } catch (e) {
    console.error('[daemon] initialize 失败:', e.message);
    process.exit(1);
  }
};

ws.onmessage = (ev) => {
  const msg = JSON.parse(ev.data);
  if (msg.id && pending.has(msg.id)) {
    const p = pending.get(msg.id);
    pending.delete(msg.id);
    msg.error ? p.reject(new Error(msg.error.message)) : p.resolve(msg);
  }
};

ws.onclose = () => { console.error('[daemon] 连接断开'); process.exit(1); };
ws.onerror = (e) => { console.error('[daemon] 连接错误:', e.message || e); };

const server = http.createServer(async (req, res) => {
  const sendJson = (code, obj) => { res.writeHead(code, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(obj)); };
  try {
    if (req.method === 'GET' && req.url === '/status') {
      sendJson(200, { connected: initialized, host: HOST, port: PORT });
      return;
    }
    if (req.method === 'GET' && req.url === '/screen') {
      const r = await callTool('screen', {});
      sendJson(200, r);
      return;
    }
    if (req.method === 'POST' && req.url === '/call') {
      let body = '';
      for await (const chunk of req) body += chunk;
      const { tool, params } = JSON.parse(body || '{}');
      const r = await callTool(tool, params);
      sendJson(200, r);
      return;
    }
    sendJson(404, { ok: false, error: 'not found' });
  } catch (e) {
    sendJson(500, { ok: false, error: e.message });
  }
});

server.listen(LOCAL_PORT, '127.0.0.1', () => console.log(`[daemon] local http on :${LOCAL_PORT}`));
