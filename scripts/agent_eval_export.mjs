#!/usr/bin/env node
// 把 selftest_results/agent_eval_*.json 导出成可以公开的结果文件（docs/eval/）。
//
// 只保留结构化字段：任务、执行器、是否规划、第几次、是否通过、结束状态、耗时、步数，
// 以及是否遇到 Jev 的 HTTP 451（地区拒绝，属于网络原因而不是模型能力）。
// 步骤日志、结果文字、失败原因一律不导出：里面有屏幕文字（聊天列表、群名、文件名、通知）。
//
// 用法：node scripts/agent_eval_export.mjs <结果文件...> --out docs/eval/2026-10-14.json --commit 9069155 --note "…"
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { basename, dirname } from 'node:path';

const argv = process.argv.slice(2);
const opt = (name) => {
  const i = argv.indexOf(`--${name}`);
  return i >= 0 ? argv.splice(i, 2)[1] : undefined;
};
const out = opt('out');
const commit = opt('commit') || '';
const note = opt('note') || '';
const files = argv;
if (!out || files.length === 0) {
  console.error('用法: node scripts/agent_eval_export.mjs <结果文件...> --out <文件> [--commit <sha>] [--note <说明>]');
  process.exit(2);
}

const runs = [];
const sources = [];
for (const file of files) {
  const rows = JSON.parse(readFileSync(file, 'utf8'));
  // 文件名里的时间就是评测开始时间（agent_eval_2026-10-14T09-37-18-787Z.json）
  const started = basename(file).replace(/^agent_eval_|\.json$/g, '').replace(/T(\d+)-(\d+)-(\d+)-(\d+)Z/, 'T$1:$2:$3.$4Z');
  sources.push(started);
  for (const r of rows) {
    const text = `${r.detail || ''}\n${(r.log || []).join('\n')}`;
    runs.push({
      started,
      task: r.task,
      provider: r.provider,
      planner: !!r.planner,
      run: r.run,
      ok: !!r.ok,
      status: r.status,
      seconds: r.seconds,
      steps: r.steps ? Number(r.steps) : null,
      jev_http_451: r.provider === 'jev' && /HTTP 451/.test(text),
    });
  }
}

const summary = {};
for (const r of runs) {
  const key = `${r.provider}/${r.planner ? 'planner' : 'no-planner'}`;
  const s = (summary[key] ||= { passed: 0, total: 0, blocked_by_451: 0 });
  s.total++;
  if (r.ok) s.passed++;
  if (r.jev_http_451) s.blocked_by_451++;
}

mkdirSync(dirname(out), { recursive: true });
writeFileSync(out, JSON.stringify({ commit, note, sources, summary, runs }, null, 2) + '\n');
console.log(`${out}: ${runs.length} runs`);
for (const [k, s] of Object.entries(summary)) console.log(`  ${k.padEnd(22)} ${s.passed}/${s.total}${s.blocked_by_451 ? `  (451: ${s.blocked_by_451})` : ''}`);
