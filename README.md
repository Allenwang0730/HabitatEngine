# Habitat — Let AI Agents Drive Android

> An **embeddable Android automation runtime** with an **on-device MCP server** and an
> **autonomous agent** (DeepSeek plans, TypeSafe Jev picks each tap, code verifies).
> Workflows are flat JSON that LLMs can write; execution goes through accessibility + system APIs.

[![CI](https://github.com/Allenwang0730/HabitatEngine/actions/workflows/ci.yml/badge.svg)](https://github.com/Allenwang0730/HabitatEngine/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/Allenwang0730/HabitatEngine)](https://github.com/Allenwang0730/HabitatEngine/releases)
[![JitPack](https://jitpack.io/v/Allenwang0730/HabitatEngine.svg)](https://jitpack.io/#Allenwang0730/HabitatEngine)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3.20-purple)]()
[![Min SDK](https://img.shields.io/badge/API-29+-green)]()
[![MCP](https://img.shields.io/badge/MCP-server-green)]()
[![License](https://img.shields.io/badge/License-MIT-blue)](LICENSE)

[中文文档](README_CN.md) · [项目介绍（中文，含真机示例）](docs/INTRO.md)

**What you get**

- **Tell the phone what to do** — "open Settings and go to Bluetooth", "turn brightness to max", "what's my battery?" The agent plans, uses device tools directly when it can, taps through the UI when it must, and verifies the result on screen. On-device eval: with planning, **28/28** for both Jev and DeepSeek executors (2026-10-09, 13–18/28 without) — [method and raw data](docs/EVAL.md).
- **An MCP server on the phone** — any MCP client (Claude, Cursor, your own agent) can read the screen and call ~80 automation nodes over `ws://127.0.0.1:8931/mcp`, token-authenticated, dangerous tools off by default.
- **An embeddable engine** — drop `:lib` into your own app (JitPack) and run LLM-generated JSON workflows: triggers, branches, loops, variables, shell, HTTP, sensors, notifications.

## Try it in 2 minutes

1. Install the APK from [Releases](https://github.com/Allenwang0730/HabitatEngine/releases) on Android 10+ — `app-standard-arm64-v8a-release.apk` fits almost every phone (`universal` if unsure).
2. Open Habitat, enable its **accessibility service** when prompted.
3. **Agent** tab → pick an executor (Jev / DeepSeek / Local), paste your API key (stored encrypted on the device), type a goal, tap **Start**.
   - No key? Choose **Local** — it runs offline with simple heuristics.
   - Calling Jev from mainland China returns HTTP 451; see [Jev network](#autonomous-mode-planner-deepseek--executor-jev--code-control).

Prefer workflows? **AI 生成** tab → describe a task in plain language → DeepSeek writes the JSON → save & run.

### Use the engine in your app

```kotlin
// settings.gradle.kts
dependencyResolutionManagement { repositories { maven("https://jitpack.io") } }
// build.gradle.kts
implementation("com.github.Allenwang0730:HabitatEngine:v1.9.0")
```

See [docs/ENGINE_USAGE.md](docs/ENGINE_USAGE.md) for initialization and permissions.

---

## Philosophy

Habitat workflows are **flat JSON graphs**. Every node has exactly two control-flow
mechanisms — `next` (linear) and `branches` (conditional) — and nothing else.

This is intentional:

- **LLMs are great at generating JSON** — the flatter the structure, the fewer mistakes.
- **One variable syntax** (`${varName}`) — no `{{}}`, `[[]]`, or other delimiters to confuse.
- **Validation is simple** — check `start_node_id` exists, check `next` references are valid.
  Errors are descriptive enough to feed back to the LLM for auto-correction.

---

## Quick Start

### Minimal Workflow

```json
{
  "start_node_id": "hello",
  "nodes": {
    "hello": {
      "id": "hello",
      "type": "ACTION_TOAST",
      "params": { "message": "Hello, Habitat!" }
    }
  }
}
```

### Execute from Code

```kotlin
val factory = NodeHandlerFactory(
    a11y = AppAccessibilityProvider,
    shell = ShizukuShellExecutor(context),
)
val graph = HabitatJson.fromJson(jsonString)
HabitatExecutionService.start(workflowId, jsonString, context, factory) { log ->
    println(log)
}
```

---

## Why not Tasker / MaaFramework / Auto.js?

| Tool | Style | Can you embed it in your app? |
|------|-------|:---:|
| **Tasker / Automate / MacroDroid** | End-user apps with GUI editors | ❌ |
| **MaaFramework** | Embeddable & JSON task graphs, but C++ and image-recognition-first | ⚠️ |
| **Auto.js / AutoX.js** | JavaScript scripting | ❌ |
| **Habitat** | **Embeddable Kotlin `:lib` + flat JSON workflows + LLM generation + accessibility & system-API dual-track** | ✅ |

Habitat is the one that is **embeddable + LLM-generatable + dual-track**. Define workflows
as flat JSON designed for LLM output, embed the runtime in your app, and let agents or
automation drive the device.

---

## Node Types (86)

The engine has 86 node types across categories. Common / core ones below (same list is in `NodeKnowledge`, the single source used for LLM generation — documented once here and in `README_CN.md`):

| Type | Params | Purpose | Requires |
|------|--------|---------|----------|
| ACTION_SET_VARIABLE | name, value, type | set variable | |
| ACTION_LOG | message | log | |
| ACTION_DELAY | millis | delay | |
| CONDITION_SWITCH | expression, branches | conditional branch | |
| ACTION_TOAST | message | toast | |
| ACTION_GET_TIME | format, output_var | get time | |
| ACTION_MATH | operation, a, b, output_var | math | |
| ACTION_TEXT_OPERATION | action, input, output_var | text op | |
| ACTION_CLIPBOARD | action, text, output_var | clipboard | |
| ACTION_PARSE_JSON | json, path, output_var | parse JSON | |
| ACTION_HTTP_REQUEST | url, method, output_var, timeout | HTTP request | needs network |
| ACTION_LAUNCH_APP | package_name | launch app | |
| ACTION_CLICK | target, region, occurrence, offset_x, offset_y | click | needs a11y |
| ACTION_INPUT_TEXT | text, mode | input text | needs a11y |
| ACTION_READ_SCREEN | keyword, output_var | read screen | needs a11y |
| ACTION_FIND_ELEMENT | selector, search_mode, region, timeout_ms, output_var | find element | needs a11y |
| ACTION_GLOBAL_KEY | key | global key | needs a11y |
| ACTION_SHELL | command, mode | shell | needs Shizuku/root |
| ACTION_GET_BATTERY | output_var | battery | |
| ACTION_NETWORK_STATUS | action | network | |
| ACTION_GET_DEVICE_INFO | | device info | |
| ACTION_SCREENSHOT | format, output_var | screenshot | screenshot perm |
| ACTION_VIBRATE | duration | vibrate | |
| ACTION_TEXT_TO_SPEECH | text, language | TTS | |
| ACTION_FILE_OPERATION | action, path, content, output_var | file | |
| ACTION_AI_CHAT | prompt, output_var | local AI chat | |
| ACTION_CALL_DIRECT | phone_number, sim | direct call | CALL_PHONE |
| ACTION_CALL_PHONE | phone_number | call | CALL_PHONE |
| ACTION_SEND_NOTIFICATION | title, message, channel_id | notification | |
| ACTION_SWIPE | x1,y1,x2,y2,duration_ms | swipe | needs a11y |
| ACTION_MEDIA_CONTROL | cmd | media control | |

Full categories in each `NodeModules`; `NodeKnowledge` is the single source for the AI-generation prompt and the node reference.

### Interaction (6)
| Type | What it does |
|------|-------------|
| `ACTION_CLICK` | Click coordinates or accessibility element (`offset_x`/`offset_y` shift from the target center) |
| `ACTION_SWIPE` | Linear swipe gesture |
| `ACTION_LONG_PRESS` | Long press |
| `ACTION_INPUT_TEXT` | Type text (a11y / shell / clipboard) |
| `ACTION_GLOBAL_KEY` | System key (back, home, recents, …) |
| `ACTION_FIND_ELEMENT` | Find UI element by selector |

### System (11)
| Type | What it does |
|------|-------------|
| `ACTION_SHELL` | Execute shell command (Shizuku / root) |
| `ACTION_LAUNCH_APP` | Launch app by package name |
| `ACTION_FORCE_STOP_APP` | Force-stop app |
| `ACTION_SCREEN_WAKE` | Wake / sleep / unlock screen |
| `ACTION_WIFI` | WiFi on / off / toggle / status |
| `ACTION_BLUETOOTH` | Bluetooth on / off / toggle / status |
| `ACTION_VOLUME` | Volume set / get / up / down / mute |
| `ACTION_BRIGHTNESS` | Brightness set / get / auto |
| `ACTION_FLASHLIGHT` | Flashlight on / off / toggle |
| `ACTION_CALL_PHONE` | Open dialer with number |
| `ACTION_SHARE` | System share sheet |

### Telephony (2)
| Type | What it does |
|------|-------------|
| `ACTION_CALL_DIRECT` | Place call directly via `ACTION_CALL` (no a11y needed, dual-SIM via `sim` param) — needs `CALL_PHONE` |
| `ACTION_WAIT_CALL_END` | Block until the call ends (polls `TelephonyManager.callState`) — needs `READ_PHONE_STATE` |

### Data (8)
| Type | What it does |
|------|-------------|
| `ACTION_SET_VARIABLE` | Set workflow variable (string / int / float / bool) |
| `ACTION_TEXT_OPERATION` | Text replace / split / substring / case |
| `ACTION_MATH` | Arithmetic (add / sub / mul / div / mod / pow) |
| `ACTION_CLIPBOARD` | Clipboard read / write |
| `ACTION_PARSE_JSON` | Parse JSON with dot-notation path |
| `ACTION_PARSE_XML` | Parse XML with XPath |
| `ACTION_BASE64` | Base64 encode / decode |
| `ACTION_FILE_OPERATION` | File read / write / delete / exists / list |

### Logic (6)
| Type | What it does |
|------|-------------|
| `CONDITION_SWITCH` | Branch on expression (`branches: {true, false}`) |
| `CONDITION_ADVANCED_SWITCH` | Compare variable (== != > < >= <= contains …) |
| `ACTION_DELAY` | Wait (ms, max 60000) |
| `ACTION_LOOP` | Counted or expression-based loop (`branches: {loop, end}`) |
| `ACTION_TRY_CATCH` | Exception wrapper (`branches: {success, error}`) |
| `ACTION_LOG` | Emit log line (debug / info / warn / error) |

### Sensors (5)
| Type | What it does |
|------|-------------|
| `ACTION_READ_SCREEN` | Search screen content via accessibility |
| `ACTION_READ_SMS` | Read SMS inbox, extract verification codes |
| `ACTION_GET_APP_INFO` | Foreground app package + activity |
| `ACTION_FOREGROUND_APP` | Foreground app (enhanced) |
| `ACTION_APP_SEARCH` | Search installed apps |

### Network (2)
| Type | What it does |
|------|-------------|
| `ACTION_HTTP_REQUEST` | HTTP GET / POST / PUT / DELETE with headers + body |
| `ACTION_NETWORK_STATUS` | Query network type, WiFi IP, mobile IP |

### Media & UI (8)
| Type | What it does |
|------|-------------|
| `ACTION_SCREENSHOT` | Screenshot (base64 or file) |
| `ACTION_TEXT_TO_SPEECH` | TTS (zh / en / ja / ko) |
| `ACTION_TOAST` | Toast message |
| `ACTION_VIBRATE` | Haptic vibration |
| `ACTION_SEND_NOTIFICATION` | System notification |
| `ACTION_DYNAMIC_ISLAND` | Floating heads-up notification |
| `ACTION_MINI_SCREEN` | Floating mini screen: live text / image / progress from a workflow, the agent or an MCP script (draggable, collapsible; moves out of the way of agent taps) |
| `ACTION_WORKSPACE` | Local workspace: write / append / read / list / delete results, notes and images (viewed in the app's Workspace tab, on-device only) |

### AI (1)
| Type | What it does |
|------|-------------|
| `ACTION_AI_CHAT` | Local LLM inference (LiteRT-LM / Gemma 4 2B) |

### Utility (2)
| Type | What it does |
|------|-------------|
| `ACTION_GET_TIME` | Current time (custom format) |
| `ACTION_RANDOM` | Random number (int / float / bool) |

Full parameter reference → [HABITAT_API.md](HABITAT_API.md)
Shared contract (client + server) → `habitat-server/docs/API_CONTRACT.md` in the server repo

---

## Workflow Examples

### SMS Verification Code Auto-Fill

```json
{
  "start_node_id": "read",
  "nodes": {
    "read": {
      "type": "ACTION_READ_SMS",
      "params": { "filter_by": "both", "sender": "code", "extract_code": true },
      "next": "check"
    },
    "check": {
      "type": "CONDITION_SWITCH",
      "params": { "expression": "sms_found == true" },
      "branches": { "true": "copy", "false": "fail" }
    },
    "copy": {
      "type": "ACTION_CLIPBOARD",
      "params": { "action": "set", "text": "${verification_code}" },
      "next": "input"
    },
    "input": {
      "type": "ACTION_INPUT_TEXT",
      "params": { "text": "${verification_code}", "mode": "a11y" }
    },
    "fail": {
      "type": "ACTION_TOAST",
      "params": { "message": "No code found" }
    }
  }
}
```

### One-Tap System Setup

```json
{
  "start_node_id": "s1",
  "nodes": {
    "s1": { "type": "ACTION_WIFI",       "params": { "action": "on" },               "next": "s2" },
    "s2": { "type": "ACTION_BRIGHTNESS", "params": { "action": "set", "value": 80 }, "next": "s3" },
    "s3": { "type": "ACTION_VOLUME",     "params": { "action": "set", "stream": "music", "value": 40 }, "next": "done" },
    "done": { "type": "ACTION_TOAST",    "params": { "message": "Configured" } }
  }
}
```

More examples → `HabitatSamples` in [HabitatJson.kt](lib/src/main/java/com/ailun/habitat/HabitatJson.kt)

---

## DAG Visual Editor

The editor renders workflows as interactive directed acyclic graphs on a custom Canvas view.

| Feature | Detail |
|---------|--------|
| Layout | Sugiyama-style layered auto-layout |
| Node colors | Color-coded by category (interaction / system / data / logic / network / sensor) |
| Edge types | Gray solid (`next`), green (`true` branch), red (`false` branch), orange dashed (loop) |
| Gestures | One-finger pan, two-finger pinch-zoom, double-tap reset, tap a node for details |
| Toggle | Switch between raw JSON editor and DAG visualizer |

Node-level `label` and `description` fields control DAG display labels (optional, fully backward-compatible).

```
EditorTab (Compose)
  ├── OutlinedTextField: name, description, JSON
  ├── Toggle: "JSON" ↔ "DAG Flowchart"
  ├── AndroidView → DagView (Canvas)
  │     ├── DagLayoutEngine (auto-layout)
  │     └── HabitatDagConverter (WorkflowGraph → DagGraph)
  └── Execution logs (LazyColumn)
```

---

## AI Workflow Generation (LLM-friendly, core selling point)

**Natural language → workflow JSON**, generated by DeepSeek (prompt built dynamically from `NodeKnowledge`, incl. an Android operation guide):

```kotlin
WorkflowAIGenerator.generate(context, "call 10086 and record")  // → GeneratedWorkflow(workflowJson, ...)
```

- **13 typical scenarios, 100% generation accuracy** (incl. "dial directly via CALL_DIRECT instead of UI simulation", "repeated text uses region")
- Prompt includes: 86-node list + common package map + priority rule (direct API preferred) + permission prerequisites + few-shot examples
- Per-generation cost ≈ 0.02–0.05 CNY (DeepSeek)

## MCP Mobile Agent (AI controls the phone)

The engine exposes an **MCP server** (JSON-RPC 2.0):

```bash
adb forward tcp:8931 tcp:8931          # the server listens on the phone's 127.0.0.1 by default
ws://127.0.0.1:8931/mcp                # Authorization: Bearer <token> (or ?token=)
```

- `initialize / tools/list / tools/call` standard MCP, any LLM client can connect
- **Auth**: a random token is generated per install (view / copy / regenerate on the App's Agent page); LAN access is off by default; dangerous nodes (shell, files, SMS, calls, …) are refused for remote callers unless explicitly enabled
- supports `region`/`occurrence` to precisely target repeated text (e.g. "tap the confirm button at the bottom")
- WeChat reading: only in the `wechat` build flavor (`./gradlew :app:assembleWechatDebug`), which adds a reader service named `SelectToSpeakService` — WeChat only exposes its UI tree to that class name, so this impersonates a Google component: personal use only, not for store distribution. The default `standard` flavor doesn't include it and falls back to on-device ML Kit OCR
- PC side `scripts/agent_daemon.mjs` (persistent connection) / `mcp_call.mjs` (one-off calls, `--full` for untruncated fields)

### Autonomous mode: planner (DeepSeek) + executor (Jev) + code control

```
goal ─► DeepSeek plans: a few steps, each with a completion condition visible on screen; uses device tools when possible
          ▼
   each step: tool step calls the device directly / UI step lets the executor (Jev / DeepSeek / local) pick one candidate per screen
          │  stuck (looping / no change / low confidence) → replan the rest from the current screen (≤2 times)
          ▼
   DeepSeek review: checks on-screen evidence that the goal is really done; answers question-style goals
```

- **Models choose, code controls**: the executor only picks from code-built candidates; step progress, navigation stack + page graph, loop detection, recovery and safety gates live in code
- **Device tools**: jump straight to 15 settings pages, toggle Wi-Fi / Bluetooth / flashlight / DND, set brightness / volume, read battery / time / device info / network. State-changing tools must be named in the user's own goal
- **Page state**: package + activity + title identify the page; switch on/off, slider %, and input contents go into the candidates; the nav stack tells the model where it is, where Back goes, and which entries were already tried
- **Safety**: planner output is untrusted — tool/arg allow-lists, typed text must be a substring of the goal, payment buttons / permission dialogs are unlocked only by the user's original goal
- **Entry points**: the Agent page (executor Jev / DeepSeek / local, "DeepSeek planning & review" switch); MCP `agent_start` (`goal` / `provider` / `max_steps` / `planner`), `agent_stop`, `agent_status`
- **Jev from mainland China** returns HTTP 451: use a proxy/VPN on the phone, or set "Jev proxy" to `127.0.0.1:<PC proxy port>` and run `adb reverse tcp:<port> tcp:<port>`

**On-device evaluation** (one vivo phone, Android 16; results verified independently via adb / screen reads, not the agent's own DONE). Method, raw data and limitations: [docs/EVAL.md](docs/EVAL.md).

| Executor | with planner | without | date / commit |
|---|:---:|:---:|---|
| Jev | 28/28 | 13/28 | 2026-10-09 · `09b0632`, 7 tasks × 4 runs |
| DeepSeek | 28/28 | 18/28 | 2026-10-09 · `09b0632`, 7 tasks × 4 runs |
| DeepSeek | 10/10 | 7/10 | 2026-10-14 · `9069155`, 10 tasks (adds messaging / calling) |

Tasks: open Bluetooth page, search inside Settings, open About phone, switch Clock to stopwatch, max brightness, ask battery level, ask Android version; since 2026-10-14 also search without quotes, type a QQ message, call a carrier by name. Reproduce with `node scripts/agent_eval.mjs` (tasks in `scripts/agent_eval_tasks.json`). See [docs/AGENT.md](docs/AGENT.md).

## Testing

- Unit tests (JVM, no device): `./gradlew :lib:testDebugUnitTest :app:testDebugUnitTest` (126 tests)
- Engine self-test: `scripts/run_selftest.ps1` (**68 regressions = 53 PASS / 0 FAIL / 15 SKIP**, needs a device)
- Autonomous-mode device eval: `node scripts/agent_eval.mjs` (`scripts/agent_eval_show.mjs` for details)
- LLM generation accuracy: `scripts/verify_workflow_gen.ps1` (13 scenarios)

---

## Local AI (LiteRT-LM)

Habitat bundles Google LiteRT-LM for on-device LLM inference — no network required.

- **Model**: `.litertlm` format, recommended Gemma 4 2B (~2.4 GB)
- **Path**: `/storage/emulated/0/Download/llm/`
- **Permission**: "All files access" needed to read the model file

Use the **LLM Console** (Profile tab → LLM Console) to load models and test prompts.
In workflows, `ACTION_AI_CHAT` calls the loaded model:

```json
{
  "type": "ACTION_AI_CHAT",
  "params": {
    "prompt": "Summarize: ${screen_content}",
    "output_var": "summary"
  },
  "next": "next_step"
}
```

---

## Project Structure

```
Habitat/                          # Root project
├── lib/                          # :lib — engine (zero Android UI deps)
│   └── com/ailun/habitat/
│       ├── api/                  # IAccessibilityProvider, IShellExecutor
│       ├── handlers/             # ~80 INodeHandler implementations
│       ├── HabitatExecutor.kt    # Coroutine-based execution loop
│       ├── HabitatJson.kt        # JSON parser + sample workflows
│       ├── WorkflowGraph.kt      # Graph model (start_node_id + nodes)
│       ├── WorkflowNode.kt       # Node model (id, type, params, next, branches)
│       ├── WorkflowContext.kt    # Per-run state, ${var} interpolation
│       ├── NodeHandlerFactory.kt # Type-string → handler registry
│       └── INodeHandler.kt       # Single-method handler interface
│
├── app/                          # :app — Compose UI, bridges, services
│   └── com/ailun/habitat/app/
│       ├── ui/                   # ProfileTab, HabitatSettingsActivity
│       ├── bridge/               # AppAccessibilityProvider, ShizukuShellExecutor
│       ├── ai/                   # NodeKnowledge(node docs), WorkflowAIGenerator(LLM gen), WorkflowAIClient(DeepSeek), LLMServiceProvider, LiteRtLLMServiceImpl
│       ├── agent/                # autonomous mode: Planner, AgentLoop, ActionCatalog, DeviceTools, NavigationState, ScreenObserver
│       ├── dag/                  # DagView, DagLayoutEngine, DagModel
│       ├── triggers/             # Notification, SMS, Timer, Clipboard, Key
│       ├── HabitatDashboard.kt   # Main scaffold + editor in home card + Agent control entry
│       ├── FloatWindowUI.kt      # Floating overlay ball + panel
│       ├── HabitatFloatService.kt
│       └── HabitatTheme.kt       # Material 3 teal color scheme
│
├── TODO.md                       # Task tracking (client-side)
└── README.md                     # This file
```

### Dependency Decoupling

The engine (`:lib`) has **zero** compile-time dependency on the app module (`:app`).
All OS access goes through two interfaces:

```
INodeHandler
    ↑
    ├── IAccessibilityProvider   (getService, foregroundPackage, foregroundActivity)
    └── IShellExecutor           (exec: suspend (cmd, asRoot) → String)
            ↑
    bridge/  (app module implementations)
    ├── AppAccessibilityProvider   ← weak ref to accessibility service
    └── ShizukuShellExecutor       ← delegates to HabitatShellManager
```

To use the engine in a different app, implement these two interfaces.

---

## Cloud Backend (Habitat Server)

A Spring Boot 3.5 server provides AI-powered workflow generation and web service publishing.
See `habitat-server/README.md` in the server repository for details.

| Feature | Endpoint |
|---------|----------|
| AI workflow generation | `POST /api/v1/workflows/generate` |
| Workflow CRUD | `GET/PUT/DELETE /api/v1/workflows/{id}` |
| API key management | `POST/GET/DELETE /api/v1/keys` |
| Publish as web service | `POST /api/v1/endpoints` |

---

## Permissions

| Feature | Permission |
|---------|-----------|
| Click / swipe / read screen / input | Accessibility Service |
| Read SMS | `READ_SMS` |
| Notifications | `POST_NOTIFICATIONS` |
| Vibrate | `VIBRATE` |
| Shell (system-level) | Shizuku or Root |
| Screenshot | Accessibility Service |
| Flashlight | `CAMERA` |
| Open dialer | None (`ACTION_DIAL`) |
| Direct call (`ACTION_CALL_DIRECT`) | `CALL_PHONE` |
| Wait call end (`ACTION_WAIT_CALL_END`) | `READ_PHONE_STATE` |

---

## Extending

```kotlin
class MyHandler : INodeHandler {
    override suspend fun handle(node: WorkflowNode, ctx: WorkflowContext): String? {
        val param = ctx.interpolate(node.params?.get("key")?.toString() ?: "")
        ctx.variables["result"] = "done"
        return node.next
    }
}

// Register
factory.register("MY_ACTION", MyHandler())
```

> **⚠️ Handler thread constraint**: handlers run on `Dispatchers.Default` (background coroutine thread, no Looper).
> Use **synchronous queries** (e.g. `TelephonyManager.callState`) or bring your own `Executor`/`HandlerThread`;
> **do not use APIs that depend on `Looper.myLooper()`** (e.g. `PhoneStateListener`) — will crash because Looper is null.
> See [docs/ENGINE_EXTENDING.md](docs/ENGINE_EXTENDING.md).

---

## FAQ

| Problem | Solution |
|---------|----------|
| Workflow won't start | Check `start_node_id` exists in `nodes` |
| Variables resolve to `"null"` | Check the variable was set via `ACTION_SET_VARIABLE` |
| Infinite loop | Check loop condition; hard cap is 1000 steps |
| Accessibility actions fail | Confirm accessibility service is running |
| Shell commands return empty | Check Shizuku / root is connected |
| WiFi / Bluetooth operations fail | Android 10+ restrictions may apply |

---

**Habitat** · 86 node types · LLM-friendly · MCP agent · embeddable · accessibility + system API · `habitat-server` companion backend
