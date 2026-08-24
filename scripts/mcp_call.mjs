#!/usr/bin/env node
// Habitat Agent MCP 客户端（验证 /mcp 端点）
// 用法：
//   node scripts/mcp_call.mjs                     # initialize + tools/list + tools/call(screen)
//   node scripts/mcp_call.mjs ACTION_LAUNCH_APP '{"package_name":"com.android.settings"}'
//   node scripts/mcp_call.mjs --list              # 只列工具
const args = process.argv.slice(2);
const listOnly = args.includes('--list');
const tool = listOnly ? null : (args[0] || 'screen');
const toolArgs = listOnly ? null : (args[1] ? JSON.parse(args[1]) : {});

const HOST = process.env.AGENT_HOST || '127.0.0.1';
const PORT = process.env.AGENT_PORT || '8931';
const TOKEN = process.env.AGENT_TOKEN || 'habitat-dev-token';
const ws = new WebSocket(`ws://${HOST}:${PORT}/mcp?token=${encodeURIComponent(TOKEN)}`);

let nextId = 1;
const pending = new Map();

function send(method, params) {
  const id = nextId++;
  ws.send(JSON.stringify({ jsonrpc: '2.0', id, method, params }));
  return new Promise((resolve) => pending.set(id, resolve));
}

ws.onmessage = (ev) => {
  const msg = JSON.parse(ev.data);
  if (msg.id && pending.has(msg.id)) {
    pending.get(msg.id)(msg);
    pending.delete(msg.id);
  }
};

ws.onopen = async () => {
  try {
    // 1. initialize
    const init = await send('initialize', {
      protocolVersion: '2025-06-18',
      capabilities: {},
      clientInfo: { name: 'habitat-mcp-cli', version: '0.1' },
    });
    console.log('MCP 初始化:', init.result?.serverInfo?.name, init.result?.protocolVersion);

    // 2. initialized 通知（无响应）
    ws.send(JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' }));

    // 3. tools/list
    const list = await send('tools/list', {});
    const tools = list.result?.tools || [];
    console.log(`工具数: ${tools.length}`);
    if (listOnly) {
      console.log('工具列表:', tools.map((t) => t.name).join(', '));
      ws.close();
      return;
    }

    // 4. tools/call
    const call = await send('tools/call', { name: tool, arguments: toolArgs });
    const content = call.result?.content?.[0]?.text ?? '';
    console.log(`\n>>> 调用 ${tool} ${JSON.stringify(toolArgs)}`);
    console.log(`<<< ${call.result?.isError ? 'ERROR' : 'OK'}`);
    const parsed = tryJson(content);
    if (parsed) {
      for (const [k, v] of Object.entries(parsed)) {
        const s = typeof v === 'string' && v.length > 400 ? v.slice(0, 400) + '...' : JSON.stringify(v);
        console.log(`    ${k} = ${s}`);
      }
    } else {
      console.log(content);
    }
    ws.close();
  } catch (e) {
    console.error('MCP 调用失败:', e.message);
    ws.close();
    process.exit(1);
  }
};

ws.onerror = (e) => {
  console.error('连接失败:', e.message || e);
  process.exit(1);
};

function tryJson(s) {
  try { return JSON.parse(s); } catch { return null; }
}
