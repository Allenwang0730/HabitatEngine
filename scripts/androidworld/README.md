# Habitat on AndroidWorld

[AndroidWorld](https://github.com/google-research/android_world) (Google, Apache-2.0) has 116 task templates over 20 apps.
Each task has its own code that sets up the emulator and checks the end state, so the agent's own
"done" counts for nothing. `run_habitat.py` plugs Habitat in as a black box:

1. AndroidWorld initializes a task and gives the goal (English).
2. The adapter calls Habitat's MCP `agent_start(goal, …)` and polls `agent_status` until it stops.
3. AndroidWorld's `is_successful()` scores the device state. For information-retrieval tasks the final
   answer (`agent_status.detail`) is put into `env.interaction_cache`, where AndroidWorld reads answers.

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

## What to keep in mind when reading the score

- Goals are English. Habitat's rule-based intent parser is Chinese, so this measures the planner + executor path.
- Remote-origin safety rules apply (the adapter starts goals over MCP): messages are typed but never sent, calls
  only fill the dialer, payment/permission buttons are never tapped. Tasks that require sending an SMS fail by
  design; `smoke25.txt` leaves them out, the full suite does not.
- Comparable published numbers are for accessibility-tree / text-only agents (AndroidWorld paper T3A: 30.6%);
  most leaderboard entries use screenshots.
