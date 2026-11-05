# Habitat on AndroidWorld

[AndroidWorld](https://github.com/google-research/android_world) (Google, Apache-2.0) has 116 task templates over 20 apps.
Each task has its own code that sets up the emulator and checks the end state; the agent saying "done" proves
nothing, though under AndroidWorld's rule a task only counts if the agent also reported done. `run_habitat.py`
plugs Habitat in as a black box:

1. AndroidWorld initializes a task and gives the goal (English).
2. The adapter calls Habitat's MCP `agent_start(goal, …)` and polls `agent_status` until it stops.
3. AndroidWorld's `is_successful()` scores the device state. For information-retrieval tasks the final
   answer (`agent_status.answer`) is put into `env.interaction_cache`, where AndroidWorld reads answers.

The DeepSeek key stays on the host. The app gets a placeholder key and a base URL pointing at a small
forwarder on the host (`10.0.2.2`), which adds the real key. This also keeps TLS off the device, whose
clock AndroidWorld freezes to October 2023.

## Setup (WSL2 or Linux with KVM)

- Android SDK with `emulator`, `platform-tools`, `system-images;android-33;google_apis;x86_64`, and an AVD
  named `AndroidWorldAvd` (Pixel 6), as in the AndroidWorld README.
- AndroidWorld installed in a Python 3.11 venv (`pip install -r requirements.txt && pip install -e .`).
- Start the emulator with gRPC, then run AndroidWorld's one-time setup once:
  ```bash
  emulator -avd AndroidWorldAvd -no-snapshot -grpc 8554 &
  python run.py --perform_emulator_setup --suite_family=android_world --n_task_combinations=0
  ```
- Build a debug APK (`./gradlew :app:assembleStandardDebug`); the x86_64 split is
  `app/build/outputs/apk/standard/debug/app-standard-x86_64-debug.apk`. The adapter configures the app through
  `NodeSelfTestActivity` (`eval_setup`), which only works on debug builds and only from `adb shell`.

## Run

```bash
export DEEPSEEK_API_KEY=...   # host only
python scripts/androidworld/run_habitat.py --apk app-standard-x86_64-debug.apk \
    --tasks @scripts/androidworld/smoke25.txt --seed 30 --out results.jsonl
```

`smoke25.txt` is a first batch of 25 short tasks with no information retrieval and no sending (Clock, Contacts,
Expense, Markor, Recipe, Simple Calendar, system toggles). Each result line has the task, goal, score, Habitat's
end phase and answer, and seconds.

Options used for the full-suite runs in [`docs/EVAL.md`](../../docs/EVAL.md):

- `--seed_mode official` (default): each task's parameters come from AndroidWorld's own per-task seed
  (`sha256("<seed>_<task>_0")`, as `create_suite` does), so the order of `--tasks` and how the list is split across
  emulators do not change the goals. `positional` reproduces runs from before 2026-11-05 (seed + index).
- `--budget official|lenient`: `int(10 × complexity)` steps as in AndroidWorld's runner, or 3× that (at least 30).
  Every row records `score` (device state) and `score_official` (device state *and* Habitat reported done, which is
  AndroidWorld's rule).
- `--eval_local`: debug-build eval switch, see "keep in mind" below.
- `--upstream truesota --exec_model … --plan_model …`: send the app's model calls to another OpenAI-compatible
  gateway (key in `TS_API_KEY`) and swap model names there; `--exec_fallback` / `--plan_fallback` retry on a backup
  model when the gateway reports one unavailable.
- `--vision all`: screenshots go with every model call.
- Several emulators: give each its own `--console_port`, `--grpc_port`, `--mcp_port`, `--forwarder_port`
  and its own netsimd (`-netsim-args --instance=N`), otherwise shutting one down takes the others with it.

Failed tasks also get an end-of-task screenshot next to their step log (`<out>_logs/<n>_<task>.png`).

Things in the host environment that showed up as agent failures:

- An `HTTP_PROXY` in the shell with `NO_PROXY=127.*`: urllib does not expand that wildcard, so local MCP calls went
  through the proxy. The adapter now never uses a proxy for MCP.
- Running `adb` from a second environment (e.g. Windows next to WSL) restarts the adb server and cuts every
  emulator's port forwards. Don't, while a run is going.
- OsmAnd's database only exists after the app has run once; Simple Gallery, VLC and Markor need all-files access or
  they show nothing / a permission screen. The adapter handles both.

## What to keep in mind when reading the score

- Goals are English. Habitat's rule-based intent parser is Chinese, so this measures the planner + executor path.
- By default remote-origin safety rules apply (the adapter starts goals over MCP): messages are typed but never sent,
  calls only fill the dialer, delete/payment/permission buttons are never tapped. Tasks that require sending or
  deleting fail by design; `smoke25.txt` leaves them out, the full suite does not.
- `--eval_local` turns on the app's eval switch (debug build only, set over `adb shell`, expires after 12 hours):
  a remote goal is then handled like one typed on the phone (a sensitive button unlocks when the goal names it), and
  text typed into an app may come from the screen. The full-suite numbers in `docs/EVAL.md` use it; say so when
  quoting them.
- Published numbers to compare with: AndroidWorld paper T3A (accessibility tree) 30.6%; most leaderboard entries
  use screenshots, and the leaderboard is self-reported.
