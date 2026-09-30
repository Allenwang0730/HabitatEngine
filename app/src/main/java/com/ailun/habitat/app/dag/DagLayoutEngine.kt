package com.ailun.habitat.app.dag

/**
 * Sugiyama 风格分层布局（自上而下）：
 * 1. 最长路径分层（忽略 BACK_EDGE），环内未分层的节点兜底放到最后一层；
 * 2. 重心法减少交叉；
 * 3. 每层水平居中，y = 层号 × 行高。
 * 返回的坐标均为非负（左上角为原点），graph.width/height 为内容尺寸。
 */
object DagLayoutEngine {

    fun layout(graph: DagGraph, density: Float): DagGraph {
        if (graph.nodes.isEmpty()) return graph

        val nodeW = DagMetrics.NODE_WIDTH_DP * density
        val nodeH = DagMetrics.NODE_HEIGHT_DP * density
        val layerGap = DagMetrics.LAYER_GAP_DP * density
        val siblingGap = DagMetrics.SIBLING_GAP_DP * density
        val margin = DagMetrics.MARGIN_DP * density
        val lane = DagMetrics.BACK_EDGE_LANE_DP * density

        val ids = graph.nodes.map { it.stepId }
        val idSet = ids.toHashSet()
        val children = HashMap<String, MutableList<String>>()
        val parents = HashMap<String, MutableList<String>>()
        for (id in ids) { children[id] = mutableListOf(); parents[id] = mutableListOf() }
        for (e in graph.edges) {
            if (e.type == DagEdgeType.BACK_EDGE) continue
            if (e.fromNodeId !in idSet || e.toNodeId !in idSet || e.fromNodeId == e.toNodeId) continue
            children.getValue(e.fromNodeId) += e.toNodeId
            parents.getValue(e.toNodeId) += e.fromNodeId
        }

        // Phase 1：最长路径分层（Kahn）
        val inDeg = HashMap<String, Int>()
        ids.forEach { inDeg[it] = parents.getValue(it).size }
        val layerOf = HashMap<String, Int>()
        val queue = ArrayDeque<String>()
        val roots = ids.filter { inDeg[it] == 0 }
            .sortedBy { if (it == graph.startNodeId) 0 else 1 }
        roots.forEach { layerOf[it] = 0; queue.addLast(it) }
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            val l = layerOf.getValue(id)
            for (c in children.getValue(id)) {
                layerOf[c] = maxOf(layerOf[c] ?: 0, l + 1)
                val d = inDeg.getValue(c) - 1
                inDeg[c] = d
                if (d == 0) queue.addLast(c)
            }
        }
        // 兜底：理论上回边已被剔除，但仍防御未分层节点
        val maxLayer = layerOf.values.maxOrNull() ?: 0
        ids.filter { it !in layerOf }.forEach { layerOf[it] = maxLayer + 1 }

        val layerCount = (layerOf.values.maxOrNull() ?: 0) + 1
        val layers = List(layerCount) { mutableListOf<String>() }
        ids.forEach { layers[layerOf.getValue(it)] += it }

        // Phase 2：重心法（自上而下，3 轮）
        val pos = HashMap<String, Int>()
        fun reindex() { layers.forEach { layer -> layer.forEachIndexed { i, id -> pos[id] = i } } }
        reindex()
        repeat(3) {
            for (li in 1 until layers.size) {
                val layer = layers[li]
                val keyed = layer.map { id ->
                    val ps = parents.getValue(id).mapNotNull { p -> pos[p]?.takeIf { layerOf[p]!! < li } }
                    id to (if (ps.isEmpty()) pos.getValue(id).toDouble() else ps.average())
                }
                layer.clear()
                keyed.sortedBy { it.second }.forEach { layer += it.first }
                layer.forEachIndexed { i, id -> pos[id] = i }
            }
        }

        // Phase 3：坐标
        val widest = layers.maxOf { it.size }
        val contentW = widest * nodeW + (widest - 1) * siblingGap
        val xy = HashMap<String, Pair<Float, Float>>()
        layers.forEachIndexed { li, layer ->
            val rowW = layer.size * nodeW + (layer.size - 1) * siblingGap
            val startX = margin + (contentW - rowW) / 2f
            val y = margin + li * (nodeH + layerGap)
            layer.forEachIndexed { i, id -> xy[id] = (startX + i * (nodeW + siblingGap)) to y }
        }

        val nodes = graph.nodes.map { n ->
            val (x, y) = xy.getValue(n.stepId)
            n.copy(x = x, y = y, layer = layerOf.getValue(n.stepId))
        }
        val hasBack = graph.edges.any { it.type == DagEdgeType.BACK_EDGE }
        return graph.copy(
            nodes = nodes,
            width = margin * 2 + contentW + if (hasBack) lane else 0f,
            height = margin * 2 + layerCount * nodeH + (layerCount - 1) * layerGap,
        )
    }
}
