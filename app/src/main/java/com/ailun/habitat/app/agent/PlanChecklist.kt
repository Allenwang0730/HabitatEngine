package com.ailun.habitat.app.agent

/**
 * Agent 页的实时计划清单：每一步的状态。重规划后，已完成的步骤保留在前面，新计划接在后面。
 * 纯逻辑，[AgentLoopController] 持有并推给界面。
 */
enum class PlanStatus { PENDING, RUNNING, DONE, FAILED }

data class PlanItem(val text: String, val status: PlanStatus, val tool: Boolean = false)

object PlanChecklist {

    /** 换上新计划：保留旧清单里已完成的项，其余换成新计划（都是 PENDING）。 */
    fun replace(old: List<PlanItem>, steps: List<PlanStep>): List<PlanItem> =
        old.filter { it.status == PlanStatus.DONE } + steps.map { PlanItem(it.instruction, PlanStatus.PENDING, it.tool != null) }

    /** 新计划的第 [index] 步变成 [status]；[offset] 是前面保留的已完成项数量。 */
    fun mark(items: List<PlanItem>, offset: Int, index: Int, status: PlanStatus): List<PlanItem> {
        val at = offset + index
        if (at !in items.indices) return items
        return items.mapIndexed { i, item -> if (i == at) item.copy(status = status) else item }
    }

    /** 任务结束：还在跑的那一步按结果收尾（完成 → DONE，其它 → FAILED）。 */
    fun finish(items: List<PlanItem>, done: Boolean): List<PlanItem> =
        items.map { if (it.status == PlanStatus.RUNNING) it.copy(status = if (done) PlanStatus.DONE else PlanStatus.FAILED) else it }
}

/** [AgentLoop] 把计划和步骤进度报给界面（可选）。 */
interface PlanListener {
    fun onPlan(steps: List<PlanStep>)
    fun onStepStatus(index: Int, status: PlanStatus)
}
