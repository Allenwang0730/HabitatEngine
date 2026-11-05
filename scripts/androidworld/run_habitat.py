"""Run Habitat's autonomous agent on AndroidWorld tasks and score them with AndroidWorld's own checks.

Habitat drives the device by itself (accessibility service + on-device planner/executor), so it is
plugged in as a black box: AndroidWorld initializes a task and gives the goal, the adapter hands the
goal to Habitat over MCP (agent_start, then polls agent_status), and AndroidWorld's task code judges
the resulting device state (`is_successful`). For information-retrieval tasks Habitat's final answer
(agent_status.detail) is copied into `env.interaction_cache`, which is where AndroidWorld reads answers.

The DeepSeek key never goes to the emulator: the app is configured with a placeholder key and a base
URL pointing at a local forwarder (10.0.2.2 = host loopback), which adds the real key from
DEEPSEEK_API_KEY. This also avoids TLS on the device, whose clock AndroidWorld freezes to October 2023.

Usage (inside WSL, with the AndroidWorld emulator running and set up once with
`python run.py --perform_emulator_setup`):

  export DEEPSEEK_API_KEY=...            # host only
  python scripts/androidworld/run_habitat.py --apk app-standard-x86_64-debug.apk \
      --tasks ContactsAddContact,ClockStopWatchRunning --seed 30 --out results.jsonl
"""

from __future__ import annotations

import argparse
import hashlib
import http.server
import json
import os
import secrets
import subprocess
import threading
import time
import urllib.error
import urllib.request

PKG = "com.ailun.habitat"
A11Y_SERVICE = f"{PKG}/{PKG}.app.HabitatAccessibilityService"
SELFTEST = f"{PKG}/.app.NodeSelfTestActivity"
DEEPSEEK_URL = "https://api.deepseek.com/chat/completions"
JEV_URL = "https://api.typesafe.ai/v1/systemone"
TRUESOTA_URL = "https://true-sota.com/v1/chat/completions"
# forwarder path -> (key name, upstream URL). The app is pointed at http://10.0.2.2:<port><path>.
ROUTES = {"/chat/completions": ("deepseek", DEEPSEEK_URL), "/v1/systemone": ("jev", JEV_URL)}


# --------------------------------------------------------------------------- model forwarder
class _Forwarder(http.server.BaseHTTPRequestHandler):
    keys: dict[str, str] = {}
    # Optional: send DeepSeek-route calls to another OpenAI-compatible gateway and swap model names there.
    # The app asks for "deepseek-chat" (executor) or its planner_model (planner/reviewer/reflection);
    # model_map turns those into the real model names, so the APK does not change.
    upstream_url: str = ""
    upstream_key_name: str = "deepseek"
    model_map: dict[str, str] = {}
    fallback_map: dict[str, str] = {}  # real model -> backup model when the gateway says it is unavailable
    network_errors = 0  # requests that still failed after retries; reported per task
    usage = {"deepseek_calls": 0, "prompt_tokens": 0, "completion_tokens": 0, "cache_hit_tokens": 0, "jev_calls": 0}
    lock = threading.Lock()

    def do_POST(self):  # noqa: N802 (http.server API)
        route = ROUTES.get(self.path)
        if route is None or not self.keys.get(route[0]):
            self.send_error(404)
            return
        name, url = route
        body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
        key_name = name
        req_model = ""
        if name == "deepseek" and self.upstream_url:
            url, key_name = self.upstream_url, self.upstream_key_name
            if self.model_map:
                try:
                    req_json = json.loads(body)
                    asked = req_json.get("model", "")
                    if asked in self.model_map:
                        req_json["model"] = self.model_map[asked]
                        req_model = req_json["model"]
                        # thinking models reject a low temperature on some gateways; let the gateway default it
                        req_json.pop("temperature", None)
                        body = json.dumps(req_json).encode()
                except ValueError:
                    pass
        req = urllib.request.Request(
            url,
            data=body,
            headers={"Content-Type": "application/json", "Authorization": f"Bearer {self.keys[key_name]}"},
            method="POST",
        )
        # Network errors through a local proxy (TLS EOF, resets) are transient: retry them here so a flaky
        # link does not show up as an agent failure. HTTP errors from DeepSeek itself are passed through.
        for attempt in range(4):
            try:
                with urllib.request.urlopen(req, timeout=120) as resp:
                    status, data = resp.status, resp.read()
                break
            except urllib.error.HTTPError as e:
                status, data = e.code, e.read()
                if e.code not in (429, 500, 502, 503) or attempt == 3:
                    break
            except Exception as e:  # network error: report as 502 after the last attempt
                status, data = 502, json.dumps({"error": str(e)}).encode()
                if attempt == 3:
                    break
            time.sleep(2 * (attempt + 1))
        # 网关上某个模型整体不可用（503 没有可用账号、404、400 上游锁定）：换备用模型再试一次，而不是让整轮评测作废
        # （2026-11-05 实测 gpt-6-luna 跑到一半开始 503/404，51 个任务因此报错）
        fb = self.fallback_map.get(req_model) if req_model else None
        if fb and status in (400, 404, 429, 500, 502, 503):
            try:
                req_json = json.loads(body)
                req_json["model"] = fb
                req2 = urllib.request.Request(url, data=json.dumps(req_json).encode(), headers=req.headers, method="POST")
                with urllib.request.urlopen(req2, timeout=120) as resp:
                    status, data = resp.status, resp.read()
                with _Forwarder.lock:
                    _Forwarder.usage["fallbacks"] = _Forwarder.usage.get("fallbacks", 0) + 1
            except urllib.error.HTTPError as e:
                status, data = e.code, e.read()
            except Exception as e:
                status, data = 502, json.dumps({"error": str(e)}).encode()
        with _Forwarder.lock:
            if status == 502:
                _Forwarder.network_errors += 1
            _Forwarder.count(name, status, data)
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    @staticmethod
    def count(name: str, status: int, data: bytes) -> None:
        """Token usage from DeepSeek's `usage` field (for cost); Jev only counts calls."""
        if status != 200:
            return
        if name == "jev":
            _Forwarder.usage["jev_calls"] += 1
            return
        _Forwarder.usage["deepseek_calls"] += 1
        try:
            u = json.loads(data).get("usage") or {}
        except ValueError:
            return
        _Forwarder.usage["prompt_tokens"] += int(u.get("prompt_tokens", 0))
        _Forwarder.usage["completion_tokens"] += int(u.get("completion_tokens", 0))
        _Forwarder.usage["cache_hit_tokens"] += int(u.get("prompt_cache_hit_tokens", 0))

    def log_message(self, *args):  # keep the console quiet; requests contain screen text
        pass


def start_forwarder(port: int, keys: dict[str, str]) -> http.server.ThreadingHTTPServer:
    _Forwarder.keys = {k: v for k, v in keys.items() if v}

    class Server(http.server.ThreadingHTTPServer):
        # default listen backlog is 5: with the thinking planner, several slow calls can be in flight and a new
        # connection was refused once ("Failed to connect to /10.0.2.2:18081")
        request_queue_size = 64

    server = Server(("127.0.0.1", port), _Forwarder)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server


# --------------------------------------------------------------------------- device setup
class Adb:
    def __init__(self, adb: str, serial: str):
        self.base = [adb, "-s", serial]

    def run(self, *args: str, check: bool = True) -> str:
        out = subprocess.run(self.base + list(args), capture_output=True, text=True, timeout=300)
        if check and out.returncode != 0:
            raise RuntimeError(f"adb {' '.join(args[:3])} failed: {out.stderr.strip()[:300]}")
        return out.stdout.strip()

    def shell(self, cmd: str, check: bool = True) -> str:
        return self.run("shell", cmd, check=check)


def save_screenshot(adb: Adb, path: str) -> None:
    """PNG of the current screen (binary, so not through Adb.run). Best effort: a failure only loses the picture."""
    try:
        out = subprocess.run(adb.base + ["exec-out", "screencap", "-p"], capture_output=True, timeout=30)
        if out.returncode == 0 and out.stdout:
            with open(path, "wb") as f:
                f.write(out.stdout)
    except (OSError, subprocess.SubprocessError):
        pass


def enable_habitat_a11y(adb: Adb) -> None:
    """Add Habitat's service next to AndroidWorld's a11y forwarder (do not replace it)."""
    current = adb.shell("settings get secure enabled_accessibility_services", check=False)
    services = [s for s in current.split(":") if s and s != "null"]
    if A11Y_SERVICE not in services:
        services.append(A11Y_SERVICE)
        adb.shell(f"settings put secure enabled_accessibility_services {':'.join(services)}")
    adb.shell("settings put secure accessibility_enabled 1")


# AndroidWorld's own setup leaves some apps asking for storage access on first open. Remote-origin goals
# never tap permission buttons (by design), so grant once here, as a user installing the app would.
APP_PERMISSIONS = {
    "net.gsantner.markor": ["MANAGE_EXTERNAL_STORAGE"],
}


def grant_app_permissions(adb: Adb) -> None:
    for pkg, ops in APP_PERMISSIONS.items():
        for op in ops:
            adb.shell(f"appops set --uid {pkg} {op} allow", check=False)
        for perm in ("READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE"):
            adb.shell(f"pm grant {pkg} android.permission.{perm}", check=False)


def a11y_healthy(mcp: "Mcp") -> bool:
    """Habitat's accessibility service sees windows with text (an empty tree made one 25-task run score 2/25)."""
    try:
        ok, text = mcp.call("screen", timeout=20)
        snap = json.loads(text) if ok else {}
    except Exception:
        return False
    return bool(snap.get("window_count")) and bool(str(snap.get("screen_text", "")).strip())


def ensure_a11y(adb: Adb, mcp: "Mcp") -> str:
    """Before each task: if Habitat sees an empty screen, rebind its accessibility service. Returns what happened."""
    if a11y_healthy(mcp):
        return "ok"
    for attempt in range(3):
        current = adb.shell("settings get secure enabled_accessibility_services", check=False)
        others = [s for s in current.split(":") if s and s != "null" and s != A11Y_SERVICE]
        adb.shell(f"settings put secure enabled_accessibility_services {':'.join(others) or 'null'}", check=False)
        time.sleep(2)
        enable_habitat_a11y(adb)
        time.sleep(4 + 2 * attempt)
        if a11y_healthy(mcp):
            return f"rebound after {attempt + 1}"
    return "unhealthy"


def setup_habitat(
    adb: Adb, apk: str | None, token: str, forwarder_port: int, provider: str, mcp_port: int, extras: dict[str, str],
) -> None:
    grant_app_permissions(adb)
    if apk:
        adb.run("install", "-r", "-g", apk)
    adb.shell(f"appops set {PKG} SYSTEM_ALERT_WINDOW allow", check=False)
    # 「修改系统设置」：用户在 App 引导里会授权；没有它亮度工具必失败（AndroidWorld 有 4 个亮度任务）
    adb.shell(f"appops set {PKG} WRITE_SETTINGS allow", check=False)
    adb.shell(f"pm grant {PKG} android.permission.POST_NOTIFICATIONS", check=False)
    enable_habitat_a11y(adb)
    time.sleep(3)  # let the accessibility service connect
    # eval_setup only works on debug builds; the activity is DUMP-protected (adb shell only).
    # Keys on the device are placeholders; the forwarder on the host adds the real ones.
    base = f"http://10.0.2.2:{forwarder_port}"
    flags = {
        "agent_token": token, "deepseek_key": "via-host-forwarder", "deepseek_url": f"{base}/chat/completions",
        "jev_key": "via-host-forwarder", "jev_url": f"{base}/v1/systemone", "provider": provider,
        **{k: v for k, v in extras.items() if k != "eval_local_hours"},
    }
    args = " ".join(f"--es {k} {v}" for k, v in flags.items() if v)
    hours = int(extras.get("eval_local_hours") or 0)
    args += f" --ei eval_local_hours {hours}"  # always sent, so a run without the flag switches it off
    adb.shell(f"am start -W -n {SELFTEST} --ez eval_setup true --ez start_agent true {args}")
    time.sleep(3)
    adb.run("forward", f"tcp:{mcp_port}", "tcp:8931")


# --------------------------------------------------------------------------- MCP client
class Mcp:
    # The app's MCP server is on 127.0.0.1 (adb forward). WSL has HTTP_PROXY=127.0.0.1:7897 (Clash) and
    # NO_PROXY="127.*,...": urllib does not expand that wildcard, so these calls went through Clash, which answered
    # 502 now and then and killed three jobs at once (2026-11-05 21:16). Never use a proxy for them.
    _direct = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def __init__(self, token: str, url: str):
        self.token, self.url, self.next_id = token, url, 1

    def call(self, tool: str, args: dict | None = None, timeout: float = 60) -> tuple[bool, str]:
        msg = {"jsonrpc": "2.0", "id": self.next_id, "method": "tools/call", "params": {"name": tool, "arguments": args or {}}}
        self.next_id += 1
        req = urllib.request.Request(
            self.url,
            data=json.dumps(msg).encode(),
            headers={"Content-Type": "application/json", "Accept": "application/json, text/event-stream", "Authorization": f"Bearer {self.token}"},
            method="POST",
        )
        with self._direct.open(req, timeout=timeout) as resp:
            reply = json.loads(resp.read())
        if "error" in reply:
            return False, reply["error"].get("message", "")
        result = reply["result"]
        return not result.get("isError", False), result["content"][0]["text"]

    def wait_ready(self, seconds: int = 60) -> None:
        deadline = time.time() + seconds
        while time.time() < deadline:
            try:
                if self.call("ping", timeout=5)[0]:
                    return
            except Exception:
                pass
            time.sleep(2)
        raise RuntimeError(f"Habitat MCP server not reachable at {self.url}")


def run_goal(mcp: Mcp, goal: str, provider: str, planner: bool, max_steps: int, timeout_s: int) -> dict:
    ok, text = mcp.call("agent_start", {"goal": goal, "provider": provider, "planner": planner, "max_steps": max_steps})
    if not ok:
        return {"phase": "start_error", "detail": text, "log": ""}
    deadline = time.time() + timeout_s
    status: dict = {}
    while time.time() < deadline:
        time.sleep(3)
        try:
            ok, text = mcp.call("agent_status")
        except (OSError, ValueError):  # one lost poll (adb forward hiccup) is not the end of the task; the agent keeps running
            continue
        status = json.loads(text) if ok else {}
        if status and not status.get("running"):
            return status
    mcp.call("agent_stop")
    time.sleep(2)
    status = json.loads(mcp.call("agent_status")[1])
    status["phase"] = "timeout"
    return status


def instance_seed(seed: int, name: str) -> int:
    """AndroidWorld's create_suite seed for the first instance of a task (suite_utils._get_instance_seed(name, 0))."""
    return int(hashlib.sha256(f"{seed}_{name}_0".encode()).hexdigest(), 16) % (2**32)


def step_budget(complexity: float, mode: str) -> tuple[int, int]:
    """(AndroidWorld steps, device actions Habitat may take).

    'official' = AndroidWorld's runner, int(10 * complexity); 'official15' = 1.5x (DroidRun's public harness default,
    ceil(15 * complexity)); 'lenient' = 3x official, at least 30 (most top leaderboard entries run with 150 steps or no
    limit). In all three, one step is kept for the final answer/status, which the reference agents spend as an action;
    Habitat does it as its review, so its device actions are capped at budget - 1. Habitat caps a run at 120.
    A plain number is a fixed budget with no reserved step (the 25-task subset runs).
    """
    import math

    if mode in ("official", "official15", "lenient"):
        if mode == "official":
            total = int(10 * complexity)
        elif mode == "official15":
            total = math.ceil(15 * complexity)
        else:
            total = max(30, int(30 * complexity))
        total = max(5, min(120, total))
        return total, total - 1
    return int(mode), int(mode)


def obs_sources(log: str) -> dict[str, int]:
    """Count observation sources from the step log ("读屏 … / <source> / N 个控件")."""
    counts: dict[str, int] = {}
    for line in log.splitlines():
        if line.startswith("读屏 ") and " / " in line:
            parts = line.split(" / ")
            if len(parts) >= 3:
                counts[parts[-2].strip()] = counts.get(parts[-2].strip(), 0) + 1
    return counts


# --------------------------------------------------------------------------- main
def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--apk", help="Habitat debug APK (x86_64 or universal); omit to keep the installed one")
    ap.add_argument("--adb", default=os.path.expanduser("~/aw/android-sdk/platform-tools/adb"))
    ap.add_argument("--console_port", type=int, default=5554)
    ap.add_argument("--tasks", required=True, help="comma-separated AndroidWorld task names, or @file with one per line")
    ap.add_argument("--seed", type=int, default=30)
    ap.add_argument("--seed_mode", default="official", choices=["official", "positional"],
                    help="official = AndroidWorld's per-task seed (same params as the reference runner, independent of list order); "
                         "positional = seed + index in --tasks (runs before 2026-11-05 21:10)")
    ap.add_argument("--provider", default="deepseek", choices=["deepseek", "jev", "local"])
    ap.add_argument("--planner", default="on", choices=["on", "off"])
    ap.add_argument("--planner_model", default="", help="DeepSeek model for planner/reviewer/reflection (e.g. deepseek-flash = thinking); empty = deepseek-chat")
    ap.add_argument("--upstream", default="deepseek", choices=["deepseek", "truesota"],
                    help="where the app's DeepSeek-route calls go; truesota = true-sota.com (any model, key in TS_API_KEY)")
    ap.add_argument("--exec_model", default="", help="with --upstream truesota: real model for the per-step executor (app asks for deepseek-chat)")
    ap.add_argument("--plan_model", default="", help="with --upstream truesota: real model for planner/reviewer/reflection")
    ap.add_argument("--exec_fallback", default="", help="backup executor model if the gateway says exec_model is unavailable")
    ap.add_argument("--plan_fallback", default="", help="backup planner model if the gateway says plan_model is unavailable")
    ap.add_argument("--vision", default="off", choices=["off", "plan", "all"],
                    help="attach screenshots: plan = planner/reviewer/reflection, all = every executor step too (needs multimodal models)")
    ap.add_argument("--budget", default="30", help="steps per task: a number, 'official' = AndroidWorld's int(10*complexity), 'official15' = 1.5x; Habitat caps at 40")
    ap.add_argument("--forwarder_port", type=int, default=18080)
    ap.add_argument("--grpc_port", type=int, default=8554)
    ap.add_argument("--mcp_port", type=int, default=8931, help="host port forwarded to the app's MCP server (8931 on the device)")
    ap.add_argument("--ocr_mode", default="", help="fallback / hybrid (app setting; empty = keep the app default)")
    ap.add_argument("--penalty_mode", default="", help="hard / soft (app setting; empty = keep the app default)")
    ap.add_argument("--eval_local", action="store_true",
                    help="treat MCP goals as typed on the phone (debug builds only; sensitive actions the goal names are allowed)")
    ap.add_argument("--out", default="habitat_androidworld.jsonl")
    ap.add_argument("--start_index", type=int, default=0, help="resume an interrupted run: skip the first N tasks (seeds stay seed+i)")
    a = ap.parse_args()

    keys = {"deepseek": os.environ.get("DEEPSEEK_API_KEY", ""), "jev": os.environ.get("JEV_API_KEY", ""),
            "truesota": os.environ.get("TS_API_KEY", "")}
    if a.upstream == "truesota":
        if not keys["truesota"]:
            raise SystemExit("set TS_API_KEY (stays on the host)")
        _Forwarder.upstream_url, _Forwarder.upstream_key_name = TRUESOTA_URL, "truesota"
        _Forwarder.model_map = {k: v for k, v in (("deepseek-chat", a.exec_model), (a.planner_model or "deepseek-flash", a.plan_model)) if v}
        _Forwarder.fallback_map = {k: v for k, v in ((a.exec_model, a.exec_fallback), (a.plan_model, a.plan_fallback)) if k and v}
        keys["deepseek"] = keys["deepseek"] or "unused"  # the app still needs the DeepSeek route switched on
    if (a.provider == "deepseek" or a.planner == "on") and not keys["deepseek"]:
        raise SystemExit("set DEEPSEEK_API_KEY (stays on the host; the emulator only sees a placeholder)")
    if a.provider == "jev" and not keys["jev"]:
        raise SystemExit("set JEV_API_KEY (stays on the host; the emulator only sees a placeholder)")
    names = open(a.tasks[1:]).read().split() if a.tasks.startswith("@") else [t for t in a.tasks.split(",") if t]

    # AndroidWorld imports are heavy; keep them after argument parsing
    import random

    from android_world import registry
    from android_world.env import env_launcher

    start_forwarder(a.forwarder_port, keys)
    env = env_launcher.load_and_setup_env(
        console_port=a.console_port, emulator_setup=False, adb_path=a.adb, grpc_port=a.grpc_port,
    )
    adb = Adb(a.adb, f"emulator-{a.console_port}")
    token = secrets.token_urlsafe(24)
    extras = {"ocr_mode": a.ocr_mode, "penalty_mode": a.penalty_mode, "planner_model": a.planner_model or "-",
              "vision": a.vision, "eval_local_hours": "12" if a.eval_local else "0"}
    setup_habitat(adb, a.apk, token, a.forwarder_port, a.provider, a.mcp_port, extras)
    mcp = Mcp(token, f"http://127.0.0.1:{a.mcp_port}/mcp")
    mcp.wait_ready()
    started = time.strftime("%Y-%m-%d %H:%M:%S")

    aw = registry.TaskRegistry().get_registry(registry.TaskRegistry.ANDROID_WORLD_FAMILY)
    unknown = [n for n in names if n not in aw]
    if unknown:
        raise SystemExit(f"unknown tasks: {unknown}")

    passed = passed_official = done = 0
    with open(a.out, "a", encoding="utf-8") as out:
        for i, name in enumerate(names):
            if i < a.start_index:
                continue
            task_type = aw[name]
            # official: AndroidWorld's create_suite seeding (sha256 of "<seed>_<name>_0"), so a task gets the same
            # parameters as in the reference runner no matter where it sits in our list or which emulator runs it.
            # positional: the old seed+i, only to reproduce runs from before 2026-11-05 21:10 (v8 and earlier).
            seed = instance_seed(a.seed, name) if a.seed_mode == "official" else a.seed + i
            random.seed(seed)
            goal, complexity = "", None
            try:
                task_type.set_device_time(env)
                params = task_type.generate_random_params()
                params["seed"] = seed
                task = task_type(params)
                goal, complexity = task.goal, task.complexity
                env.reset(go_home=True)
                enable_habitat_a11y(adb)  # AndroidWorld resets can drop extra services
                try:
                    task.initialize_task(env)
                except Exception as e:
                    # an app's database only exists after the app has run once (OsmAnd's map_markers_db on a fresh
                    # read-only emulator); the reference runner's order happened to open it earlier. Open it, retry once.
                    app = getattr(task, "app_name_with_db", None) or getattr(task, "app_name", None)
                    if "no such table" not in str(e) or not app:
                        raise
                    from android_world.env import adb_utils
                    adb_utils.launch_app(app, env.controller)
                    time.sleep(8)
                    adb_utils.close_app(app, env.controller)
                    env.reset(go_home=True)
                    task.initialize_task(env)
            except Exception as e:  # one broken task setup must not end a 116-task run
                row = {"task": name, "seed": seed, "goal": goal, "score": 0.0, "phase": "init_error",
                       "detail": repr(e)[:300], "complexity": complexity}
                out.write(json.dumps(row, ensure_ascii=False) + "\n")
                out.flush()
                print(f"[{i + 1}/{len(names)}] INIT_ERROR {name}: {repr(e)[:200]}", flush=True)
                # several tasks of one app can fail setup for the same reason (missing tool); only stop if the device is gone
                if adb.run("get-state", check=False).strip() != "device":
                    raise SystemExit("emulator is not reachable any more; stopping")
                continue
            budget, device_steps = step_budget(task.complexity, a.budget)
            timeout = max(300, min(5400, 40 * budget))
            a11y = ensure_a11y(adb, mcp)
            t0 = time.time()
            net_before = _Forwarder.network_errors
            usage_before = dict(_Forwarder.usage)
            status = run_goal(mcp, task.goal, a.provider, a.planner == "on", device_steps, timeout)
            status["network_errors"] = _Forwarder.network_errors - net_before
            usage = {k: _Forwarder.usage[k] - usage_before[k] for k in usage_before}
            seconds = round(time.time() - t0)
            # Information-retrieval tasks read the answer from here. Newer builds report a bare `answer`;
            # older ones only have `detail` (answer + reason in one string), which is what they are scored on.
            answer = status.get("answer") or status.get("detail", "")
            env.interaction_cache = answer
            try:
                score = float(task.is_successful(env))
            except Exception as e:  # a crashing check counts as a failure, but keep the reason
                score, status["check_error"] = 0.0, repr(e)[:300]
            log_dir = os.path.splitext(a.out)[0] + "_logs"
            os.makedirs(log_dir, exist_ok=True)
            if score < 1.0:  # the end screen of a failed task, before tear_down resets the app (for failure analysis)
                save_screenshot(adb, os.path.join(log_dir, f"{i:03d}_{name}.png"))
            try:
                task.tear_down(env)
            except Exception as e:
                status["teardown_error"] = repr(e)[:200]
            passed += score == 1.0
            # AndroidWorld's runner: `agent_successful = task_successful if interaction_results.done else 0.0` —
            # a run that never reported completion scores 0 even if the phone ends up right.
            score_official = score if status.get("phase") == "done" else 0.0
            passed_official += score_official == 1.0
            done += 1
            row = {
                "task": name, "seed": seed, "goal": task.goal, "score": score, "score_official": score_official,
                "phase": status.get("phase"), "detail": status.get("detail", "")[:500], "answer": answer[:200],
                "seconds": seconds, "provider": a.provider, "planner": a.planner == "on", "planner_model": a.planner_model,
                "complexity": task.complexity, "budget": budget, "device_steps": device_steps, "check_error": status.get("check_error"),
                "network_errors": status["network_errors"], "usage": usage, "a11y": a11y, "eval_local": a.eval_local,
                "obs_sources": status.get("obs_sources") or obs_sources(status.get("log", "")),
            }
            out.write(json.dumps(row, ensure_ascii=False) + "\n")
            out.flush()
            # step log for failure analysis; contains screen text, so keep it next to the results, not in git
            with open(os.path.join(log_dir, f"{i:03d}_{name}.log"), "w", encoding="utf-8") as f:
                f.write(f"goal: {task.goal}\nscore: {score}\nphase: {status.get('phase')}\ndetail: {status.get('detail', '')}\n\n")
                f.write(status.get("log", ""))
            print(f"[{i + 1}/{len(names)}] {'PASS' if score == 1.0 else 'FAIL'} {name} ({seconds}s, {status.get('phase')}) — {task.goal}", flush=True)
    summary = {
        "started": started, "finished": time.strftime("%Y-%m-%d %H:%M:%S"), "passed": passed,
        "passed_official": passed_official, "ran": done,
        "tasks": len(names), "seed": a.seed, "provider": a.provider, "planner": a.planner, "planner_model": a.planner_model,
        "budget": a.budget,
        "ocr_mode": a.ocr_mode, "penalty_mode": a.penalty_mode, "eval_local": a.eval_local, "apk": a.apk, "usage": _Forwarder.usage,
        "upstream": a.upstream, "exec_model": a.exec_model, "plan_model": a.plan_model,
        "network_errors": _Forwarder.network_errors,
    }
    with open(os.path.splitext(a.out)[0] + ".summary.json", "w", encoding="utf-8") as f:
        json.dump(summary, f, ensure_ascii=False, indent=1)
    print(f"\n{passed}/{len(names)} passed ({passed_official} under the official done rule); results appended to {a.out}", flush=True)
    env.close()


if __name__ == "__main__":
    main()
