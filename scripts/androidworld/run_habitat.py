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


# --------------------------------------------------------------------------- DeepSeek forwarder
class _Forwarder(http.server.BaseHTTPRequestHandler):
    key = ""
    network_errors = 0  # requests that still failed after retries; reported per task

    def do_POST(self):  # noqa: N802 (http.server API)
        if self.path != "/chat/completions":
            self.send_error(404)
            return
        body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
        req = urllib.request.Request(
            DEEPSEEK_URL,
            data=body,
            headers={"Content-Type": "application/json", "Authorization": f"Bearer {self.key}"},
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
        if status == 502:
            _Forwarder.network_errors += 1
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *args):  # keep the console quiet; requests contain screen text
        pass


def start_forwarder(port: int, key: str) -> http.server.ThreadingHTTPServer:
    _Forwarder.key = key
    server = http.server.ThreadingHTTPServer(("127.0.0.1", port), _Forwarder)
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


def setup_habitat(adb: Adb, apk: str | None, token: str, forwarder_port: int, provider: str) -> None:
    grant_app_permissions(adb)
    if apk:
        adb.run("install", "-r", "-g", apk)
    adb.shell(f"appops set {PKG} SYSTEM_ALERT_WINDOW allow", check=False)
    adb.shell(f"pm grant {PKG} android.permission.POST_NOTIFICATIONS", check=False)
    enable_habitat_a11y(adb)
    time.sleep(3)  # let the accessibility service connect
    # eval_setup only works on debug builds; the activity is DUMP-protected (adb shell only)
    adb.shell(
        f"am start -W -n {SELFTEST} --ez eval_setup true --ez start_agent true "
        f"--es agent_token {token} --es deepseek_key via-host-forwarder "
        f"--es deepseek_url http://10.0.2.2:{forwarder_port}/chat/completions --es provider {provider}"
    )
    time.sleep(3)
    adb.run("forward", "tcp:8931", "tcp:8931")


# --------------------------------------------------------------------------- MCP client
class Mcp:
    def __init__(self, token: str, url: str = "http://127.0.0.1:8931/mcp"):
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
        with urllib.request.urlopen(req, timeout=timeout) as resp:
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
        raise RuntimeError("Habitat MCP server not reachable on 127.0.0.1:8931")


def run_goal(mcp: Mcp, goal: str, provider: str, planner: bool, max_steps: int, timeout_s: int) -> dict:
    ok, text = mcp.call("agent_start", {"goal": goal, "provider": provider, "planner": planner, "max_steps": max_steps})
    if not ok:
        return {"phase": "start_error", "detail": text, "log": ""}
    deadline = time.time() + timeout_s
    status: dict = {}
    while time.time() < deadline:
        time.sleep(3)
        ok, text = mcp.call("agent_status")
        status = json.loads(text) if ok else {}
        if status and not status.get("running"):
            return status
    mcp.call("agent_stop")
    time.sleep(2)
    status = json.loads(mcp.call("agent_status")[1])
    status["phase"] = "timeout"
    return status


# --------------------------------------------------------------------------- main
def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--apk", help="Habitat debug APK (x86_64 or universal); omit to keep the installed one")
    ap.add_argument("--adb", default=os.path.expanduser("~/aw/android-sdk/platform-tools/adb"))
    ap.add_argument("--console_port", type=int, default=5554)
    ap.add_argument("--tasks", required=True, help="comma-separated AndroidWorld task names, or @file with one per line")
    ap.add_argument("--seed", type=int, default=30)
    ap.add_argument("--provider", default="deepseek", choices=["deepseek", "jev", "local"])
    ap.add_argument("--planner", default="on", choices=["on", "off"])
    ap.add_argument("--max_steps", type=int, default=30)
    ap.add_argument("--timeout", type=int, default=600, help="seconds per task")
    ap.add_argument("--forwarder_port", type=int, default=18080)
    ap.add_argument("--out", default="habitat_androidworld.jsonl")
    a = ap.parse_args()

    key = os.environ.get("DEEPSEEK_API_KEY", "")
    if a.provider == "deepseek" or a.planner == "on":
        if not key:
            raise SystemExit("set DEEPSEEK_API_KEY (stays on the host; the emulator only sees a placeholder)")
    names = open(a.tasks[1:]).read().split() if a.tasks.startswith("@") else [t for t in a.tasks.split(",") if t]

    # AndroidWorld imports are heavy; keep them after argument parsing
    import random

    from android_world import registry
    from android_world.env import env_launcher

    start_forwarder(a.forwarder_port, key)
    env = env_launcher.load_and_setup_env(console_port=a.console_port, emulator_setup=False, adb_path=a.adb)
    adb = Adb(a.adb, f"emulator-{a.console_port}")
    token = secrets.token_urlsafe(24)
    setup_habitat(adb, a.apk, token, a.forwarder_port, a.provider)
    mcp = Mcp(token)
    mcp.wait_ready()

    aw = registry.TaskRegistry().get_registry(registry.TaskRegistry.ANDROID_WORLD_FAMILY)
    unknown = [n for n in names if n not in aw]
    if unknown:
        raise SystemExit(f"unknown tasks: {unknown}")

    passed = 0
    with open(a.out, "a", encoding="utf-8") as out:
        for i, name in enumerate(names):
            random.seed(a.seed + i)
            task_type = aw[name]
            task = task_type(task_type.generate_random_params())
            env.reset(go_home=True)
            enable_habitat_a11y(adb)  # AndroidWorld resets can drop extra services
            task.initialize_task(env)
            t0 = time.time()
            net_before = _Forwarder.network_errors
            status = run_goal(mcp, task.goal, a.provider, a.planner == "on", a.max_steps, a.timeout)
            status["network_errors"] = _Forwarder.network_errors - net_before
            seconds = round(time.time() - t0)
            env.interaction_cache = status.get("detail", "")  # only information-retrieval tasks read it
            try:
                score = float(task.is_successful(env))
            except Exception as e:  # a crashing check counts as a failure, but keep the reason
                score, status["check_error"] = 0.0, repr(e)[:300]
            task.tear_down(env)
            passed += score == 1.0
            row = {
                "task": name, "seed": a.seed + i, "goal": task.goal, "score": score,
                "phase": status.get("phase"), "detail": status.get("detail", "")[:500],
                "seconds": seconds, "provider": a.provider, "planner": a.planner == "on",
                "complexity": task.complexity, "check_error": status.get("check_error"),
                "network_errors": status["network_errors"],
            }
            out.write(json.dumps(row, ensure_ascii=False) + "\n")
            out.flush()
            # step log for failure analysis; contains screen text, so keep it next to the results, not in git
            log_dir = os.path.splitext(a.out)[0] + "_logs"
            os.makedirs(log_dir, exist_ok=True)
            with open(os.path.join(log_dir, f"{i:03d}_{name}.log"), "w", encoding="utf-8") as f:
                f.write(f"goal: {task.goal}\nscore: {score}\nphase: {status.get('phase')}\ndetail: {status.get('detail', '')}\n\n")
                f.write(status.get("log", ""))
            print(f"[{i + 1}/{len(names)}] {'PASS' if score == 1.0 else 'FAIL'} {name} ({seconds}s, {status.get('phase')}) — {task.goal}")
    print(f"\n{passed}/{len(names)} passed; results appended to {a.out}")
    env.close()


if __name__ == "__main__":
    main()
