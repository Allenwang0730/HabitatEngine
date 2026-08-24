# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 🔒 敏感信息铁律（反例：DeepSeek API key 硬编码导致泄露事故，已重写 git 历史清除）**绝不把任何密钥/token/机密硬编码进代码或提交到 git。** 这是本项目的硬性红线。

- **API key / token / password / secret** —— 一律：
  - 通过**环境变量**（如 `DEEPSEEK_API_KEY`）或在 App 内**运行时配置**（SharedPreferences/设置界面）注入，**不落代码**。
  - 示例：`WorkflowAIClient.getApiKey()` 从配置读、未配置返回 null 并引导用户；禁止 `const val DEFAULT_API_KEY = "sk-..."` 这类写法。
- **`.gitignore`** 已包含 `.env`/`*.secret`/`*.pem`/`*.jks` 等敏感模式；**不要**用 `git add -f` 强制加入被忽略的敏感文件。
- **提交前**：`git commit` 会触发 `scripts/scan_secrets.sh`（pre-commit hook），命中 `sk-...`/`BEGIN PRIVATE KEY`/`AKIA...`/`ghp_...` 等疑似密钥会**拒绝提交**。提交前可手动跑 `sh scripts/scan_secrets.sh` 自查。
- **若发现密钥已提交/推送到远程**：立即用 `git filter-repo --replace-text` 清除**全部历史**并**强推覆盖远程**；同时**在服务方（如 DeepSeek 后台）作废该密钥**（git 清除不能使已泄露的 key 失效）。
- 违反红线会重演"DeepSeek key 泄露 + 重写 52 个提交历史"的事故，**不可再犯**。

## ⚙️ 权限声明铁律（反例：CALL_PHONE 漏声明导致直拨不可用，排查多轮）

**任何 handler/节点用到的运行时权限，必须在 app `AndroidManifest.xml` 里 `<uses-permission>` 声明**，否则系统不认为 App 有该权限 → 运行时授权框不出现、`pm grant` 无效、功能**静默失败**。

- 反例：`ACTION_CALL_DIRECT` 直拨需 `CALL_PHONE`，但 Habitit manifest 漏声明 → 系统给不了权限 → 直拨必败（而 SmartCS 声明了就能拨）。用户看到的"电话权限开"其实是 `READ_PHONE_STATE`（读状态），不是 `CALL_PHONE`（拨号）——**两个不同权限**。
- **新增/改动节点时**：检查 handler 用到的权限是否已在 manifest 声明（CALL_PHONE / READ_CALL_LOG / SEND_SMS / RECORD_AUDIO / CAMERA / 定位 / 通知 等）。
- **验证**：真机装后 `adb shell dumpsys package <pkg> | grep '<PERM>: granted'` 确认可授予；`pm grant`（debug 构建）可强制授予。
- 危险权限（CALL_PHONE/READ_PHONE_STATE/CAMERA/RECORD_AUDIO/定位/READ_SMS 等）需运行时授权（弹框/设置页手授权）。

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
