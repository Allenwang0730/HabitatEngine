#!/usr/bin/env node
// 示例：电脑上的程序（或 Claude / Cursor 等 MCP 客户端）通过 MCP 编排手机上的多步任务，
// 进度实时推到手机小屏，结果写进手机工作区。
//
// 用法（手机已 adb forward tcp:8931 tcp:8931，令牌在 App「Agent」页）：
//   AGENT_TOKEN=<令牌> node scripts/demo_mcp_orchestrate.mjs
const TOKEN = process.env.AGENT_TOKEN;
if (!TOKEN) {
  console.error('缺少 AGENT_TOKEN');
  process.exit(2);
}
const ws = new WebSocket(`ws://${process.env.AGENT_HOST || '127.0.0.1'}:${process.env.AGENT_PORT || '8931'}/mcp?token=${encodeURIComponent(TOKEN)}`);
let nextId = 1;
const pending = new Map();
ws.onmessage = (ev) => {
  const msg = JSON.parse(ev.data);
  pending.get(msg.id)?.(msg);
  pending.delete(msg.id);
};
const rpc = (method, params) => new Promise((resolve) => {
  const id = nextId++;
  pending.set(id, resolve);
  ws.send(JSON.stringify({ jsonrpc: '2.0', id, method, params }));
});
async function call(name, args = {}) {
  const res = await rpc('tools/call', { name, arguments: args });
  const text = res.result?.content?.[0]?.text ?? '{}';
  try { return JSON.parse(text); } catch { return { raw: text }; }
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const t0 = Date.now();
const log = (s) => console.log(`[${((Date.now() - t0) / 1000).toFixed(1)}s] ${s}`);

ws.onopen = async () => {
  await rpc('initialize', { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'demo', version: '1' } });
  const tools = (await rpc('tools/list', {})).result.tools.length;
  log(`已连接手机 MCP 服务，可用工具 ${tools} 个`);

  const progress = (p, title) => call('ACTION_MINI_SCREEN', { action: 'progress', progress: p, title });
  await call('ACTION_MINI_SCREEN', { title: '手机巡检', text: '电脑端发起：读取设备状态，再让手机 Agent 去设置里确认存储情况', progress: 0 });
  log('小屏：开始巡检');

  // 1) 直接调用节点：几个读数，一次一个工具
  const battery = await call('ACTION_GET_BATTERY');
  const net = await call('ACTION_NETWORK_STATUS', { action: 'all' });
  const dev = await call('ACTION_GET_DEVICE_INFO');
  const storage = await call('ACTION_GET_STORAGE_INFO');
  await progress(40, '手机巡检 · 读数完成');
  const gb = (b) => (Number(b) / 1e9).toFixed(0);
  const facts = [
    `机型：${dev.device_brand ?? ''} ${dev.device_model ?? ''}，Android ${dev.device_android_version ?? '?'}`,
    `电量：${battery.battery_level ?? '?'}%${battery.battery_charging ? '（充电中）' : ''}`,
    `网络：${net.network_type ?? '?'}${net.network_connected === false ? '（未连接）' : ''}`,
    `存储：可用 ${gb(storage.storage_available)} GB / 共 ${gb(storage.storage_total)} GB（剩 ${storage.storage_available_percent}%）`,
  ];
  facts.forEach((f) => log(f));
  await call('ACTION_MINI_SCREEN', { action: 'append', text: facts.join('\n') });

  // 2) 交给手机上的 Agent：需要在界面里找的事
  const goal = '打开设置，进入存储空间页面';
  log(`启动手机 Agent：${goal}`);
  await call('agent_start', { goal, planner: true });
  let status;
  for (let i = 0; i < 60; i++) {
    await sleep(2000);
    status = await call('agent_status');
    if (status.running === false) break;
  }
  log(`Agent 结束：${status.phase} — ${status.detail}`);
  await progress(90, '手机巡检 · Agent 完成');

  // 3) 汇总：小屏显示，写进工作区
  const report = [...facts, `Agent：${goal} → ${status.phase === 'done' ? '已完成' : status.phase}`].join('\n');
  await call('ACTION_MINI_SCREEN', { title: '手机巡检 · 完成', text: report, progress: 100 });
  const saved = await call('ACTION_WORKSPACE', { action: 'write', name: '手机巡检报告', content: report, tags: '巡检', overwrite: false });
  log(`报告已写入手机工作区（id ${saved.workspace_id}）`);
  ws.close();
};
ws.onerror = () => {
  console.error('连接失败：服务没起？adb forward？令牌？');
  process.exit(1);
};
