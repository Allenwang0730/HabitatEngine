#!/usr/bin/env node
// Habitat 自主模式真机评测：跑一组固定任务，用 adb / 读屏独立验证结果（不相信 agent 自己说的 DONE），跑完恢复设备状态。
//
// 用法（手机需解锁、亮屏，已 adb forward tcp:8931 tcp:8931，环境变量 AGENT_TOKEN）：
//   node scripts/agent_eval.mjs                                  # 默认：jev + deepseek，各自 有/无 规划，每项 1 次
//   node scripts/agent_eval.mjs --providers jev --planner on --repeat 2
//   node scripts/agent_eval.mjs --only about_phone,clock_stopwatch
//   JEV_PROXY_PORT=7897 node scripts/agent_eval.mjs   # 手机经 PC 代理访问 Jev：每个任务前恢复 adb reverse
// 结果写到 selftest_results/agent_eval_<时间>.json（目录已在 .gitignore）。
import { execFileSync } from 'node:child_process';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const argv = process.argv.slice(2);
const opt = (name, fallback) => {
  const i = argv.indexOf(`--${name}`);
  return i >= 0 && argv[i + 1] ? argv[i + 1] : fallback;
};
const providers = opt('providers', 'jev,deepseek').split(',');
const plannerModes = { on: [true], off: [false], both: [true, false] }[opt('planner', 'both')];
const repeat = Number(opt('repeat', '1'));
const only = opt('only', '') ? new Set(opt('only', '').split(',')) : null;
const maxSteps = Number(opt('steps', '20'));
const timeoutSec = Number(opt('timeout', '240'));

const ADB = process.env.ADB || join(process.env.LOCALAPPDATA || '', 'Android/Sdk/platform-tools/adb.exe');
const TOKEN = process.env.AGENT_TOKEN;
const URL_BASE = `ws://${process.env.AGENT_HOST || '127.0.0.1'}:${process.env.AGENT_PORT || '8931'}/mcp`;
if (!TOKEN) {
  console.error('缺少 AGENT_TOKEN');
  process.exit(2);
}

const tasks = JSON.parse(readFileSync(join(here, 'agent_eval_tasks.json'), 'utf8')).filter((t) => !only || only.has(t.id));

const sleepSync = (ms) => Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
const adb = (args) => execFileSync(ADB, args, { encoding: 'utf8', timeout: 20000 }).trim();

// USB 数据线会掉线（真机实测一次评测里掉好几次）：命令失败就等设备回来再重试，不把"读不到"当成验证失败
function waitDevice() {
  for (let i = 0; i < 40; i++) {
    try {
      if (/\tdevice$/m.test(adb(['devices']))) return true;
      adb(['reconnect', 'offline']);
    } catch {}
    sleepSync(3000);
  }
  return false;
}
// 手机经 PC 代理访问 Jev 时（Agent 页「Jev 代理」填 127.0.0.1:端口），重连后 adb reverse 也要恢复
const PROXY_PORT = process.env.JEV_PROXY_PORT;
function restoreTunnels() {
  try { adb(['forward', 'tcp:8931', 'tcp:8931']); } catch {}
  if (PROXY_PORT) try { adb(['reverse', `tcp:${PROXY_PORT}`, `tcp:${PROXY_PORT}`]); } catch {}
}
const sh = (cmd) => {
  for (let attempt = 0; attempt < 3; attempt++) {
    try {
      return adb(['shell', cmd]);
    } catch {
      if (!waitDevice()) break;
      restoreTunnels();
    }
  }
  return '';
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ---- MCP 客户端（一次连接复用） ----
let ws;
let nextId = 1;
const pending = new Map();
function connect() {
  return new Promise((resolve, reject) => {
    ws = new WebSocket(`${URL_BASE}?token=${encodeURIComponent(TOKEN)}`);
    ws.onopen = resolve;
    ws.onerror = () => reject(new Error('MCP 连接失败（服务没起？adb forward？令牌？）'));
    ws.onclose = () => { ws = null; };
    ws.onmessage = (ev) => {
      const msg = JSON.parse(ev.data);
      if (msg.id && pending.has(msg.id)) {
        pending.get(msg.id)(msg);
        pending.delete(msg.id);
      }
    };
  });
}
async function rpc(method, params) {
  for (let attempt = 0; attempt < 3; attempt++) {
    if (!ws || ws.readyState !== WebSocket.OPEN) {
      waitDevice();
      restoreTunnels();
      try { await connect(); } catch { await sleep(3000); continue; }
    }
    const id = nextId++;
    const reply = new Promise((resolve) => pending.set(id, resolve));
    ws.send(JSON.stringify({ jsonrpc: '2.0', id, method, params }));
    const timeout = new Promise((resolve) => setTimeout(() => resolve(null), 30000));
    const res = await Promise.race([reply, timeout]);
    if (res) return res;
    pending.delete(id);
    try { ws.close(); } catch {}
    ws = null;
  }
  return { result: { isError: true, content: [{ text: '{"error":"MCP 无响应"}' }] } };
}
async function call(name, args = {}) {
  const res = await rpc('tools/call', { name, arguments: args });
  const text = res.result?.content?.[0]?.text ?? '';
  let data;
  try {
    data = JSON.parse(text);
  } catch {
    data = { raw: text };
  }
  return { error: !!res.result?.isError, data };
}

function focus() {
  for (let i = 0; i < 3; i++) {
    const m = sh('dumpsys window | grep mCurrentFocus').match(/u0 ([^\s}]+)/);
    if (m) return m[1];
    sleepSync(1000);
  }
  return '';
}

async function screenText() {
  const r = await call('screen');
  return [r.data.screen_text || '', ...(r.data.windows || []).map((w) => w.text || '')].join('\n');
}

async function verify(task, status) {
  const v = task.verify;
  const reasons = [];
  if (v.focus && !new RegExp(v.focus).test(focus())) reasons.push(`前台 ${focus()} 不匹配 /${v.focus}/`);
  if (v.screen) {
    const text = await screenText();
    if (!new RegExp(v.screen).test(text)) reasons.push(`屏幕上没有 /${v.screen}/`);
  }
  if (v.shell) {
    const value = Number(sh(v.shell));
    if (!(value >= v.atLeast)) reasons.push(`${v.shell} = ${value}，期望 ≥ ${v.atLeast}`);
  }
  if (v.detailContains && !new RegExp(v.detailContains).test(status.detail || '')) {
    reasons.push(`结果「${status.detail}」不匹配 /${v.detailContains}/`);
  }
  if (v.detailMatchesShell) {
    const truth = (sh(v.detailMatchesShell).match(/(\d+)/) || [])[1];
    const said = ((status.detail || '').match(new RegExp(v.detailRegex)) || [])[1];
    if (!truth || said !== truth) reasons.push(`回答「${status.detail}」与实际电量 ${truth} 不符`);
  }
  if (v.detailContainsShell) {
    const truth = sh(v.detailContainsShell);
    if (!truth || !(status.detail || '').includes(truth)) reasons.push(`回答「${status.detail}」里没有实际值 ${truth}`);
  }
  return reasons;
}

async function runOne(task, provider, planner) {
  restoreTunnels();
  const saved = {};
  for (const [k, cmd] of Object.entries(task.save || {})) saved[k] = sh(cmd);
  sh('input keyevent HOME');
  for (const cmd of task.setup || []) sh(cmd);
  await sleep(1200);
  const started = Date.now();
  const start = await call('agent_start', { goal: task.goal, provider, max_steps: maxSteps, planner });
  let status = start.data;
  if (start.error) return { ok: false, status: 'start_error', detail: JSON.stringify(start.data), seconds: 0, reasons: ['启动失败'] };
  while (Date.now() - started < timeoutSec * 1000) {
    await sleep(3000);
    status = (await call('agent_status')).data;
    if (status.running === false) break;
  }
  if (status.running) await call('agent_stop');
  const seconds = Math.round((Date.now() - started) / 1000);
  // 结束那一刻界面可能还在转场：等稳定再验，失败再验一次，避免把"刚好在切换"误判成失败
  await sleep(1500);
  let reasons = await verify(task, status);
  if (reasons.length) {
    await sleep(2000);
    reasons = await verify(task, status);
  }
  for (const cmd of task.restore || []) sh(cmd.replace(/\{(\w+)\}/g, (_, k) => saved[k] ?? ''));
  const lines = String(status.log || '').split('\n');
  return {
    ok: reasons.length === 0,
    status: status.phase,
    detail: status.detail,
    seconds,
    steps: (String(status.log || '').match(/（(\d+) 步）/) || [])[1],
    reasons,
    log: lines,
  };
}

// 首次连接也走重试：USB 可能刚好掉线
await rpc('initialize', { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'habitat-eval', version: '1' } });
const results = [];
for (const provider of providers) {
  for (const planner of plannerModes) {
    for (const task of tasks) {
      for (let i = 0; i < repeat; i++) {
        const r = await runOne(task, provider, planner);
        results.push({ task: task.id, provider, planner, run: i + 1, ...r });
        const mark = r.ok ? 'PASS' : 'FAIL';
        console.log(`${mark}  ${provider.padEnd(8)} planner=${planner ? 'on ' : 'off'}  ${task.id.padEnd(18)} ${String(r.seconds).padStart(4)}s  ${r.status}  ${r.ok ? '' : r.reasons.join('；')}`);
      }
    }
  }
}
ws.close();

console.log('\n汇总（通过/总数）:');
for (const provider of providers) {
  for (const planner of plannerModes) {
    const rows = results.filter((r) => r.provider === provider && r.planner === planner);
    const pass = rows.filter((r) => r.ok).length;
    const secs = rows.filter((r) => r.ok).map((r) => r.seconds).sort((a, b) => a - b);
    const median = secs.length ? secs[Math.floor(secs.length / 2)] : '-';
    console.log(`  ${provider.padEnd(8)} planner=${planner ? 'on ' : 'off'}  ${pass}/${rows.length}  通过任务中位耗时 ${median}s`);
  }
}
const outDir = join(here, '..', 'selftest_results');
mkdirSync(outDir, { recursive: true });
const file = join(outDir, `agent_eval_${new Date().toISOString().replace(/[:.]/g, '-')}.json`);
writeFileSync(file, JSON.stringify(results, null, 2));
console.log(`\n明细: ${file}`);
