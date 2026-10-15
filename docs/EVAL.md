# Autonomous-mode evaluation

How Habitat's autonomous agent is evaluated on a real phone, and the raw results.

## Method

- **Device**: one vivo V2329A (PD2329), Android 16, Chinese locale. No other device has been evaluated.
- **Harness**: [`scripts/agent_eval.mjs`](../scripts/agent_eval.mjs) drives the app over its local MCP server (`adb forward tcp:8931`). Each task starts from the home screen; `setup` / `restore` commands reset device state (brightness, app processes, drafts).
- **Verification is independent of the agent**: a run passes only if `adb` / screen reads confirm the end state (foreground activity, text on screen, `settings get …`, `dumpsys battery`, `getprop`). The agent saying DONE counts for nothing.
- **Origin**: eval runs are *remote-origin* (started over MCP), so the safety rules for remote goals apply: messages are typed but not sent, calls only fill the dialer.
- **Models**: planner/reviewer = DeepSeek `deepseek-chat` (OpenAI-compatible API); executors = TypeSafe Jev `jev-latest`, DeepSeek `deepseek-chat`, or `local` (rule-based, no network).
- **Configs**: each executor with the planner on and off. "No planner" means the executor drives the whole goal by itself, with no review step.

Tasks ([`scripts/agent_eval_tasks.json`](../scripts/agent_eval_tasks.json)):

| id | goal | verified by |
|---|---|---|
| bluetooth_page | 打开设置，进入蓝牙页面 | foreground activity |
| settings_search | 打开设置，在搜索框输入「电池」并搜索 | result text on screen |
| about_phone | 打开设置，找到「关于手机」并进入 | foreground activity |
| clock_stopwatch | 打开时钟，切换到秒表页面 | activity + text |
| brightness_max | 把屏幕亮度调到最高 | `screen_brightness` ≥ 230 |
| battery_question | 现在电量是多少？ | answer == `dumpsys battery` level |
| android_version | 进入关于手机，告诉我安卓版本号 | answer contains `ro.build.version.release` |
| settings_search_nospace ¹ | 打开设置，在搜索框输入电池并搜索 | result text on screen |
| qq_my_computer ¹ | 打开QQ给我的电脑发消息说测试123 | QQ foreground, text typed in the chat input, result says "not sent" |
| call_10086 ¹ | 打电话给中国移动 | dialer foreground with 10086 filled in |
| battery_to_mini_screen ² | 查一下现在电量，显示在小屏上 | mini screen visible (read back via MCP `mini_screen_status`) and its text contains the `dumpsys battery` level |
| note_android_version ² | 看一下安卓版本号，记下来 | a new workspace note tagged `agent` contains `ro.build.version.release` |

¹ Added in the 2026-10-14 run. `qq_my_computer` needs QQ installed and logged in.
² Added for 1.10.0 (mini screen and workspace); not yet run on a device at the time of writing.

## Results

### 2026-10-14 — commit `9069155` (branch `feat/agent-intent`), 1 run per task

| Executor | Planner | No planner |
|---|:---:|:---:|
| DeepSeek | **10/10** | 7/10 |
| local (no network) | — | 5/10 |
| Jev | 7/10 ² | 1/10 ² |

² Jev was run **without a proxy**. From mainland China, TypeSafe returns HTTP 451 (region refused) for most requests: 5 of 10 runs with the planner and 9 of 10 without hit it. The Jev rows on this date measure the network, not the model. With the planner, some tasks still passed because the planner's device-tool steps or a fixed skill had already reached the verified state before Jev was refused (those runs end with status `error` but pass verification).

Raw data: [`eval/2026-10-14.json`](eval/2026-10-14.json).

Failures without the planner (DeepSeek): `clock_stopwatch` — the agent reported the stopwatch page, but at verification time a different app was in the foreground (cause not determined); `android_version` — the executor repeatedly picked a control that was not in the candidate list; `settings_search_nospace` — pressing Enter failed (likely: the search box exposes no IME-enter action and the `input keyevent` fallback needs Shizuku). `local` cannot answer question tasks, and failed `brightness_max` because the app lacked the "modify system settings" permission on this run.

### 2026-10-09 — commit `09b0632` (v1.9.0), 2 runs per task, 2 sessions

| Executor | Planner | No planner |
|---|:---:|:---:|
| Jev | **28/28** | 13/28 |
| DeepSeek | **28/28** | 18/28 |

Per session the no-planner results were Jev 7/14 and 6/14, DeepSeek 10/14 and 8/14. Without the planner, failures concentrate in question tasks (no review step reads the answer) and multi-step navigation. No Jev request was refused on this date.

Raw data: [`eval/2026-10-09.json`](eval/2026-10-09.json).

## What the raw files contain

Each file has `commit`, `note`, `summary` and one record per run: task, executor, planner on/off, run number, pass/fail, end status, seconds, steps, and whether Jev returned HTTP 451. Step logs and agent answers are **not** published: they contain screen text (chat lists, contact and group names, notifications). They are exported from the local `selftest_results/` with [`scripts/agent_eval_export.mjs`](../scripts/agent_eval_export.mjs).

## Limitations

- One device, one ROM, one locale. Other launchers/ROMs/apps will lay screens out differently; the fixes in this repo were driven by what this device showed.
- Ten short tasks. They cover settings navigation, in-app search, device toggles, questions, one messaging flow and one call — not long or open-ended goals.
- 1–2 runs per task per date; small differences between configs are noise.
- The QQ task only verifies "typed, not sent" (remote origin). Sending on local origin was not covered by the eval.
- Results depend on third-party services (DeepSeek, TypeSafe) whose models and availability change without notice.

## Data leaving the phone

- Planner on: the goal and screen text are sent to DeepSeek.
- Executor Jev: the goal and screen text are sent to TypeSafe; executor DeepSeek: to DeepSeek.
- Executor `local` with the planner off: nothing leaves the phone.
- Phone numbers are resolved on the phone (goal digits → contacts → built-in service numbers) and never put into a prompt.
- Run records (goal, steps, result) and notes are written to the on-device workspace (`filesDir/workspace`) and never uploaded. Step logs contain screen text; turn off "运行记录存到工作区" on the Agent page to stop recording.

## Reproduce

```bash
adb forward tcp:8931 tcp:8931
export AGENT_TOKEN=<token from the app's Agent page>
node scripts/agent_eval.mjs --providers deepseek --planner on          # one config
node scripts/agent_eval.mjs                                             # jev + deepseek × planner on/off
JEV_PROXY_PORT=7897 node scripts/agent_eval.mjs --providers jev         # Jev through a PC proxy (adb reverse)
node scripts/agent_eval_export.mjs selftest_results/agent_eval_*.json --out docs/eval/<date>.json --commit <sha>
```

The phone must be unlocked with the screen on. The app needs accessibility enabled, the Agent service switched on, and a DeepSeek key (for the planner / DeepSeek executor) or Jev key.
