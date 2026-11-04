package com.ailun.habitat

import com.ailun.habitat.spec.CommonParams
import com.ailun.habitat.spec.NodeSpecs

/**
 * 工作流静态检查结果。severity 为 [ERROR] 或 [WARNING]。
 * nodeId 为 null 表示图级问题（如缺少 start_node_id）。
 */
data class LintIssue(
    val nodeId: String?,
    val message: String,
    val severity: String,
) {
    companion object {
        const val ERROR = "error"
        const val WARNING = "warning"
    }
}

/**
 * [HabitatJson.lint] 的实现。只读图、不抛异常。
 *
 * 节点引用来源（与 executor/handler 实际跳转一致）：
 * - `next`、`branches` 的值
 * - ACTION_FORK `targets`（executor 内建）
 * - ACTION_LOOP `body_node`（NodeLoopHandler 返回的循环体入口）
 * - ACTION_RETRY `target`（NodeRetryHandler 跳转的重试目标）
 * 含 `${` 的动态引用无法静态判定，跳过。
 */
internal object WorkflowLint {

    /** 节点类型 → params 里存放节点 id 的键。 */
    private val PARAM_NODE_REFS: Map<String, List<String>> = mapOf(
        HabitatExecutor.ACTION_FORK to listOf("targets"),
        NodeHandlerFactory.ACTION_LOOP to listOf("body_node"),
        NodeHandlerFactory.ACTION_RETRY to listOf("target"),
    )

    /** executor 内建、不在 NodeHandlerFactory 注册的类型。 */
    private val BUILTIN_TYPES = setOf(HabitatExecutor.ACTION_FORK, HabitatExecutor.ACTION_JOIN)

    fun lint(graph: WorkflowGraph, knownTypes: Set<String>?): List<LintIssue> {
        val issues = mutableListOf<LintIssue>()
        val nodes = graph.nodes
        if (nodes.isNullOrEmpty()) {
            issues += LintIssue(null, "缺少节点（nodes 为空）", LintIssue.ERROR)
            return issues
        }

        val startId = graph.startNodeId
        when {
            startId.isNullOrBlank() -> issues += LintIssue(null, "缺少 start_node_id", LintIssue.ERROR)
            startId !in nodes -> issues += LintIssue(null, "start_node_id '$startId' 在 nodes 中不存在", LintIssue.ERROR)
        }

        // key → 该节点指向的所有节点 id（仅静态可判定的）
        val edges = HashMap<String, List<String>>()
        for ((key, node) in nodes) {
            @Suppress("SENSELESS_COMPARISON")
            if (node == null) {
                issues += LintIssue(key, "节点 '$key' 为空", LintIssue.ERROR)
                continue
            }
            val id = node.id
            if (id.isNullOrBlank()) {
                issues += LintIssue(key, "节点 '$key' 缺少 id", LintIssue.ERROR)
            } else if (id != key) {
                issues += LintIssue(key, "节点 key '$key' 与 id '$id' 不一致（跳转按 key 查找）", LintIssue.WARNING)
            }

            val type = node.type
            if (type.isNullOrBlank()) {
                issues += LintIssue(key, "节点 '$key' 缺少 type", LintIssue.ERROR)
            } else if (knownTypes != null && type !in knownTypes && type !in BUILTIN_TYPES) {
                issues += LintIssue(key, "未注册的节点类型 '$type'", LintIssue.ERROR)
            }

            val refs = mutableListOf<String>()
            node.next?.takeIf { it.isNotBlank() }?.let { n ->
                if (isDynamic(n)) return@let
                refs += n
                if (n !in nodes) issues += LintIssue(key, "next 指向不存在的节点 '$n'", LintIssue.ERROR)
            }
            node.branches?.forEach { (branch, target) ->
                if (target.isNullOrBlank() || isDynamic(target)) return@forEach
                refs += target
                if (target !in nodes) {
                    issues += LintIssue(key, "分支 '$branch' 指向不存在的节点 '$target'", LintIssue.ERROR)
                }
            }
            type?.let { PARAM_NODE_REFS[it] }?.forEach { param ->
                val raw = node.params?.get(param) ?: run {
                    if (type == HabitatExecutor.ACTION_FORK) {
                        issues += LintIssue(key, "FORK 缺少 targets 参数", LintIssue.WARNING)
                    }
                    return@forEach
                }
                for (target in parseRefList(raw)) {
                    if (isDynamic(target)) continue
                    refs += target
                    if (target !in nodes) {
                        issues += LintIssue(key, "参数 '$param' 指向不存在的节点 '$target'", LintIssue.ERROR)
                    }
                }
            }
            edges[key] = refs
            if (!type.isNullOrBlank()) issues += paramIssues(key, type, node.params)
        }

        if (!startId.isNullOrBlank() && startId in nodes) {
            val reachable = HashSet<String>()
            val queue = ArrayDeque<String>()
            queue += startId
            while (queue.isNotEmpty()) {
                val cur = queue.removeFirst()
                if (!reachable.add(cur)) continue
                edges[cur]?.forEach { if (it in nodes && it !in reachable) queue += it }
            }
            for (key in nodes.keys) {
                if (key !in reachable) {
                    issues += LintIssue(key, "节点 '$key' 从 start_node_id 不可达", LintIssue.WARNING)
                }
            }
        }
        return issues
    }

    private fun isDynamic(ref: String) = ref.contains("\${")

    /**
     * 按节点规格（[NodeSpecs]）检查参数：缺必填、可选值不在列表里、不认识的参数名（多半拼错）。
     * 一律 WARNING：规格之外的参数 handler 只是忽略，不阻止保存。没有规格的节点类型不检查。
     */
    internal fun paramIssues(key: String, type: String, params: Map<String, Any?>?): List<LintIssue> {
        val spec = NodeSpecs.get(type) ?: return emptyList()
        val given = params.orEmpty()
        val out = mutableListOf<LintIssue>()
        for (p in spec.params) {
            val names = listOf(p.name) + p.aliases
            val present = names.firstOrNull { given[it] != null && given[it].toString().isNotBlank() }
            if (p.required && present == null) {
                out += LintIssue(key, "$type 缺少必填参数 '${p.name}'（${p.description}）", LintIssue.WARNING)
            }
            if (present != null && p.enum.isNotEmpty()) {
                val v = given[present].toString().trim()
                if (!isDynamic(v) && v.lowercase() !in p.enum) {
                    out += LintIssue(key, "$type 的 '${p.name}' = '$v' 不在可选值里：${p.enum.joinToString("/")}", LintIssue.WARNING)
                }
            }
        }
        val known = spec.params.flatMap { listOf(it.name) + it.aliases }.toSet() + CommonParams.names
        for (name in given.keys) {
            if (name !in known) {
                val hint = known.minByOrNull { editDistance(it, name) }?.takeIf { editDistance(it, name) <= 2 }
                out += LintIssue(
                    key,
                    "$type 不认识参数 '$name'" + (hint?.let { "，是不是 '$it'？" } ?: "（会被忽略）"),
                    LintIssue.WARNING,
                )
            }
        }
        return out
    }

    private fun editDistance(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }

    /** 与 executor 解析 FORK targets 的格式一致：List / JSON 数组串 / 逗号分隔。 */
    internal fun parseRefList(raw: Any?): List<String> {
        return when (raw) {
            null -> emptyList()
            is List<*> -> raw.mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() }
            else -> {
                val s = raw.toString().trim()
                val inner = if (s.startsWith("[") && s.endsWith("]")) s.substring(1, s.length - 1) else s
                inner.split(",").map { it.trim().trim('"', '\'').trim() }.filter { it.isNotEmpty() }
            }
        }
    }
}
