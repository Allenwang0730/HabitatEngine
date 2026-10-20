package com.ailun.habitat.spec

import com.ailun.habitat.HabitatExecutor

/** 全部节点规格（引擎内置 + 宿主 :app 注册的节点 + 执行器内建的 FORK/JOIN）。 */
object NodeSpecs {

    private val builtin: List<NodeSpec> = listOf(
        NodeSpec(
            HabitatExecutor.ACTION_FORK, "并行：同时启动多个分支，各分支沿自己的 next 执行",
            params = listOf(
                ParamSpec("targets", ParamType.ARRAY, "要并行启动的节点 id 列表", required = true, nodeRef = true),
            ),
        ),
        NodeSpec(HabitatExecutor.ACTION_JOIN, "汇合：等待 FORK 启动的分支都结束后继续 next"),
    )

    val all: List<NodeSpec> by lazy {
        builtin + CoreSpecs.all + InteractionSpecs.all + SystemSpecs.all + TelephonySpecs.all +
            MediaSpecs.all + NetworkSpecs.all + SensorSpecs.all + AiSpecs.all + HostSpecs.all
    }

    private val byType: Map<String, NodeSpec> by lazy { all.associateBy { it.type } }

    fun get(type: String?): NodeSpec? = type?.let(byType::get)
}
