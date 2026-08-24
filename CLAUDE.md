# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build commands

```bash
# Debug APK (all ABIs)
./gradlew assembleDebug

# Release APK (signed, minified, with ProGuard)
./gradlew assembleRelease

# Clean rebuild
./gradlew clean assembleDebug

# Build a single module
./gradlew :app:assembleDebug
./gradlew :lib:assembleRelease
```

Output APKs land in `app/build/outputs/apk/<variant>/`. The debug build uses the default debug keystore automatically. The release build reads signing config from `keystore.properties` at the project root — this file and `*.keystore`/`*.jks` are git-ignored. To set up release signing, generate a keystore and create `keystore.properties`:

```
storePassword=<password>
keyPassword=<password>
keyAlias=<alias>
storeFile=app/<name>.keystore
```

There is no test suite to run. The project has `junit` in the version catalog but no test source files exist.

## Module architecture

```
:app (com.ailun.habitat.app)     ← Android application, Compose UI, bridge impls
:lib (com.ailun.habitat)         ← Pure engine library, handlers, model, API interfaces
```

**`lib` is the core engine** — it defines the workflow model, execution runtime, handler interfaces, and 40+ node handlers. It has zero dependency on the app module.

**`:app` is the host** — it provides Compose UI (dashboard, DAG editor, float window, settings), Shizuku bridge, accessibility service, LLM service, and notification handling.

## Engine architecture

The workflow engine follows a simple loop pattern:

```
JSON string → HabitatJson.fromJson() → WorkflowGraph
            → HabitatExecutor.execute(graph, WorkflowContext)
              → loop: node → NodeHandlerFactory.get(type) → handler.handle(node, context) → next node
```

Key classes (all in `lib/src/main/java/com/ailun/habitat/`):

- **`HabitatExecutor`** — Coroutine-based executor. Iterates nodes via `next`/`branches` references, catches handler exceptions, has a 1000-step limit. Exposes `execute(graph, context, onLog)` returning a `Job`.
- **`WorkflowGraph`** — Container with `startNodeId: String` and `nodes: Map<String, WorkflowNode>`. Also holds optional `name`/`description`/`category`/`tags` metadata.
- **`WorkflowNode`** — Single node: `id`, `type`, `params`, `next`, `branches`, plus optional `label`/`description` for DAG display.
- **`WorkflowContext`** — Per-execution state. `variables` is a `ConcurrentHashMap` (thread-safe). `interpolate(text)` replaces `${var}` patterns recursively (max 5 passes). `log()` emits to both Logcat and an optional callback.
- **`INodeHandler`** — Single-method interface: `suspend fun handle(node: WorkflowNode, context: WorkflowContext): String?`. Returns the next node ID or `null` to stop.
- **`NodeHandlerFactory`** — Registry of `String → INodeHandler`. All 40+ type constants are defined in its companion object. Handlers receive `IAccessibilityProvider` and/or `IShellExecutor` via constructor injection. New handlers are registered with `factory.register("TYPE", handler)`.
- **`HabitatJson`** — Parses JSON strings into `WorkflowGraph`, applies default values for missing fields.

## Decoupling interfaces (in `lib/.../api/`)

The engine never depends on the app module. Two interfaces bridge the gap:

- **`IAccessibilityProvider`** — `getService(): AccessibilityService?` + `foregroundPackage` / `foregroundActivity` properties. App module implements via `AppAccessibilityProvider` which holds a weak reference to the accessibility service singleton.
- **`IShellExecutor`** — `suspend fun exec(command: String, asRoot: Boolean = false): String`. App module implements via `ShizukuShellExecutor` which delegates to `HabitatShellManager`.

Handlers that need neither interface (pure data/logic nodes) receive `null`. This is how, for example, `NodeSetVariableHandler` works without any OS dependency.

## Handler categories

All handlers live in `lib/src/main/java/com/ailun/habitat/handlers/`. They're organized into 10 categories registered in `NodeHandlerFactory`'s `init` block: Logic control (6), Variables & data (8), Interaction (6), System control (12), Media (2), Network (2), Utilities (2), Sensors (5), UI (4), AI (1).

## Variable system

- Variables are set via `ACTION_SET_VARIABLE` (params: `name`, `value`, optional `type` for coercion) or automatically by any handler that writes to `context.variables[outputVar]`.
- Referenced in JSON as `${varName}`. Interpolation happens at execution time via `context.interpolate()` on all string params. Undefined variables resolve to the string `"null"`.
- Many handlers write both a primary output variable and a `<type>_success` boolean (e.g., `wifi_success`, `screenshot_success`).

## DAG visualization

The app renders workflow graphs as a directed acyclic graph on a Canvas-based `DagView`. The pipeline is:

1. `HabitatDagConverter` → converts `WorkflowGraph` (engine model) to `DagGraph` (render model: `DagNode`/`DagEdge`)
2. `DagLayoutEngine` → Sugiyama-style layered layout, positions nodes/edges automatically
3. `DagView` → custom `View` with Canvas drawing, pinch-to-zoom (0.3×–3.0×), pan, double-tap reset, tap-to-select. Edge types: gray solid (next), green solid (true branch), red solid (false branch), orange dashed (loop back).

All in `app/src/main/java/com/ailun/habitat/app/dag/`.

## Key dependencies

- **Shizuku** (v13.1.5) — System-level shell execution without root, via `shizuku-api` + `shizuku-provider`
- **LiteRT-LM** (v0.11.0) — Google's on-device LLM inference (Gemma 4 2B), used by `ACTION_AI_CHAT`
- **Compose BOM** (2025.12.01) — UI framework with Material 3
- **AGP 9.0.1, Kotlin 2.3.20** — Build toolchain targeting SDK 36, min SDK 29
- **Gson** (v2.13.2) — JSON parsing for workflow definitions and handler params

## Permission model

Handlers use automatic degradation: `ACTION_READ_SMS` falls back from `READ_SMS` content provider to Shizuku shell commands. `ACTION_WIFI` falls back from `WifiManager` API to `svc wifi` shell command. `ACTION_SHELL` prefers Shizuku, falls back to `Runtime.exec()`.
