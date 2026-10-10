#!/usr/bin/env node
// 查看 agent_eval 结果明细：node scripts/agent_eval_show.mjs [结果文件]（默认最新一个）[--fail]
import { readdirSync, readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
const dir = join(dirname(fileURLToPath(import.meta.url)), '..', 'selftest_results');
const args = process.argv.slice(2);
const failOnly = args.includes('--fail');
const file = args.find((a) => !a.startsWith('--')) || join(dir, readdirSync(dir).filter((f) => f.startsWith('agent_eval_')).sort().pop());
for (const r of JSON.parse(readFileSync(file, 'utf8'))) {
  if (failOnly && r.ok) continue;
  console.log(`\n=== ${r.ok ? 'PASS' : 'FAIL'} ${r.provider} planner=${r.planner} ${r.task} #${r.run} ${r.seconds}s ${r.status}: ${r.detail}`);
  if (!r.ok) console.log(`    原因: ${r.reasons.join('；')}`);
  for (const line of r.log || []) console.log(`    ${line}`);
}
