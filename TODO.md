# Habitat (Android) — TODO

> Status legend: `[ ]` pending · `[~]` in progress · `[x]` done  
> Label `[S]` = requires server-side support first (see server TODO.md)

---

## Navigation Structure

Bottom nav: **Workflows** | **AI Chat** | **Profile**

Editor is no longer a tab — it's a screen launched from Workflows or Chat.

```
Workflows Tab ──"编辑"──► EditorScreen (JSON/DAG)
     │                          │
     │ "AI 修改"                 │ "AI 辅助"
     ▼                          ▼
ChatTab ◄───────────────────────┘
(带当前 graphJson)
     │
     │ "打开编辑器"
     ▼
EditorScreen (确认后的 workflow)
```

**Coupling rules:**
- `EditorScreen` receives: `HabitatWorkflow?` (null = new), `onSaved: () -> Unit`
- `ChatTab` receives: `initialGraphJson: String?` (null = new conversation), `conversationId: String?` (resume)
- Chat → Editor data flow: confirm → `workflowId` + `graphJson` → navigate
- Editor → Chat data flow: current `editJson` → navigate
- Tab switching triggers list refresh (no explicit callback needed)

---

## P0 — Client-Server Auth Foundation

Auth is the prerequisite for ALL server features. Without JWT, nothing else works.

- [x] **HabitatApiClient** — Centralized OkHttpClient singleton. Base URL from
      ProfilePrefs, JSON media type, connect/read timeouts (10s/30s). Rebuild
      when server URL changes. All API calls go through this client.
- [x] **AuthStateManager** — Singleton. JWT in `EncryptedSharedPreferences`.
      Expose: `isLoggedIn: StateFlow<Boolean>`, `token: String?`, `userId: String?`,
      `username: String?`. Methods: `login()`, `logout()`, `refreshFromDisk()`.
- [x] **AuthInterceptor (OkHttp)** — Attaches `Authorization: Bearer <token>`
      to all requests EXCEPT `/auth/**` and `/actuator/health`. On 401:
      clear token → emit logged-out event → navigate to LoginScreen.
- [x] **LoginScreen** — Username + password form. Call `POST /auth/login`.
      Success: store JWT + userId + username → navigate back. Error: inline
      message. "Register" link at bottom.
- [x] **RegisterScreen** — Username + email + password + confirm. Client-side
      validation. Call `POST /auth/register`. Success: auto-login → navigate back.
- [x] **ProfileTab auth state** — Logged in: show username + avatar.
      Logged out: show "登录" button → LoginScreen. Logout button clears state.
- [x] **App startup auth check** — Check stored JWT validity on launch.
      Expired → LoginScreen. Valid → proceed.

## P1 — Bottom Nav & Navigation Restructure

Replace Editor tab with AI Chat tab. Editor becomes a reusable screen.

- [ ] **Change bottom nav tabs** — `Workflows("工作流")`, `Chat("AI 对话")`,
      `Profile("个人主页")`. Update `HabitatBottomBar` icons: Chat uses
      `Icons.Filled.Chat` / `Icons.Outlined.Chat`.
- [ ] **Extract EditorScreen from EditorTab** — Move all EditorTab content
      (name, description, JSON editor, DAG toggle, save, test run) into a
      standalone `@Composable fun EditorScreen()`. Accept parameters:
      `workflow: HabitatWorkflow?` (null = create new), `onSaved: () -> Unit`,
      `onBack: () -> Unit`. Add "AI 辅助" button in toolbar → navigates to
      ChatTab with current `editJson`.
- [ ] **WorkflowsTab "编辑" → EditorScreen** — Wire existing edit button to
      navigate to EditorScreen instead of switching to Editor tab. Use
      `remember` state hoisting in Dashboard for `currentEditorWorkflow`.
- [ ] **WorkflowsTab "AI 修改" button** — New button on workflow card (icon:
      `Icons.Outlined.AutoAwesome`). Opens ChatTab with `initialGraphJson` set
      to the workflow's `jsonContent`.
- [ ] **ChatTab receives EditorScreen callback** — After confirm + save,
      ChatTab offers "打开编辑器" button → navigate to EditorScreen with
      the confirmed workflow loaded.

## P1 — Chat-first AI Workflow Generation

Multi-turn conversation for iteratively refining workflows with AI.
Depends on P0 (Auth) + API key management.

- [ ] **SSE Client** — Lightweight SSE parser. Connect to
      `POST /conversations/{id}/messages`, parse `data:` lines. Emit tokens
      to Kotlin `SharedFlow`. Parse final `[DONE]` event with workflow JSON.
      Handle: connection errors (retry with exponential backoff), timeouts
      (120s), 401 (re-auth), 429 (rate limit).
- [ ] **ConversationApiService** — Coroutine-based service class.
      `createConversation()`, `listConversations()`, `getConversation(id)`,
      `sendMessage(id, text) → Flow<SseEvent>`, `confirmConversation(id)`.
      Uses HabitatApiClient.
- [ ] **ChatTab composable** — New top-level tab content.
      - Message list: `LazyColumn`, user messages right-aligned, AI messages
        left-aligned. Auto-scroll to bottom.
      - Streaming text: AI bubble appends tokens in real-time from SSE flow.
      - Workflow preview card: embedded in AI messages when JSON is present.
        Shows node count, start node, first 3 node types. "View DAG" expands
        to full `DagView` overlay.
      - Input bar: `OutlinedTextField` + send button. Disabled while AI
        responding. "Stop" button during streaming.
      - Toolbar: conversation title (editable), "保存工作流" button, back nav.
      - Receives: `initialGraphJson: String?`, `conversationId: String?`.
        When `initialGraphJson` is provided, first system message includes it
        as current state ("帮我修改这个工作流: ...").
- [ ] **Conversation list** — When ChatTab opens without `conversationId`,
      show list of past conversations: title, last message preview, timestamp,
      workflow status badge. Tap to resume. "新建对话" button at top.
- [ ] **Confirm & save flow** — "保存工作流" calls `POST /conversations/{id}/confirm`
      → server saves to `habitat_workflows` table. Also saves locally via
      `WorkflowRepository.upsert()`. Returns `workflowId`. Shows "打开编辑器"
      button → navigate to EditorScreen.
- [ ] **Local fallback** — When server unreachable, banner "服务端不可用，使用本地模型".
      Fall back to on-device LiteRT-LM (Gemma 4 2B) for single-shot generation.
      Indicator chip: ☁️ or 📱.

## P1 — Client-Server Integration

These depend on P0 (Auth). Many also depend on Chat P1 above.

### API Keys

- [x] Local API key management UI (add, toggle, delete) — ProfileTab
- [ ] **Push keys to server** — After adding key locally, call `POST /keys`.
      Conflict dialog if key already exists on server.
- [ ] **Pull keys from server** — On app start / pull-to-refresh, `GET /keys`.
      Merge: server wins for active state; local wins for key value.
- [ ] **Key selector in ChatTab** — Before sending first message, pick which
      API key to use. Shows both local and server keys.

### Workflow Sync

- [ ] **Pull workflow list** — `GET /workflows` on WorkflowsTab enter.
      Sync badges: ☁️ server-only, 📱 local-only, ✅ synced. Pull-to-refresh.
- [ ] **Push on save** — After local save in EditorScreen, if synced:
      `PUT /workflows/{id}`. If new: offer "同步到云端?" dialog.
- [ ] **Delete cascade** — Deleting synced workflow: confirm "同时删除云端?"
      → `DELETE /workflows/{id}`.
- [ ] **Conflict resolution** — Server `updated_at` vs local edit time.
      Server newer → diff dialog. Local newer → push wins.

### Endpoint Publishing

- [ ] **Publish dialog** — From WorkflowsTab, workflow card "..." menu →
      "发布为 API". Dialog: path, method, auth, rate limit. `POST /endpoints`.
- [ ] **Endpoint list in ProfileTab** — Published endpoints with status,
      invocation count, full URL. Tap to copy. Swipe to delete/toggle.

---

## P0 — Engine Completeness

- [x] 37 node handler types
- [x] `${varName}` variable interpolation (recursive, max 5 passes)
- [x] Linear (`next`) and conditional (`branches`) control flow
- [x] Trigger system (notification, SMS, timer, clipboard, key)
- [x] DAG visual preview (Canvas-based, pinch-to-zoom, pan)
- [ ] **ACTION_REGEX** — Regex match/extract. Params: `input`, `pattern`, `output_var`.
- [ ] **ACTION_WAIT_FOR** — Wait for element with timeout. Params: `target`, `timeout_ms`, `poll_interval_ms`.
- [ ] **ACTION_SWITCH** — N-way branch dispatch. Params: `value`, `cases[]`, `branches{}`.
- [ ] **ACTION_FOR_EACH** — Iterate JSON array. Params: `array`, `item_var`, `index_var`, `branches{loop/end}`.

---

## P2 — UI Improvements

- [x] ProfileTab with server config, API keys, stats
- [x] DAG view in editor (Canvas, pinch-to-zoom, pan, tap-to-select)
- [ ] **Extract tabs into separate files** — `WorkflowsTab`, `EditorScreen`,
      `ChatTab`, `HabitatLLMConsoleContent` each in `ui/` package. Dashboard
      should be ~150 lines of Scaffold + nav + state hoisting.
- [ ] **Interactive DAG editor** — Draggable nodes, connectable edges, node
      palette sidebar. Visual-first mode (JSON as fallback).
- [ ] **Workflow version history UI** — Timeline view, side-by-side diff, rollback.

---

## P3 — Polish & Testing

- [ ] Unit tests: `HabitatJson.fromJson()`, `WorkflowContext.interpolate()`,
      `NodeHandlerFactory` registration completeness.
- [ ] Integration tests: end-to-end with sample JSONs.
- [ ] Error recovery in executor: snap context on exception, retry/skip/abort.
- [ ] 1000-step limit configurability: workflow-level parameter (default 1000, max 5000).

---

## Planned Node Type Additions

| Node | Control | Priority | Reason |
|------|---------|----------|--------|
| `ACTION_REGEX` | `next` only | P0 | AI generates regex well; SMS/HTTP/screen parsing is top-3 use case |
| `ACTION_WAIT_FOR` | `next` only | P0 | Compresses 3-node poll loop into 1 |
| `ACTION_SWITCH` | `branches` (N keys) | P0 | Avoids deep chaining of binary switches |
| `ACTION_FOR_EACH` | `branches` (loop/end) | P0 | Array iteration from `PARSE_JSON` output |

---

## Client ↔ Server API Cross-Reference

### Client depends on Server for:

| Feature | Server Endpoint | Status |
|---------|----------------|--------|
| Register | `POST /auth/register` | [x] |
| Login | `POST /auth/login` | [x] |
| Add API key | `POST /keys` | [x] local; [ ] sync |
| List API keys | `GET /keys` | [x] local; [ ] pull |
| AI generate (legacy one-shot) | `POST /workflows/generate` | [ ] |
| Chat conversation - new | `POST /conversations` | [ ] |
| Chat conversation - list | `GET /conversations` | [ ] |
| Chat conversation - detail | `GET /conversations/{id}` | [ ] |
| Chat conversation - send | `POST /conversations/{id}/messages` | [ ] |
| Chat conversation - confirm | `POST /conversations/{id}/confirm` | [ ] |
| Pull workflows | `GET /workflows` | [ ] |
| Push workflow | `PUT /workflows/{id}` | [ ] |
| Publish endpoint | `POST /endpoints` | [ ] |
| List endpoints | `GET /endpoints` | [ ] |

### Server depends on Client for:

| Feature | Client Component | Status |
|---------|-----------------|--------|
| Execute workflow remotely | HabitatExecutor | [ ] |
| Device online check | FCM token registration | [ ] |
| Result callback | HTTP callback to server | [ ] |

---

## Interface Change Log

| Date | Change | Files Affected |
|------|--------|---------------|
| 2026-05-31 | Bottom nav: Editor → AI Chat tab. Editor becomes Screen, reused from Workflows and Chat | `HabitatDashboard.kt`, `ui/EditorScreen.kt` (new), `ui/ChatTab.kt` (new) |
| 2026-05-31 | Restructured TODO: Auth as P0 prerequisite, expanded integration subtasks | TODO.md |
| 2026-05-30 | Added ProfileTab (server config, local API keys, stats, quick actions) | `ui/ProfileTab.kt` (new), `HabitatDashboard.kt` (modified) |
