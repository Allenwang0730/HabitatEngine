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
² Added in 1.10.0 (mini screen and workspace).

## Results

### 2026-10-16 (full) — commit `d6b49cd` (1.10.0), all configs, 1 run per task, Jev via proxy

| Executor | Planner | No planner |
|---|:---:|:---:|
| Jev | **12/12** | 9/12 |
| DeepSeek | **12/12** | 11/12 |

12 tasks (the 10 earlier ones + `battery_to_mini_screen` + `note_android_version`). Jev was reached through a proxy on the phone; no request was refused. Failures without the planner: `android_version` (both executors) and `battery_to_mini_screen` (Jev) reached the right page but ended with "完成" and no value — without the planner there is no review step to read the answer off the screen; Jev `about_phone` tapped the 「关于手机」 row repeatedly without the page opening and was stopped by loop detection.

Raw data: [`eval/2026-10-16-full.json`](eval/2026-10-16-full.json).

### 2026-10-16 — commit `edf0dc4` (branch `feat/mini-screen-workspace`, 1.10.0), DeepSeek + planner, 1 run per task

| Session | Result |
|---|:---:|
| New output tasks (`battery_to_mini_screen`, `note_android_version`) | **2/2** |
| The 10 earlier tasks with a mini screen kept on screen over the Settings list (`--overlay`) | **10/10** |

The planner chose `read_battery → show_on_screen` and `read_device_info → save_note`, with `{battery_level}` / `{device_android_version}` filled from the tool readings. Only DeepSeek was run on this date (no Jev proxy). Gesture avoidance was also checked by hand: with the mini screen over the Settings "账号" row, `ACTION_CLICK` at that point moved the panel to the top and opened the account page; a raw `adb input tap` at the same point (which bypasses the hook) was swallowed by the panel, as expected.

Raw data: [`eval/2026-10-16.json`](eval/2026-10-16.json).

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

## AndroidWorld (public benchmark, emulator)

[AndroidWorld](https://github.com/google-research/android_world) (Google, Apache-2.0): 116 task templates over 20 apps on an Android 13 emulator. Each task's own code sets up the device and checks the end state; the agent saying "done" proves nothing, but under AndroidWorld's rule a task only counts if the agent also reported done. Habitat runs as a black box through [`scripts/androidworld/run_habitat.py`](../scripts/androidworld/README.md): AndroidWorld gives the goal, the adapter starts it over MCP, AndroidWorld scores the device state.

### 2026-11-06 — full 116 tasks, official step budget, `f1033a0`

| Metric | Result |
|---|:---:|
| Reported done **and** device state correct (AndroidWorld's own rule) | **83/116 (71.6%)** |
| Device state correct (ignoring whether it reported done) | 88/116 (75.9%) |
| Same build, 3× step budget (at least 30), AndroidWorld's rule | 87/116 (75.0%) |

The 3× run is not comparable with official-budget numbers; it is there because many leaderboard entries use 150 steps or no limit. Three of its tasks failed in AndroidWorld's own setup (Pro Expense onboarding: `Target text "NEXT" not found`) and count as failures.

Same build and settings on other seeds (each seed gives every task different parameters):

| Seed | AndroidWorld's rule | Device state correct |
|---|:---:|:---:|
| 30 | 83/116 (71.6%) | 88/116 |
| 31 | 84/116 (72.4%) | 87/116 |
| 32 | 41/55 so far (run interrupted, not finished) | — |
| **30 + 31** | **83.5/116 (72.0%)** | 87.5/116 |

The agent code these numbers come from is `f1033a0`'s (later changes were measured worse on a matching subset and reverted in `0f205f2`). The branch has since moved on: `231166f` adds a list-row fallback (below, under MobileWorld) that has only been measured on subsets. An earlier build the same night (`fcade18`) scored 75/116 (64.7%) / 83/116 on the same tasks and seeds. The changes in between: the executor writes straight into the input box a step names instead of tapping it first; the final review, when no retry is left, passes unless it sees evidence of a miss; the reviewer sees the text that was typed; and a regression where Markor files were saved as `a.md.md` was fixed.

Setup: seed 30 with AndroidWorld's per-task seeding (`sha256("30_<task>_0")`, same goals as `create_suite(seed=30)`), pass@1, step budget `int(10 × complexity)` as in AndroidWorld's runner (one step kept for the final answer). Three read-only emulators, each running a third of the tasks.
Models, through a gateway: executor `claude-sonnet-5-5` (fallback `qwen3.8-max`); planner / reviewer / reflection / step check `claude-opus-5-5` (fallback `claude-sonnet-5-5`). Screenshots go to every model call (`vision=all`); the accessibility tree is the main input and the executor still picks from code-built candidates (plus `TAP_POINT` / `SWIPE_POINT` on screenshot coordinates, gated the same way).

**Eval mode, not the default product behavior.** AndroidWorld's goals arrive over MCP, which Habitat treats as remote-origin: remote goals never delete, send or grant. This run turns on the eval switch (debug build, adb only, time-limited), under which a remote goal is treated like one the user typed on the phone, and text typed into an app may come from the screen (needed for cross-app tasks). A normal install (eval switch off) keeps the remote-origin rules. The adapter also grants all-files access to Markor, Simple Gallery and VLC before the run, as a user would on first launch.

Failures (33), by how the run ended: out of steps 20 (the three browser tasks, file manager, Broccoli recipes read from an image or a Markor file, several Markor edits, Bluetooth on); reported done but the check failed 8 (e.g. Bluetooth off: the emulator's setting stayed on while the UI said off; an event set to 17:00 for "5h"); the executor kept waiting 2; loop 1; unparseable reply 1; gateway error 1. By app (official rule): Simple Calendar 15/17, Simple SMS 6/6, OpenTracks 5/6, brightness and Wi-Fi 8/8, Bluetooth 1/4, Pro Expense add 3/4 and delete 4/5, Broccoli delete 5/8, Broccoli add 2/5, Markor create 2/4, OsmAnd 1/3.

Where this sits: on the [leaderboard](https://docs.google.com/spreadsheets/d/1cchzP9dlTZ3WXQTfYNhh3avxoLipqHN75v1Tb86uhHo) as of 2026-11-06 (snapshot in [`eval/androidworld/leaderboard-2026-11-06.csv`](eval/androidworld/leaderboard-2026-11-06.csv)), 23 of the 47 agent entries report more than 71.6%, five of them 100% (the human baseline is 80.0%), so 71.6% would rank about 24th. The leaderboard is self-reported with no verification and has no step-budget column; most top entries use screenshots with newer models, and some say they trained on AndroidWorld tasks. Accessibility-tree-only entries: V-Droid 59.5%, AndroidGen 46.8%, the AndroidWorld paper's T3A 30.6%. Habitat's own baseline on the 116 task templates a day earlier (DeepSeek, no screenshots, remote-origin rules, older per-position seeding) was 17/116.

Raw data: [`eval/androidworld/2026-11-06-full116-v14-official-s30.jsonl`](eval/androidworld/2026-11-06-full116-v14-official-s30.jsonl), seed 31 [`…-v14-official-s31.jsonl`](eval/androidworld/2026-11-06-full116-v14-official-s31.jsonl), the 3× budget run [`…-v14-lenient-s30.jsonl`](eval/androidworld/2026-11-06-full116-v14-lenient-s30.jsonl), and the earlier build's [`…-v12-official-s30.jsonl`](eval/androidworld/2026-11-06-full116-v12-official-s30.jsonl) (task, seed, goal, both scores, end phase, final detail and answer, seconds, step budget).

### 2026-11-04 — branch `feat/ocr-open-url` at `ed6116b`, 25-task subset, 3 seeds

DeepSeek executor + DeepSeek planner, max 30 steps, remote origin. Emulator: `AndroidWorldAvd` (Pixel 6, Android 13 `google_apis` x86_64) under WSL2.

| Seed | Passed | Mean seconds per task |
|---|:---:|:---:|
| 30 | 14/25 | 32 |
| 31 | 12/25 | 29 |
| 32 | 11/25 | 31 |
| **Mean** | **12.3/25 (49%)** | |

The subset ([`smoke25.txt`](../scripts/androidworld/smoke25.txt)) is the 25 shortest tasks (complexity 1–1.2) that need no answer and no sending: Clock, Contacts, Expense, Markor, Broccoli recipes, Simple Calendar, Bluetooth / Wi-Fi / brightness / clipboard. Seeds 30–32 give each task different parameters. Seed 32 was interrupted after 8 tasks (a host-side time limit) and resumed at task 9 with the same per-task seeds. No DeepSeek request failed on the network in these three runs.

Per task over the 3 seeds: passed 3/3 — both Contacts tasks, both stopwatch tasks, Markor create folder, Expense delete single, Calendar delete one event, Bluetooth on, Wi-Fi on and off; 2/3 — Bluetooth off, Open app; 1/3 — Expense add, Expense delete duplicates, Calendar delete events on a relative day; 0/3 — Clock timer entry, all four other Markor tasks, both Broccoli tasks, brightness max and min, copy to clipboard.

Main failure causes, from the step logs:

- **Picks outside the candidate list (most Broccoli, clipboard and timer failures).** The model picks a TAP or LONG_PRESS index that the code did not offer, gets "not a candidate", picks it again, and the step is replanned or ends. Long-press candidates are capped at 8 per screen and taps at 30, so on long lists the row the model wants can be left out.
- **Multi-line text editing (Markor edit / add header).** The agent alternates "clear field" and "type text" until the step budget runs out; the typed text contains line breaks.
- **Repeated long-press on a list row (Markor delete).** The agent long-presses the right row, the selection mode appears, and it long-presses again instead of choosing the delete action; loop detection then ends the step.
- **Sliders (brightness max / min).** The "open display settings" shortcut did not land on the expected page, and the brightness row on the page it reached was not exposed as a slider, so SET_SLIDER was never offered.
- **Timer entry.** The agent taps the number pad but the entered digits do not register as a change, so it repeats digits and backspace until the step limit.

Earlier runs on the same day with seed 30, before two fixes in `ed6116b`, passed 9/25 and 10/25. The fixes: icon buttons with no text (only a resource-id, e.g. Expense's add button) were left out of the tap candidates, so the model picked them from the element list and got "not a candidate"; and an "open this settings page" shortcut kept being offered after it had already succeeded without changing the screen. The adapter also started granting Markor's storage permission before runs, since remote-origin goals never tap permission buttons. The 9/25 → 12.3/25 change mixes these three effects with seed-to-seed noise (11–14 across seeds).

How to read this:

- It is a subset, not the full benchmark. The full 116 tasks include information retrieval and SMS sending; remote-origin safety rules (messages are typed, never sent) make the sending tasks fail by design.
- Goals are English. Habitat's rule-based intent parser is Chinese, so this measures the planner + executor path only.
- Text-only agents on the full AndroidWorld set, for scale: T3A (GPT-4 Turbo, accessibility tree) 30.6% in the [AndroidWorld paper](https://arxiv.org/abs/2405.14573); human 80.0%. Those numbers are over all 116 tasks and are not directly comparable with a 25-task subset.

Raw data: [`eval/androidworld/`](eval/androidworld/) (task, seed, goal, score, end phase, Habitat's final detail, seconds). Step logs are not published (they contain screen text).

## MobileWorld (public benchmark, emulator)

[MobileWorld](https://github.com/Tongyi-MAI/MobileWorld) (Tongyi-MAI): 201 tasks on a rooted Pixel 8 / Android 14 emulator in Docker, with its own Mail, Mastodon, Mattermost and shop backends; 117 are GUI-only (no `ask_user`, no MCP tools), the leaderboard's GUI-only column, which uses a 50-step budget. Habitat runs as a black box through [`scripts/mobileworld/habitat_agent.py`](../scripts/mobileworld/README.md): the whole task runs inside the first `predict()`, then MobileWorld checks the device and backend state. Question tasks are checked strictly (for example `int(answer)`).

### 2026-11-06 — first baseline, not finished

`f1033a0`, not tuned on MobileWorld, same models and eval switch as the AndroidWorld run, 50 device actions per task. The run was interrupted twice when the WSL VM stopped and is resumable; **15 of 117 tasks are scored so far: 4 passed.** MobileWorld runs tasks grouped by app, so these 15 are not a random sample (most are Files / document-reader tasks) and the rate says little about the full set.

Failures so far: 7 of 11 are file-manager tasks (open a PDF or zip and answer, copy or rename files); then Mastodon ×2, SMS clean-up (deleted some spam, not all), and a mail-with-attachment task.

**Root cause shared with AndroidWorld.** In the file manager (DocumentsUI), Habitat's accessibility gestures on list rows do nothing: tapping a folder or file (by coordinates or by text) does not open it, and long-pressing does not select it. On the same row, `adb input tap` and a node `ACTION_CLICK` both work (checked on a spare AndroidWorld emulator). In AndroidWorld's two seeds, 14 tasks show "tapped a list row, screen did not change", including both Files tasks and all three Browser tasks (they open `task.html` from Files).

`231166f` adds a narrow fallback: only for list rows, when a tap or long press reports success but the screen does not change, the same point gets the node action once (one extra step). It is not applied to buttons, input fields or screenshot-coordinate taps, does not switch a whole app to node clicks, and only looks in the topmost app window — an earlier, broader version (`e315e4b`) closed a Markor dialog and was reverted. Partial check, not a score: on 15 of 20 AndroidWorld validation tasks (seed 30), the tasks where the fallback fired went FilesDeleteFile 0→1, FilesMoveFile right end state but out of steps, BrowserMaze now opens the page; three tasks changed without it firing (MarkorMergeNotes 1→0, the control RetroPlayingQueue 1→0, SimpleCalendarLocationOfEvent 0→1), which is run-to-run noise rather than this change; the other two controls passed. On MobileWorld's CheckInvoiceTask3 it now opens the invoice PDF and sends the SMS, but with a wrong number.

## What the raw files contain

Each file has `commit`, `note`, `summary` and one record per run: task, executor, planner on/off, run number, pass/fail, end status, seconds, steps, and whether Jev returned HTTP 451. Step logs and agent answers are **not** published: they contain screen text (chat lists, contact and group names, notifications). They are exported from the local `selftest_results/` with [`scripts/agent_eval_export.mjs`](../scripts/agent_eval_export.mjs).

## Limitations

- One device, one ROM, one locale. Other launchers/ROMs/apps will lay screens out differently; the fixes in this repo were driven by what this device showed.
- Twelve short tasks. They cover settings navigation, in-app search, device toggles, questions, one messaging flow, one call, and writing to the mini screen and workspace — not long or open-ended goals.
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
