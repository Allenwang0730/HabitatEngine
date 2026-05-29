# Habitat — JSON-Driven Android Automation Engine

> Lightweight, AI-friendly, 40+ node types. Define workflows as flat JSON — designed for
> LLM generation, not hand-authoring.

[![Kotlin](https://img.shields.io/badge/Kotlin-2.3.20-purple)]()
[![Min SDK](https://img.shields.io/badge/API-29+-green)]()
[![Nodes](https://img.shields.io/badge/Nodes-40+-blue)]()
[![Compose](https://img.shields.io/badge/UI-Compose_M3-orange)]()

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

## Node Types (40+)

### Interaction (6)
| Type | What it does |
|------|-------------|
| `ACTION_CLICK` | Click coordinates or accessibility element |
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

### Media & UI (6)
| Type | What it does |
|------|-------------|
| `ACTION_SCREENSHOT` | Screenshot (base64 or file) |
| `ACTION_TEXT_TO_SPEECH` | TTS (zh / en / ja / ko) |
| `ACTION_TOAST` | Toast message |
| `ACTION_VIBRATE` | Haptic vibration |
| `ACTION_SEND_NOTIFICATION` | System notification |
| `ACTION_DYNAMIC_ISLAND` | Floating heads-up notification |

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
| Gestures | One-finger pan, two-finger pinch-zoom (0.3×–3.0×), double-tap reset, tap to select |
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
│       ├── handlers/             # 40+ INodeHandler implementations
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
│       ├── ai/                   # LLMServiceProvider, LiteRtLLMServiceImpl
│       ├── dag/                  # DagView, DagLayoutEngine, DagModel
│       ├── triggers/             # Notification, SMS, Timer, Clipboard, Key
│       ├── HabitatDashboard.kt   # Main scaffold + 3-tab navigation
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
| Dial | None (uses `ACTION_DIAL`) |

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

**Habitat** · 40+ node types · AI-friendly flat JSON · `habitat-server` companion backend
