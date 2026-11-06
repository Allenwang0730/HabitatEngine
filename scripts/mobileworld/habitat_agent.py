"""MobileWorld agent that runs Habitat as a black box (`mw eval --agent_type <this file>`).

Habitat is an on-device agent (accessibility service + planner/executor in the app), so it does not fit
MobileWorld's "screenshot in, one action out" loop. This adapter does the whole task inside the first
`predict()`:

  MobileWorld /task/init (snapshot load) → predict(): install Habitat, enable its accessibility service,
  start its MCP server, agent_start(goal), poll until it stops → return `answer` (answer text) or `finished`
  → MobileWorld /task/eval scores the device / backend state.

Every task starts from the `init_state` snapshot, so Habitat is installed again for each task.

Networking: inside the container the emulator's 10.0.2.2 is the container itself, not the WSL host. A socat
relay in the container (127.0.0.1:RELAY → docker bridge gateway:RELAY) reaches the model forwarder on the host,
which binds the bridge address only (not 0.0.0.0: with WSL's mirrored networking that would expose the key-adding
forwarder on the LAN). The forwarder adds the real gateway key; the device only has a placeholder.

Configuration (environment variables; MobileWorld passes only model_name / llm_base_url / api_key):
  HABITAT_APK            path to the debug APK (required)
  TS_API_KEY             gateway key (host only)
  HABITAT_EXEC_MODEL     executor model           (default claude-sonnet-5-5)
  HABITAT_PLAN_MODEL     planner/reviewer model   (default claude-opus-5-5)
  HABITAT_EXEC_FALLBACK / HABITAT_PLAN_FALLBACK   backups when the gateway says a model is unavailable
  HABITAT_STEPS          device actions per task  (default 50, MobileWorld's leaderboard budget)
  HABITAT_EVAL_LOCAL     1 = eval switch on (remote goals handled like typed on the phone), default 1
  HABITAT_VISION         off / plan / all         (default all)
  HABITAT_RELAY_PORT     forwarder / relay port   (default 18090)
  HABITAT_MCP_PORT_BASE  host ports for the app's MCP server (default 18930, one more per container)
  HABITAT_ADB            adb binary (default ~/aw/android-sdk/platform-tools/adb)
"""

from __future__ import annotations

import http.server
import json
import os
import secrets
import subprocess
import sys
import threading
import time
import urllib.parse
from typing import Any

from loguru import logger
from mobile_world.agents.base import BaseAgent
from mobile_world.runtime.utils.models import JSONAction

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "androidworld"))
import run_habitat as rh  # noqa: E402  (shared device setup, MCP client, model forwarder)

ADB = os.path.expanduser(os.environ.get("HABITAT_ADB", "~/aw/android-sdk/platform-tools/adb"))
RELAY_PORT = int(os.environ.get("HABITAT_RELAY_PORT", "18090"))
MCP_PORT_BASE = int(os.environ.get("HABITAT_MCP_PORT_BASE", "18930"))
STEPS = int(os.environ.get("HABITAT_STEPS", "50"))
EVAL_LOCAL = os.environ.get("HABITAT_EVAL_LOCAL", "1") == "1"
VISION = os.environ.get("HABITAT_VISION", "all")
PLANNER_MODEL_NAME = "deepseek-flash"  # what the app asks for on the planner route; the forwarder maps it

# MobileWorld imports this file again for every task (a new module each time), so process-wide state (the forwarder,
# the MCP port per container) lives on the cached run_habitat module instead of in this module's globals.
if not hasattr(rh, "mobileworld_state"):
    rh.mobileworld_state = {"lock": threading.Lock(), "forwarder": None, "mcp_ports": {}}
_STATE = rh.mobileworld_state


def _docker(*args: str, timeout: int = 60) -> str:
    out = subprocess.run(["docker", *args], capture_output=True, text=True, timeout=timeout)
    if out.returncode != 0:
        raise RuntimeError(f"docker {' '.join(args[:2])} failed: {out.stderr.strip()[:300]}")
    return out.stdout.strip()


def _bridge_gateway() -> str:
    return _docker("network", "inspect", "bridge", "--format", "{{(index .IPAM.Config 0).Gateway}}")


def _start_forwarder() -> None:
    """One forwarder per process (all containers share it), bound to the docker bridge address only."""
    with _STATE["lock"]:
        if _STATE["forwarder"] is not None:
            return
        key = os.environ.get("TS_API_KEY", "")
        if not key:
            raise SystemExit("set TS_API_KEY (stays on the host)")
        exec_model = os.environ.get("HABITAT_EXEC_MODEL", "claude-sonnet-5-5")
        plan_model = os.environ.get("HABITAT_PLAN_MODEL", "claude-opus-5-5")
        fwd = rh._Forwarder
        fwd.keys = {"deepseek": "unused", "truesota": key}
        fwd.upstream_url, fwd.upstream_key_name = rh.TRUESOTA_URL, "truesota"
        fwd.model_map = {"deepseek-chat": exec_model, PLANNER_MODEL_NAME: plan_model}
        fwd.fallback_map = {k: v for k, v in (
            (exec_model, os.environ.get("HABITAT_EXEC_FALLBACK", "qwen3.8-max")),
            (plan_model, os.environ.get("HABITAT_PLAN_FALLBACK", "claude-sonnet-5-5")),
        ) if v}

        class Server(http.server.ThreadingHTTPServer):
            request_queue_size = 64

        server = Server((_bridge_gateway(), RELAY_PORT), fwd)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        _STATE["forwarder"] = server
        logger.info(f"Habitat model forwarder on {server.server_address}")


def _container_for(base_url: str) -> tuple[str, int]:
    """(container name, host adb port) for the MobileWorld env at base_url."""
    backend_port = urllib.parse.urlparse(base_url).port
    for name in filter(None, _docker("ps", "--format", "{{.Names}}").splitlines()):
        ports = json.loads(_docker("inspect", name, "--format", "{{json .NetworkSettings.Ports}}"))
        backend = [int(b["HostPort"]) for b in (ports.get("6800/tcp") or [])]
        if backend_port in backend:
            adb = [int(b["HostPort"]) for b in (ports.get("5556/tcp") or [])]
            if not adb:
                raise RuntimeError(f"container {name} does not publish the emulator adb port")
            return name, adb[0]
    raise RuntimeError(f"no MobileWorld container publishes backend port {backend_port}")


def _mcp_port_for(container: str) -> int:
    with _STATE["lock"]:
        ports = _STATE["mcp_ports"]
        return ports.setdefault(container, MCP_PORT_BASE + len(ports))


def _ensure_relay(container: str) -> None:
    """socat in the container: the emulator's 10.0.2.2:RELAY → host forwarder on the bridge gateway."""
    running = subprocess.run(["docker", "exec", container, "pgrep", "-f", f"TCP-LISTEN:{RELAY_PORT}"],
                             capture_output=True, text=True)
    if running.returncode == 0:
        return
    gw = _bridge_gateway()
    _docker("exec", "-d", container, "socat", f"TCP-LISTEN:{RELAY_PORT},bind=127.0.0.1,fork,reuseaddr",
            f"TCP:{gw}:{RELAY_PORT}")
    time.sleep(1)


def _connect(adb_port: int) -> rh.Adb:
    """Host adb over the container's published relay. A snapshot load can drop the TCP session, so reconnect."""
    serial = f"127.0.0.1:{adb_port}"
    for _ in range(30):
        subprocess.run([ADB, "connect", serial], capture_output=True, text=True, timeout=20)
        state = subprocess.run([ADB, "-s", serial, "get-state"], capture_output=True, text=True, timeout=20)
        if "unauthorized" in state.stdout + state.stderr:
            raise RuntimeError(f"emulator at {serial} refuses this host's adb key (unauthorized)")
        if state.stdout.strip() == "device":
            booted = subprocess.run([ADB, "-s", serial, "shell", "getprop", "sys.boot_completed"],
                                    capture_output=True, text=True, timeout=20)
            if booted.stdout.strip() == "1":
                return rh.Adb(ADB, serial)
        subprocess.run([ADB, "disconnect", serial], capture_output=True, text=True, timeout=20)
        time.sleep(2)
    raise RuntimeError(f"emulator at {serial} not reachable over adb")


MAX_LOST_POLLS = 40  # about 2 minutes of failed agent_status polls (3 s apart)


def _run_goal(mcp: rh.Mcp, goal: str, timeout_s: int) -> dict:
    """rh.run_goal, but a server that stays unreachable raises (MobileWorld retries the task) instead of
    polling until the timeout and scoring the task as a failure."""
    ok, text = mcp.call("agent_start", {"goal": goal, "provider": "deepseek", "planner": True, "max_steps": STEPS})
    if not ok:
        raise RuntimeError(f"agent_start refused: {text[:300]}")
    deadline, lost = time.time() + timeout_s, 0
    while time.time() < deadline:
        time.sleep(3)
        try:
            ok, text = mcp.call("agent_status")
        except (OSError, ValueError) as e:
            lost += 1
            if lost >= MAX_LOST_POLLS:
                raise RuntimeError(f"Habitat MCP server unreachable for {lost} polls: {e!r}") from e
            continue
        lost = 0
        status = json.loads(text) if ok else {}
        if status and not status.get("running"):
            return status
    mcp.call("agent_stop")
    time.sleep(2)
    status = json.loads(mcp.call("agent_status")[1])
    status["phase"] = "timeout"
    return status


class HabitatAgent(BaseAgent):
    """Runs a whole MobileWorld task inside Habitat, then hands the answer (if any) back."""

    def __init__(self, model_name: str = "", llm_base_url: str = "", api_key: str = "", env: Any = None, **kwargs: Any):
        super().__init__()
        self.env = env
        self.apk = os.path.expanduser(os.environ.get("HABITAT_APK", ""))
        if not self.apk or not os.path.exists(self.apk):
            raise SystemExit("set HABITAT_APK to the Habitat debug APK")
        self.ran = False
        _start_forwarder()

    def initialize_hook(self, instruction: str) -> None:
        self.ran = False

    def reset(self) -> None:
        self.ran = False

    def predict(self, observation: dict[str, Any]) -> tuple[str, JSONAction]:
        if self.ran:  # MobileWorld should have stopped after our answer/finished; never act twice
            return "done", JSONAction(action_type="finished")
        self.ran = True
        goal = self.instruction
        t0 = time.time()
        # Setup failures (adb, install, MCP server) raise: MobileWorld then retries the task (--auto-retry)
        # instead of scoring a run in which Habitat never started.
        container, adb_port = _container_for(self.env.base_url)
        _ensure_relay(container)
        adb = _connect(adb_port)
        try:
            return self._run(adb, container, goal, t0)
        finally:
            # Leave the device to MobileWorld between tasks: a second adb host attached during its snapshot load
            # is one suspect for the "device offline" it reported once right after a load.
            subprocess.run([ADB, "disconnect", f"127.0.0.1:{adb_port}"], capture_output=True, text=True, timeout=20)

    def _run(self, adb: rh.Adb, container: str, goal: str, t0: float) -> tuple[str, JSONAction]:
        mcp_port = _mcp_port_for(container)
        token = secrets.token_urlsafe(24)
        extras = {"ocr_mode": "fallback", "penalty_mode": "soft", "planner_model": PLANNER_MODEL_NAME,
                  "vision": VISION, "eval_local_hours": "12" if EVAL_LOCAL else "0"}
        rh.setup_habitat(adb, self.apk, token, RELAY_PORT, "deepseek", mcp_port, extras)
        mcp = rh.Mcp(token, f"http://127.0.0.1:{mcp_port}/mcp")
        mcp.wait_ready()
        a11y = rh.ensure_a11y(adb, mcp)
        # Once the agent has started, whatever it does is the agent's result. Losing the app's MCP server for
        # minutes is not (run_goal skips failed polls, so a dead server only showed up as a 50-minute timeout).
        status = _run_goal(mcp, goal, timeout_s=max(600, min(5400, 60 * STEPS)))
        answer = (status.get("answer") or "").strip()
        summary = {
            "phase": status.get("phase"), "detail": (status.get("detail") or "")[:500], "answer": answer[:300],
            "seconds": round(time.time() - t0), "a11y": a11y, "log": (status.get("log") or "")[-12000:],
        }
        prediction = json.dumps(summary, ensure_ascii=False)
        if answer:
            return prediction, JSONAction(action_type="answer", text=answer)
        return prediction, JSONAction(action_type="finished")
