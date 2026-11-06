# Habitat on MobileWorld

[MobileWorld](https://github.com/Tongyi-MAI/MobileWorld) (Tongyi-MAI) has 201 tasks on a rooted Pixel 8 / API 34
emulator inside Docker; 117 of them are GUI-only (no `ask_user`, no MCP tools), which is the leaderboard's
"GUI-only" column. Its leaderboard budget is 50 steps per task.

`habitat_agent.py` is a black-box agent for `mw eval --agent_type <file>`. Habitat is an on-device agent
(accessibility service + planner/executor inside the app), so instead of returning one action per screenshot it
runs the whole task in the first `predict()`:

1. MobileWorld loads the task's `init_state` snapshot (this wipes any installed app and accessibility setting).
2. `predict()` installs the APK over the container's adb relay, enables Habitat's accessibility service, starts its
   MCP server, calls `agent_start(goal)` with a 50-action budget, and polls until the agent stops.
3. It returns `answer` with Habitat's answer text (question tasks read it from `controller.interaction_cache`; the
   checks are strict, for example `int(answer)`), or `finished`.
4. MobileWorld scores the device / backend state as usual.

Setup failures before the agent starts (adb, install, MCP server) raise, so MobileWorld retries the task
(`--auto-retry`) instead of scoring a run in which Habitat never started. The prediction text of the single step
holds a JSON summary with the last part of Habitat's log; MobileWorld's own trajectory has only that one step.

## Image

The published `ghcr.io/tongyi-mai/mobile_world:latest` is v1.0: no `socat`, no adb relay on container port 5556,
and older task code than the MobileWorld checkout. `build_image.sh` applies the same changes as MobileWorld's
`docker/Dockerfile.update` (v1.2) in fewer layers (`Dockerfile.mw`; each layer on the 31 GB image takes about
10 minutes to commit with the classic builder):

```bash
HTTP_PROXY=http://127.0.0.1:7897 bash build_image.sh ~/mw/MobileWorld      # → mobile_world:v1.2-local
```

## Ports and network

- `mw env run` publishes every port with plain `-p host:container`, which binds 0.0.0.0. Port 5556 is a root adb
  shell into the emulator, and with WSL's mirrored networking 0.0.0.0 can be the LAN. Set Docker's default bind
  address before launching: `/etc/docker/daemon.json` = `{"ip": "127.0.0.1"}`, then restart dockerd.
- Move the adb port off AndroidWorld's emulator range (5554–5585): `--adb-start-port 15600`.
- Inside the container the emulator's 10.0.2.2 is the container, not the host. The agent starts a socat relay in the
  container (127.0.0.1:18090 → docker bridge gateway:18090) to the model forwarder, which binds the bridge address
  only and adds the gateway key. The device has a placeholder key.
- The app's MCP server is reached with `adb forward` on host ports 18930, 18931, … (one per container).

## Run

```bash
cd ~/mw/MobileWorld
.venv/bin/mw env run --count 1 --adb-start-port 15600 --image mobile_world:v1.2-local
HABITAT_APK=~/aw/apks/habitat_v14_f1033a0.apk TS_API_KEY="$(head -1 ~/.ts_key)" \
  .venv/bin/mw eval --agent_type /path/to/Habitat/scripts/mobileworld/habitat_agent.py \
    --model_name habitat --llm_base_url none --max_round 50 --log_file_root traj_logs/<run>
```

Without `--task` MobileWorld runs the GUI-only tasks. Pass `--max_round` explicitly (it decides when the loop stops
for agents that take one action per step; Habitat's own budget is `HABITAT_STEPS`). Other settings are environment
variables, listed at the top of `habitat_agent.py`.
