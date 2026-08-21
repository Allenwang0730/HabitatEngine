# Habitat 引擎扩展指南（开发者文档）

本文档面向**给引擎添加新节点/Handler** 的开发者。工作流的 JSON 结构见 `habitat_api_knowledge.md`（也是 LLM 生成的依据）。

## 核心：Handler 的线程约束（最重要）

**引擎执行工作流用的是 `Dispatchers.Default`（后台协程线程，没有 Android Looper）：**

```kotlin
// HabitatExecutor.kt
private val executorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
```

因此 Handler 运行在**无 Looper 的后台线程**上。这条约束决定了你能用什么 Android API：

| ❌ 不能用 | ✅ 应该用 |
|-----------|-----------|
| `PhoneStateListener` + `TelephonyManager.listen()` | 同步查询，如 `TelephonyManager.callState` |
| 依赖 `Handler(Looper.myLooper())` 的 API（在无 Looper 线程上 `new Handler()` 会因 `Looper.mQueue` 为 null 崩溃）| 自带 `HandlerThread` / `Executor` 承载回调 |
| 依赖主线程 Looper 派发的注册 | `registerTelephonyCallback(Executor, ...)`（用 Executor，不需 Looper） |

**经验教训**：`ACTION_WAIT_CALL_END` 第一版用 `PhoneStateListener` 直接注册 → 无 Looper 崩溃；第二版包 `HandlerThread` → vivo 上 `tm.listen` 内部仍无 Looper；最终改用**轮询 `tm.callState`**（同步查询）才稳定。

**结论**：Handler 内优先用**同步 API 轮询**，其次才用自带 Looper/Executor 的回调方案。

## 添加一个新节点的标准流程

1. **写 Handler**（`lib/src/main/java/com/ailun/habitat/handlers/`）：
   - 实现 `INodeHandler.handle(node, context): String?`，返回 `node.next`（或 `node.branches[...]`）。
   - 输出写进 `context.variables`（供条件节点/插值用）。
2. **注册**（`NodeHandlerFactory.kt`）：`register(TYPE, Handler())` + 在 companion 加 `const val TYPE`。
3. **同步三处**（缺一不可）：
   - 另一份引擎拷贝（SmartCS 的 `:lib` 或 Habitat 的 `:lib`，两份保持一致）。
   - Java 后端白名单：`WorkflowGenerationService.VALID_NODE_TYPES` 与 `WorkflowJsonValidator.VALID_NODE_TYPES`。
   - RAG 知识库：`habitat_api_knowledge.md` + `RAGService` 的硬编码列表与 few-shot 示例。
4. **可加参数文档**：在知识库文档里写明 params 与输出变量。

## 节点分类

- **Action**：做一件事并返回 `next`（如 `ACTION_CLICK`、`ACTION_CALL_DIRECT`）。
- **条件**：`CONDITION_SWITCH`，按 `branches` 分流。
- **状态读取**：同步查一个值写进变量（如 `ACTION_GET_BATTERY`、`ACTION_GET_CALL_STATE`）。
- **事件等待（新类别）**：挂起工作流协程直到外部条件满足（如 `ACTION_WAIT_CALL_END`、`ACTION_WAIT_FOR_ELEMENT`）。实现上必须遵守线程约束（轮询或自带 Executor）。

## 两个已新增节点

### ACTION_CALL_DIRECT（直接拨出）
- params：`phone_number`（必填，支持 `${var}`）、`sim`（可选 `auto`/`1`/`2`，双卡）
- 输出：`call_success`
- 权限：`CALL_PHONE`
- 实现要点：`Intent.ACTION_CALL` + `FLAG_ACTIVITY_NEW_TASK`（从 application context 启动必须加）；双卡用 `TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE`。

### ACTION_WAIT_CALL_END（等待挂断）
- params：`timeout_ms`（默认 600000）
- 输出：`call_ended`
- 权限：`READ_PHONE_STATE`
- 实现要点：轮询 `TelephonyManager.callState`，状态从"通话中"变"空闲"即返回（同步查询，无 Looper 依赖）。

## 引擎向 App 返还内容

`HabitatExecutionService.start(...)` 提供了三种返还通道：

| 通道 | 说明 |
|------|------|
| `onResult: (Map<String, Any?>) -> Unit` | **工作流结束后的全部变量快照**（节点输出：`screen_text`、`http_response`、`file_result` 等）。这是拿到工作流"产出"的正式通道 |
| `onComplete: () -> Unit` | 完成信号（在 onResult 之后触发）|
| `onLog: (String) -> Unit` | 日志行 |

注意：`onResult` 在 `onComplete` **之前**触发，App 可在 onComplete 里基于 onResult 的数据做后处理。

## 与触发器的关系

引擎的**触发器**（notification/sms/timer/clipboard/key）是"事件 → 启动工作流"；**事件等待节点**是"运行中的工作流内暂停到条件满足"。二者互补。可考虑扩展 `call_end` 触发器（app 侧监听电话状态 → 触发收尾工作流）。
