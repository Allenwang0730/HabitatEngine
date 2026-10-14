package com.ailun.habitat.app

import com.ailun.habitat.NodeHandlerFactory
import com.ailun.habitat.WorkflowGraph
import com.ailun.habitat.app.dag.DagEdge
import com.ailun.habitat.app.dag.DagEdgeType
import com.ailun.habitat.app.dag.DagGraph
import com.ailun.habitat.app.dag.DagNode
import com.ailun.habitat.app.ui.theme.NodeCategories
import com.ailun.habitat.app.ui.theme.NodeCategory

/**
 * WorkflowGraph → DagGraph。
 * 从 startNodeId 做 DFS：指向递归栈中节点的边标为 BACK_EDGE（循环写法 next 指回前面节点），
 * 布局时忽略这些边，避免 Kahn 拓扑排序因环而漏掉节点。
 */
object HabitatDagConverter {

    /** 参数中引用其他节点的键（与 lib WorkflowLint 保持一致）。 */
    private val PARAM_REFS: Map<String, List<Pair<String, String>>> = mapOf(
        NodeHandlerFactory.ACTION_FORK to listOf("targets" to "并行"),
        NodeHandlerFactory.ACTION_LOOP to listOf("body_node" to "循环体"),
        NodeHandlerFactory.ACTION_RETRY to listOf("target" to "重试"),
    )

    fun convert(graph: WorkflowGraph): DagGraph {
        val nodeMap = graph.nodes ?: return DagGraph(emptyList(), emptyList())
        val startId = graph.startNodeId?.takeIf { it in nodeMap }

        val nodes = nodeMap.entries.mapIndexed { index, (nodeId, wn) ->
            val type = wn.type ?: "UNKNOWN"
            val isStart = nodeId == startId
            DagNode(
                stepId = nodeId,
                stepIndex = index,
                moduleId = type,
                label = wn.label?.takeIf { it.isNotBlank() }
                    ?: wn.description?.takeIf { it.isNotBlank() }
                    ?: NodeCategories.displayName(type),
                moduleName = NodeCategories.displayName(type),
                category = NodeCategories.of(type),
                isTrigger = isStart,
                source = wn,
            )
        }

        val raw = mutableListOf<DagEdge>()
        for ((nodeId, wn) in nodeMap) {
            val next = wn.next
            if (!next.isNullOrBlank() && next in nodeMap) {
                raw += DagEdge(nodeId, next, DagEdgeType.SEQUENTIAL)
            }
            wn.branches?.forEach { (branchName, targetId) ->
                if (!targetId.isNullOrBlank() && targetId in nodeMap) {
                    raw += DagEdge(nodeId, targetId, branchType(branchName), label = branchName)
                }
            }
            PARAM_REFS[wn.type]?.forEach { (key, label) ->
                parseIds(wn.params?.get(key)).filter { it in nodeMap }.forEach { target ->
                    raw += DagEdge(nodeId, target, DagEdgeType.JUMP, label = label)
                }
            }
        }
        val edges = raw.distinctBy { Triple(it.fromNodeId, it.toNodeId, it.type) }

        // DFS 着色：0 未访问 / 1 在栈中 / 2 完成
        val out = edges.groupBy { it.fromNodeId }
        val color = HashMap<String, Int>()
        val back = HashSet<DagEdge>()
        fun dfs(id: String) {
            color[id] = 1
            for (e in out[id].orEmpty()) {
                when (color[e.toNodeId] ?: 0) {
                    0 -> dfs(e.toNodeId)
                    1 -> back += e
                }
            }
            color[id] = 2
        }
        startId?.let { dfs(it) }
        for (id in nodeMap.keys) if ((color[id] ?: 0) == 0) dfs(id)

        val finalEdges = edges.map { e ->
            if (e in back) e.copy(type = DagEdgeType.BACK_EDGE, baseType = e.type) else e
        }
        return DagGraph(nodes, finalEdges, startId)
    }

    private fun branchType(name: String): DagEdgeType = when (name.trim().lowercase()) {
        "true", "yes", "success", "ok", "1", "then" -> DagEdgeType.BRANCH_TRUE
        "false", "no", "failure", "fail", "error", "0", "else", "default", "catch" -> DagEdgeType.BRANCH_FALSE
        else -> DagEdgeType.SEQUENTIAL
    }

    /** targets 可能是 List、JSON 数组字符串或逗号分隔字符串。 */
    private fun parseIds(value: Any?): List<String> = when (value) {
        null -> emptyList()
        is Collection<*> -> value.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
        else -> value.toString().trim().removePrefix("[").removeSuffix("]")
            .split(',')
            .map { it.trim().trim('"', '\'').trim() }
            .filter { it.isNotEmpty() }
    }

    /** 兼容旧调用：类别枚举。 */
    fun categoryOf(type: String?): NodeCategory = NodeCategories.of(type)
}
