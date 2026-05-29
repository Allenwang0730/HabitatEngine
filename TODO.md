# Habitat (Android) — TODO

> Status legend: `[ ]` pending · `[~]` in progress · `[x]` done  
> Label `[S]` = requires server-side support first (see server TODO.md)

---

## P0 — Engine Completeness

- [x] 37 node handler types (interaction, system, data, logic, network, sensor, media, UI, AI)
- [x] `${varName}` variable interpolation (recursive, max 5 passes)
- [x] Linear (`next`) and conditional (`branches`) control flow
- [x] Trigger system (notification, SMS, timer, clipboard, key)
- [x] DAG visual preview (Canvas-based, pinch-to-zoom, pan)
- [ ] **ACTION_REGEX** — Regex match/extract from text strings.
      Params: `input`, `pattern`, `output_var`. Outputs the first capture group
      or full match. Only `next` — linear data transform node.
- [ ] **ACTION_WAIT_FOR** — Wait for element on screen with timeout.
      Params: `target` (text/selector), `timeout_ms`, `poll_interval_ms` (default 500).
      Only `next` — loop is internal to handler.
- [ ] **ACTION_SWITCH** — Multi-branch dispatch (N-way switch).
      Params: `value`, `cases` (string array). Uses existing `branches` map
      (each case name → node ID). Same structure as `CONDITION_SWITCH`.
- [ ] **ACTION_FOR_EACH** — Iterate over JSON array.
      Params: `array` (JSON array string), `item_var`, `index_var` (optional).
      Branches: `{"loop": "...", "end": "..."}`. Same structure as `ACTION_LOOP`.

## P1 — Client-Server Integration

- [x] ProfileTab with server URL config and connection test
- [x] Local API key management UI (add, toggle, delete)
- [ ] **[S] Auth flow** — Register / Login screens or dialogs. Store JWT in
      EncryptedSharedPreferences. Auto-attach `Authorization: Bearer <token>`
      to all server requests.
- [ ] **[S] Workflow sync** — Pull workflow list from server. Push local
      workflows. UI indicator for sync status (synced / local-only / server-only).
- [ ] **[S] AI generation in-app** — "Generate with AI" button in EditorTab.
      Sends prompt → server → gets back graphJson → loads into editor.
- [ ] **[S] API key sync** — Push keys from ProfileTab to server, pull server
      keys to local. Conflict resolution: server wins for active/inactive state,
      local wins for key value (server stores encrypted version anyway).
- [ ] **[S] Publish workflow as endpoint** — UI to configure endpoint path,
      method, auth requirements, rate limit. Push to server.
- [ ] **[S] Endpoint invocation from server** — Receive FCM push when a
      published endpoint is called. Execute the workflow and POST result back.

## P2 — UI Improvements

- [x] Three-tab bottom navigation: Workflows, Editor, Profile
- [x] ProfileTab with server config, API keys, stats, quick actions
- [ ] **Extract tabs into separate files** — Move `WorkflowsTab`, `EditorTab`,
      `HabitatLLMConsoleContent` out of `HabitatDashboard.kt` into `ui/` package.
      Dashboard should be ~150 lines of Scaffold + state hoisting only.
- [ ] **Interactive DAG editor** — Make `DagView` nodes draggable, edges
      connectable. Add a node palette sidebar. Replace raw JSON editor with
      visual-first mode (keep JSON as fallback).
- [ ] **Workflow version history UI** — Timeline view of previous versions.
      Side-by-side diff of graphJson. One-tap rollback.

## P3 — Polish & Testing

- [ ] **Unit tests** — `HabitatJson.fromJson()`, `WorkflowContext.interpolate()`,
      `NodeHandlerFactory` registration completeness.
- [ ] **Integration tests** — End-to-end with sample JSONs (GET_VERIFICATION_CODE,
      SCREENSHOT_WORKFLOW, SCREEN_MONITOR_WORKFLOW).
- [ ] **Error recovery in executor** — On handler exception: snap context state,
      offer retry/skip/abort. `ACTION_TRY_CATCH` already exists but only does
      branching without state snapshots.
- [ ] **1000-step limit configurability** — Make the step limit a workflow-level
      parameter (default 1000, max 5000).

---

## Planned Node Type Additions

These four nodes are pure incremental additions — they use existing `next`/`branches`
without changing `WorkflowNode`, `WorkflowGraph`, or `HabitatExecutor`:

| Node | Control | Priority | Reason |
|------|---------|----------|--------|
| `ACTION_REGEX` | `next` only | P0 | AI generates regex well; SMS/HTTP/screen parsing is top-3 use case |
| `ACTION_WAIT_FOR` | `next` only | P0 | Compresses 3-node poll loop into 1; AI frequently writes timeout logic |
| `ACTION_SWITCH` | `branches` (N keys) | P0 | Avoids deep chaining of binary switches; AI gets lost in true/false chains |
| `ACTION_FOR_EACH` | `branches` (loop/end) | P0 | Array iteration from `PARSE_JSON` output; no equivalent exists |

---

## Client ↔ Server API Cross-Reference

### Client depends on Server for:

| Feature | Server Endpoint | Client Status |
|---------|----------------|---------------|
| Register | `POST /auth/register` | UI needed |
| Login | `POST /auth/login` | UI needed |
| Add API key | `POST /keys` | ProfileTab has local-only UI; sync needed |
| List API keys | `GET /keys` | ProfileTab has local-only UI; sync needed |
| AI generate workflow | `POST /workflows/generate` | Button needed in EditorTab |
| Pull workflows | `GET /workflows` | WorkspacesTab needs sync mode |
| Push workflow | `PUT /workflows/{id}` | EditorTab save needs server target |
| Publish endpoint | `POST /endpoints` | UI needed |
| List endpoints | `GET /endpoints` | ProfileTab needs section |

### Server depends on Client for:

| Feature | Client Component | Status |
|---------|-----------------|--------|
| Execute workflow remotely | HabitatExecutor | Not yet — endpoint invocation returns graph, doesn't run it |
| Device online check | FCM token registration | Not yet — FCM not integrated |
| Result callback | HTTP callback to server | Not yet |

---

## Interface Change Log

| Date | Change | Files Affected |
|------|--------|---------------|
| 2026-05-30 | Added ProfileTab (server config, API keys, stats, quick actions) | `ui/ProfileTab.kt` (new), `HabitatDashboard.kt` (modified) |
| 2026-05-30 | Renamed bottom nav tab "我的" → "个人主页" | `HabitatDashboard.kt` |
| 2026-05-30 | Removed `AccountTab`, `AccountMenuItem` composables | `HabitatDashboard.kt` |
| 2026-05-30 | Planned: 4 new node types (REGEX, WAIT_FOR, SWITCH, FOR_EACH) | `NodeHandlerFactory.kt`, `handlers/` |
