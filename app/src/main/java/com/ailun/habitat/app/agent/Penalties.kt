package com.ailun.habitat.app.agent

import kotlin.math.exp

/**
 * 失败项的软惩罚：本次任务里做过却没用的动作留在候选里，只按惩罚分降低被选中的概率。
 *
 * 为什么不直接删（旧的 [LoopPolicy.deadEnds] + [ActionCatalog.without]）：AndroidWorld 实测里删错过——
 * 循环自己的换招「返回」把刚点对的「蓝牙」行判成死路，之后模型要点它只能得到「候选里没有」，任务报错结束。
 * 软惩罚下这个行还在，只是排在后面；别的路都走不通时模型仍能回来选它。
 *
 * 打分（同一动作签名 [LoopPolicy.signatureOf] 在本次任务里累计）：
 * - 做了屏幕没变化：+[NO_CHANGE]
 * - 节点执行失败：+[ERROR]
 * - 死路（原地动作没效果 / 点进外部应用又被拉回 / 进子页几步内又退回）：+[DEAD_END]
 * - 反思里点名「别再选」的（文字匹配候选标签）：+[AVOIDED]
 * 选择时概率乘以 exp(-[STRENGTH] × 惩罚分) 再归一化（[rerank]）。
 */
object Penalties {
    const val NO_CHANGE = 1.0
    const val ERROR = 1.5
    const val DEAD_END = 2.0
    const val AVOIDED = 1.0
    const val STRENGTH = 0.8

    /** 永远不罚：模型必须始终有路可退，也必须能宣布完成。 */
    private val NEVER = setOf("DONE", "BLOCKED", "WAIT")

    /** 按签名累计的惩罚分。 */
    fun bySignature(history: List<StepNote>): Map<String, Double> {
        val out = mutableMapOf<String, Double>()
        fun add(sig: String, v: Double) {
            out[sig] = (out[sig] ?: 0.0) + v
        }
        history.forEach { note ->
            if (note.operation in NEVER || note.signature.startsWith("INVALID|")) return@forEach
            if (note.error.isNotEmpty()) add(note.signature, ERROR)
            else if (note.changed == false) add(note.signature, NO_CHANGE)
        }
        LoopPolicy.deadEnds(history).forEach { add(it, DEAD_END) }
        return out
    }

    /**
     * 当前屏幕每个候选的惩罚分（动作 id → 分）。[avoid] 是反思给出的「别再选」文字，
     * 与候选标签或控件文字互相包含即算命中（至少 2 个字，避免「确定」这种短词误伤太多）。
     */
    fun forCatalog(
        catalog: ActionCatalog,
        history: List<StepNote>,
        observation: ScreenObservation,
        avoid: List<String> = emptyList(),
    ): Map<String, Double> {
        val bySig = bySignature(history)
        val avoidKeys = avoid.map(::fold).filter { it.length >= 2 }
        val out = mutableMapOf<String, Double>()
        catalog.actions.forEach { action ->
            if (action.operation in NEVER) return@forEach
            var p = bySig[LoopPolicy.signatureOf(action, observation)] ?: 0.0
            if (avoidKeys.isNotEmpty()) {
                val label = fold(action.matchText?.takeIf { it.isNotBlank() } ?: action.label)
                if (label.length >= 2 && avoidKeys.any { label.contains(it) || it.contains(label) }) p += AVOIDED
            }
            if (p > 0.0) out[action.id] = p
        }
        // 按坐标点/滑的动作是选中后现做的，不在 actions 里：按历史里做过的格子算（同一格子没效果就压低）
        if (catalog.pointTap != null) {
            history.filter { it.operation == "TAP_POINT" || it.operation == "SWIPE_POINT" }.forEach { note ->
                bySig[note.signature]?.takeIf { it > 0.0 }?.let { out[note.actionId] = it }
            }
        }
        return out
    }

    data class Pick(val choice: String, val probability: Double, val flipped: Boolean)

    /**
     * 模型给的概率 × exp(-STRENGTH × 惩罚) 后重新选。[probs] 里不在 [allowed] 的选项忽略。
     * 没有任何惩罚时原样返回模型的首选（flipped=false），行为与不开惩罚一致。
     */
    fun rerank(probs: Map<String, Double>, allowed: Set<String>, penaltyOf: (String) -> Double): Pick? {
        val valid = probs.filterKeys { it in allowed }.filterValues { it.isFinite() && it >= 0.0 }
        if (valid.isEmpty()) return null
        val original = valid.maxByOrNull { it.value }!!.key
        val weighted = valid.mapValues { (k, v) -> v * exp(-STRENGTH * penaltyOf(k)) }
        val total = weighted.values.sum()
        if (total <= 0.0) return Pick(original, valid.getValue(original), flipped = false)
        val best = weighted.maxByOrNull { it.value }!!.key
        return Pick(best, weighted.getValue(best) / total, flipped = best != original)
    }

    private fun fold(text: String): String = text.lowercase().filterNot { it.isWhitespace() || it in "「」\"'“”()（）" }
}
