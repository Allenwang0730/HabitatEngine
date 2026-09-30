package com.ailun.habitat.app.dag

import com.ailun.habitat.WorkflowNode
import com.ailun.habitat.app.ui.theme.NodeCategory

enum class DagEdgeType {
    SEQUENTIAL,
    BRANCH_TRUE,
    BRANCH_FALSE,
    /** 回边（指向 DFS 栈中的祖先，形成循环）：布局时忽略，绘制为虚线。 */
    BACK_EDGE,
    EXIT_EDGE,
    /** 参数里的节点引用（LOOP body_node / FORK targets / RETRY target）。 */
    JUMP,
    TRIGGER,
}

data class DagNode(
    val stepId: String,
    val stepIndex: Int,
    val moduleId: String,
    val label: String,
    val moduleName: String,
    val category: NodeCategory,
    val isTrigger: Boolean = false,
    val source: WorkflowNode? = null,
    val layer: Int = 0,
    val x: Float = 0f,
    val y: Float = 0f,
)

/**
 * @param type 绘制语义；回边为 BACK_EDGE。
 * @param baseType 原始语义（回边保留 next/true/false，用于标签与颜色）。
 */
data class DagEdge(
    val fromNodeId: String,
    val toNodeId: String,
    val type: DagEdgeType,
    val label: String = "",
    val baseType: DagEdgeType = type,
)

data class DagGraph(
    val nodes: List<DagNode>,
    val edges: List<DagEdge>,
    val startNodeId: String? = null,
    /** 布局后内容尺寸（px）；未布局为 0。 */
    val width: Float = 0f,
    val height: Float = 0f,
)

/** 节点与间距尺寸（dp），布局与绘制共用。 */
object DagMetrics {
    const val NODE_WIDTH_DP = 200f
    const val NODE_HEIGHT_DP = 60f
    const val LAYER_GAP_DP = 56f
    const val SIBLING_GAP_DP = 28f
    const val MARGIN_DP = 32f
    /** 右侧给回边留的绕行空间。 */
    const val BACK_EDGE_LANE_DP = 56f
}
