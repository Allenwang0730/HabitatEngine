package com.ailun.habitat.app

import com.ailun.habitat.NodeHandlerFactory

/**
 * 远程（/ws、/mcp）调用节点的放行规则。整个节点高危的在 [AgentService.REMOTE_DANGEROUS_TOOLS]；
 * 这里再管"节点本身可远程用、但其中某个动作高危"的情况。
 */
object RemoteToolPolicy {

    /** 节点 → 远程默认拒绝的 action。 */
    val DANGEROUS_ACTIONS: Map<String, Set<String>> = mapOf(
        // 工作区：远程可以写、读、列；删除算高危（误删/恶意清空本机结果）
        NodeHandlerFactory.ACTION_WORKSPACE to setOf("delete"),
    )

    /** 返回拒绝原因，null 表示放行。 */
    fun check(tool: String, action: String?, dangerousTools: Set<String>, dangerousAllowed: Boolean): String? {
        if (dangerousAllowed) return null
        if (tool in dangerousTools) {
            return "远程调用 $tool 已被拒绝：该节点属于高危操作，需在 Agent 控制页开启「允许远程高危工具」"
        }
        val act = action?.trim()?.lowercase()
        if (act != null && act in DANGEROUS_ACTIONS[tool].orEmpty()) {
            return "远程调用 $tool（action=$act）已被拒绝：该操作属于高危操作，需在 Agent 控制页开启「允许远程高危工具」"
        }
        return null
    }
}
