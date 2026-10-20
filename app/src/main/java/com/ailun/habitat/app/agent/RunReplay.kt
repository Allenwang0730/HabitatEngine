package com.ailun.habitat.app.agent

import com.google.gson.GsonBuilder

/**
 * 「把这次 Agent 运行存成工作流」：成功执行过的动作按顺序编成工作流 JSON，
 * 以后同样的事直接跑工作流，不用再调模型。
 *
 * - 点击优先按控件文字（`ACTION_CLICK target=文字`），坐标只在没有文字时用：换了分辨率/布局也能点到。
 * - 只读工具（读电量等）和写小屏/工作区不编进去：它们的结果只对当时的 Agent 有用。
 * - 每步 `on_error=stop` + `require=<成功标志>`：某一步没做成就停下，不会接着乱点。
 */
data class RecordedAction(
    /** 节点类型（ACTION_CLICK / ACTION_INPUT_TEXT / ACTION_LAUNCH_APP …）。 */
    val node: String,
    val params: Map<String, Any>,
    val label: String,
    /** 点击目标的控件文字（来自候选）；有就按文字点。 */
    val matchText: String? = null,
    val successFlag: String? = null,
)

object RunReplay {
    /** 不改界面、或只对当次 Agent 有意义的节点。 */
    private val SKIP_NODES = setOf(
        "ACTION_GET_BATTERY", "ACTION_GET_TIME", "ACTION_GET_DEVICE_INFO", "ACTION_NETWORK_STATUS",
        "ACTION_MINI_SCREEN", "ACTION_WORKSPACE", "ACTION_DELAY",
    )

    /** 两步之间留给界面转场的时间。 */
    const val STEP_DELAY_MS = 1200

    fun replayable(actions: List<RecordedAction>): List<RecordedAction> = actions.filter { it.node !in SKIP_NODES }

    /** 编成工作流 JSON（`HabitatJson.fromJson` 可解析）；没有可回放的动作返回 null。 */
    fun toWorkflowJson(actions: List<RecordedAction>): String? {
        val steps = replayable(actions)
        if (steps.isEmpty()) return null
        val nodes = linkedMapOf<String, Map<String, Any>>()
        steps.forEachIndexed { i, a ->
            val id = "s${i + 1}"
            val params = paramsFor(a).toMutableMap()
            params["on_error"] = "stop"
            a.successFlag?.let { params["require"] = it }
            val next = if (i < steps.lastIndex) "w${i + 1}" else null
            nodes[id] = buildMap {
                put("id", id)
                put("type", a.node)
                put("label", a.label.take(40))
                put("params", params)
                next?.let { put("next", it) }
            }
            if (next != null) {
                nodes[next] = mapOf(
                    "id" to next, "type" to "ACTION_DELAY",
                    "params" to mapOf("millis" to STEP_DELAY_MS), "next" to "s${i + 2}",
                )
            }
        }
        return gson.toJson(mapOf("start_node_id" to "s1", "nodes" to nodes))
    }

    /** 点击：有控件文字就按文字点（去掉坐标）；其它节点原样。 */
    internal fun paramsFor(a: RecordedAction): Map<String, Any> {
        val text = a.matchText?.trim()?.takeIf { it.isNotEmpty() }
        if (a.node == "ACTION_CLICK" && text != null) return a.params + ("target" to text)
        return a.params
    }

    fun workflowName(goal: String): String = "Agent：" + goal.replace(Regex("\\s+"), " ").trim().take(30)

    private val gson = GsonBuilder().disableHtmlEscaping().create()
}
