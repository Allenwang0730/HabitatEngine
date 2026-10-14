#!/usr/bin/env node
// Habitat Agent 常驻客户端（优化：一次握手，长连接复用，本地 HTTP 转发）
// 用法（默认通过 adb forward 连手机：adb forward tcp:8931 tcp:8931）：
//   AGENT_TOKEN=<手机 Agent 页的令牌> node scripts/agent_daemon.mjs
//   node scripts/agent_daemon.mjs --token <令牌> [--host 127.0.0.1] [--port 8931] [--local-port 18080]
//   环境变量：AGENT_HOST / AGENT_PORT / AGENT_TOKEN / LOCAL_PORT / HABITAT_KEY
// 本地接口（仅 127.0.0.1；必须带请求头 X-Habitat-Key，值在启动时打印，或用 HABITAT_KEY 指定）：
//   GET  http://127.0.0.1:18080/status          # 连接状态
//   POST http://127.0.0.1:18080/call             # {"tool":"ACTION_CLICK","params":{...}}
//   GET  http://127.0.0.1:18080/screen           # 快捷读屏
import http from 'node:http';
import crypto from 'node:crypto';

function argOf(name) {
  const i = process.argv.indexOf(name);
  return i >= 0 ? process.argv[i + 1] : undefined;
}

const HOST = argOf('--host') || process.env.AGENT_HOST || '127.0.0.1';
const PORT = argOf('--port') || process.env.AGENT_PORT || '8931';
const TOKEN = argOf('--token') || process.env.AGENT_TOKEN;
const LOCAL_PORT = parseInt(argOf('--local-port') || process.env.LOCAL_PORT || '18080', 10);
const LOCAL_KEY = process.env.HABITAT_KEY || crypto.randomBytes(24).toString('hex');
const MAX_BODY = 1024 * 1024;

if (!TOKEN) {
  console.error('[daemon] 缺少令牌：设置环境变量 AGENT_TOKEN 或传 --token（手机 Agent 页可查看）');
  process.exit(2);
}

// Node 内置 WebSocket 不支持自定义请求头，令牌走 ?token=（服务端同时支持 Authorization: Bearer）
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
  let msg;
  try { msg = JSON.parse(ev.data); } catch { console.error('[daemon] 收到非 JSON 消息，已忽略'); return; }
  if (msg && msg.id && pending.has(msg.id)) {
    const p = pending.get(msg.id);
    pending.delete(msg.id);
    msg.error ? p.reject(new Error(msg.error.message)) : p.resolve(msg);
  }
};

ws.onclose = () => { console.error('[daemon] 连接断开（令牌错误时服务端会直接关闭连接）'); process.exit(1); };
ws.onerror = (e) => { console.error('[daemon] 连接错误:', e.message || e); };

const ALLOWED_HOSTS = new Set([`127.0.0.1:${LOCAL_PORT}`, `localhost:${LOCAL_PORT}`]);

function keyMatches(given) {
  if (typeof given !== 'string') return false;
  const a = Buffer.from(given);
  const b = Buffer.from(LOCAL_KEY);
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

async function readBody(req) {
  let body = '';
  for await (const chunk of req) {
    body += chunk;
    if (body.length > MAX_BODY) throw Object.assign(new Error('请求体过大'), { status: 413 });
  }
  return body;
}

const server = http.createServer(async (req, res) => {
  const sendJson = (code, obj) => { res.writeHead(code, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(obj)); };
  // 防 DNS rebinding / 浏览器跨站：只接受本机 Host，拒绝任何带 Origin 的请求，要求自定义头（浏览器 simple request 带不上）
  if (!ALLOWED_HOSTS.has(req.headers.host || '')) { sendJson(403, { ok: false, error: 'bad host' }); return; }
  if (req.headers.origin !== undefined) { sendJson(403, { ok: false, error: 'cross-origin not allowed' }); return; }
  if (!keyMatches(req.headers['x-habitat-key'])) { sendJson(401, { ok: false, error: 'missing or bad X-Habitat-Key' }); return; }
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
      const body = await readBody(req);
      let payload;
      try { payload = JSON.parse(body || '{}'); } catch { sendJson(400, { ok: false, error: '请求体不是合法 JSON' }); return; }
      const { tool, params } = payload || {};
      if (typeof tool !== 'string' || !tool) { sendJson(400, { ok: false, error: '缺少 tool' }); return; }
      const r = await callTool(tool, params && typeof params === 'object' ? params : {});
      sendJson(200, r);
      return;
    }
    sendJson(404, { ok: false, error: 'not found' });
  } catch (e) {
    sendJson(e.status || 500, { ok: false, error: e.message });
  }
});

server.listen(LOCAL_PORT, '127.0.0.1', () => {
  console.log(`[daemon] local http on 127.0.0.1:${LOCAL_PORT}`);
  if (!process.env.HABITAT_KEY) console.log(`[daemon] X-Habitat-Key: ${LOCAL_KEY}`);
});
