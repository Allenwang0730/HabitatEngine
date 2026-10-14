# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build commands

```bash
# Debug APK (all ABIs). Two flavors: `standard` (default, distributable) and
# `wechat` (adds the WeChat reader service that impersonates Google's SelectToSpeakService — personal use only)
./gradlew :app:assembleStandardDebug
./gradlew :app:assembleWechatDebug

# Release APK (signed, minified, with ProGuard)
./gradlew assembleRelease

# Clean rebuild
./gradlew clean :app:assembleStandardDebug

# Build a single module
./gradlew :lib:assembleRelease
```

Output APKs land in `app/build/outputs/apk/<flavor>/<buildType>/`. The debug build uses the default debug keystore automatically. The release build reads signing config from `keystore.properties` at the project root (if the file is missing, no release signing config is created — debug builds still work) — this file and `*.keystore`/`*.jks` are git-ignored. To set up release signing, generate a keystore and create `keystore.properties`:

```
storePassword=<password>
keyPassword=<password>
keyAlias=<alias>
storeFile=app/<name>.keystore
```

Unit tests (JVM, no device needed):

```bash
./gradlew :lib:testDebugUnitTest   # engine: JSON parsing, lint, interpolation, executor
./gradlew :app:testDebugUnitTest   # agent policy / catalog / Jev answer parsing
```

In lib tests, build a `WorkflowContext` with `WorkflowContext.detached()` (no Android Context); `unitTests.isReturnDefaultValues = true` stubs `android.util.Log`. On-device node self-tests: see `docs/SELFTEST.md`.

## Module architecture

```
:app (com.ailun.habitat.app)     ← Android application, Compose UI, bridge impls
:lib (com.ailun.habitat)         ← Pure engine library, handlers, model, API interfaces
```

**`lib` is the core engine** — it defines the workflow model, execution runtime, handler interfaces, and ~80 node handlers. It has zero dependency on the app module (it does depend on the Android framework).

**`:app` is the host** — it provides Compose UI (dashboard, DAG editor, float window, settings), Shizuku bridge, accessibility service, LLM service, and notification handling.

## Engine architecture

The workflow engine follows a simple loop pattern:

```
JSON string → HabitatJson.fromJson() → WorkflowGraph
            → HabitatExecutor.execute(graph, WorkflowContext)
              → loop: node → NodeHandlerFactory.get(type) → handler.handle(node, context) → next node
```

Key classes (all in `lib/src/main/java/com/ailun/habitat/`):

- **`HabitatExecutor`** — Coroutine-based executor. Iterates nodes via `next`/`branches` references, catches handler throwables (a failing node never crashes the app), has a 1000-step limit per branch. Per-node timeout defaults to 30 min (`node_timeout_ms`, `0` = none); `on_error` = `skip`/`stop`; `require` = success-flag check. `ACTION_FORK`/`ACTION_JOIN` are built into the executor. Exposes `execute(graph, context, onLog)` returning a `Job`.
- **`WorkflowGraph`** — Container with `startNodeId: String` and `nodes: Map<String, WorkflowNode>`. Also holds optional `name`/`description`/`category`/`tags` metadata.
- **`WorkflowNode`** — Single node: `id`, `type`, `params`, `next`, `branches`, plus optional `label`/`description` for DAG display.
- **`WorkflowContext`** — Per-execution state. `variables` is a `ConcurrentHashMap` (thread-safe; rejects null values — use `putVariable`, which treats null as remove). `interpolate(text)` replaces `${var}` in a single pass (substituted values are not re-scanned). `log()` emits to both Logcat and an optional callback.
- **`INodeHandler`** — Single-method interface: `suspend fun handle(node: WorkflowNode, context: WorkflowContext): String?`. Returns the next node ID or `null` to stop.
- **`NodeHandlerFactory`** — Registry of `String → INodeHandler`. Built from `ALL_NODE_MODULES` in `NodeModules.kt` (8 modules), instantiated lazily; type constants live in its companion object. New built-in nodes go into `NodeModules.kt`. Handlers receive `IAccessibilityProvider` and/or `IShellExecutor` via constructor injection. `factory.register("TYPE", handler)` is for host overrides (see `app/.../bridge/HabitatFactoryExt.kt`).
- **`HabitatJson`** — Parses JSON strings into `WorkflowGraph` (numbers narrowed to Int/Long/Double). `fromJson` only rejects structurally broken graphs; `HabitatJson.lint(graph, knownTypes)` reports dangling `next`/`branches`/param references, unknown types and unreachable nodes without throwing.
- **`ShellQuote`** — `quote()` / `isPackageName()`. Any parameter/variable value concatenated into a shell command must go through it. `ACTION_SHELL` auto-quotes each `${var}` in `command` (`raw_interpolation: true` opts out).

## Decoupling interfaces (in `lib/.../api/`)

The engine never depends on the app module. Two interfaces bridge the gap:

- **`IAccessibilityProvider`** — `getService(): AccessibilityService?` + `foregroundPackage` / `foregroundActivity` properties. App module implements via `AppAccessibilityProvider` which holds a weak reference to the accessibility service singleton.
- **`IShellExecutor`** — `suspend fun exec(command: String, asRoot: Boolean = false): String`. App module implements via `ShizukuShellExecutor` which delegates to `HabitatShellManager`.

Handlers that need neither interface (pure data/logic nodes) receive `null`. This is how, for example, `NodeSetVariableHandler` works without any OS dependency.

## Handler categories

All handlers live in `lib/src/main/java/com/ailun/habitat/handlers/`. They're grouped into the `NodeModule` entries in `NodeModules.kt`. Shared helpers: `ParamUtils.kt` (`paramInt/paramLong/...` — interpolate then parse, accept numeric strings), `FilePathGuard.kt` (every file-touching node), `HttpUtil.kt`, `LocalShell.kt`. Blocking IO runs on `Dispatchers.IO`; always rethrow `CancellationException`.

## Variable system

- Variables are set via `ACTION_SET_VARIABLE` (params: `name`, `value`, optional `type` for coercion) or automatically by any handler that writes `context.putVariable(outputVar, value)`. `ACTION_SET_VARIABLE` interpolates string values before coercion.
- Referenced in JSON as `${varName}`. Interpolation happens at execution time via `context.interpolate()` on all string params. Undefined variables resolve to the string `"null"`.
- Many handlers write both a primary output variable and a `<type>_success` boolean (e.g., `wifi_success`, `screenshot_success`). Handlers set their `_success` to false on entry so a stale `true` can't satisfy `require`.

## DAG visualization

The app renders workflow graphs as a directed acyclic graph on a Canvas-based `DagView`. The pipeline is:

1. `HabitatDagConverter` → converts `WorkflowGraph` (engine model) to `DagGraph` (render model: `DagNode`/`DagEdge`)
2. `DagLayoutEngine` → Sugiyama-style layered layout, positions nodes/edges automatically
3. `DagView` → custom `View` with Canvas drawing, pinch-to-zoom, pan, double-tap reset, tap-to-select. Edge types: gray solid (next), green solid (true branch), red solid (false branch), orange dashed (loop back — detected by DFS in the converter).

All in `app/src/main/java/com/ailun/habitat/app/dag/`.

## Key dependencies

- **Shizuku** (v13.1.5) — System-level shell execution without root, via `shizuku-api` + `shizuku-provider`
- **LiteRT-LM** (v0.11.0) — Google's on-device LLM inference (Gemma 4 2B), used by `ACTION_AI_CHAT`
- **Compose BOM** (2025.12.01) — UI framework with Material 3
- **AGP 9.0.1, Kotlin 2.3.20** — Build toolchain targeting SDK 36, min SDK 29
- **Gson** (v2.13.2) — JSON parsing for workflow definitions and handler params
- **Ktor (Netty)** — `AgentService` MCP/WS server; **ML Kit** text recognition (WeChat OCR); **OkHttp**; **androidx.security-crypto** (`SecretStore`, `AuthStateManager`)

## Permission model

Handlers use automatic degradation: `ACTION_READ_SMS` falls back from `READ_SMS` content provider to Shizuku shell commands. `ACTION_WIFI` / `ACTION_BLUETOOTH` use `svc wifi|bluetooth` via the injected shell (the framework toggles are no-ops on modern Android). `ACTION_SHELL` prefers Shizuku, falls back to `Runtime.exec()`; `HabitatShellManager` only falls back to `su` when the user picked root mode.

## Security model

- Secrets (API keys, agent token) go through `app/.../auth/SecretStore` (EncryptedSharedPreferences, auto-migrates old plaintext prefs). Never log them.
- `AgentService` (Ktor, port 8931) binds 127.0.0.1 unless the user enables LAN access, requires a random per-install token, and refuses remote calls to `REMOTE_DANGEROUS_TOOLS` unless the user opts in. `NodeSelfTestActivity` is protected by the `DUMP` permission (adb only).
- Autonomous agent (`app/.../agent/`): `IntentParser` first extracts intent + slots by rules (CALL / SEND_MESSAGE; every slot is a substring of the goal — no free-form query rewriting). `SkillPlans` turns complete intents into fixed plans with code-checked completion (`StepCheck`), skipping the LLM planner. Otherwise DeepSeek (`Planner`) only decomposes goals into steps and reviews the end state; the executor (Jev/DeepSeek/local) only picks from code-built candidates (`ActionCatalog`). Planner output is untrusted: tools/args are validated in `DeviceTools.resolve`, input text must be a substring of the user goal, and state-changing device tools require the *user goal* to name them. Phone numbers come only from `ToolScope` (goal digits → contacts → `ServiceNumbers`, resolved on-device, never put in prompts); `call` dials directly only for local-origin goals with CALL_PHONE and never for emergency numbers. 「发送」 (and IME enter on screens showing it) is a sensitive action unlocked by goal aliases like 发消息; remote goals only fill the message. Step progress, `NavigationState` (nav stack + page graph), loop detection and recovery live in `AgentLoop`. Eval: `node scripts/agent_eval.mjs`. Sensitive buttons, credential/payment screens and permission dialogs are gated in `Catalogs`; remote-origin goals can never unlock them. Cover new gates in `AgentPolicyTest` / `AgentIntentTest`.
- Process-level init lives in `HabitatApp` (Application): auth, API client, restoring enabled triggers. Don't reset trigger state from Activity `onCreate`.
