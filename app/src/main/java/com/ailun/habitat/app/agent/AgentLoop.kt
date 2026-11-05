package com.ailun.habitat.app.agent

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.ailun.habitat.NodeHandlerFactory
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode
import com.ailun.habitat.app.bridge.AppAccessibilityProvider
import com.ailun.habitat.app.bridge.ShizukuShellExecutor
import com.ailun.habitat.app.bridge.applyAppHandlers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** [answer]：目标是提问时只有答案本身（复核员按目标要求的格式写），[detail] 是给人看的「答案 — 依据」。 */
data class LoopResult(val status: String, val steps: Int, val detail: String, val answer: String = "")

/**
 * 循环里所有"只看数据"的判定。抽出来是为了能单测：
 * AgentLoop 本身要 Context/无障碍，纯 JUnit 跑不了；这些规则恰恰是出问题最多的地方。
 */
object LoopPolicy {
    /** 这一步该不该做。 */
    enum class Verdict { EXECUTE, EXECUTE_LOW, DROP }

    /** 屏幕能不能继续：锁屏、无障碍没连都要立刻停。 */
    fun screenVerdict(o: ScreenObservation): String? = when {
        o.locked -> "屏幕已锁定，请先解锁"
        o.source == "none" && o.elements.isEmpty() -> "无障碍未连接，读不到屏幕"
        else -> null
    }

    /** 置信度判定。低于 discard 才丢；[discard, low) 之间照做，只是记日志。 */
    fun confidenceVerdict(
        confidence: Double,
        low: Double = MIN_CONFIDENCE,
        discard: Double = DISCARD_CONFIDENCE,
    ): Verdict = when {
        confidence < discard -> Verdict.DROP
        confidence < low -> Verdict.EXECUTE_LOW
        else -> Verdict.EXECUTE
    }

    /**
     * 判重签名：操作 + 控件 viewId（拿不到才退回文字）+ 前台包。
     * 不带序号（换屏就变），也不只看文字——同一行文字在不同层级界面会误判成"重复"。
     */
    fun signatureOf(action: CatalogAction, observation: ScreenObservation): String {
        val anchor = action.viewId.ifEmpty { action.matchText ?: action.target.orEmpty() }
        return "${action.operation}|$anchor|${observation.packageName}"
    }

    /**
     * 屏幕是否"看起来没变"。文字、前台包、控件数、焦点、控件状态都算。
     * 控件状态必须算：真机上拖亮度条、拨开关时文字一个字都不变，旧判定把"已经成功"当成"没变化"，
     * 于是反复拖、被判兜圈、最后报"达到步数上限"——亮度其实早就到最高了。
     */
    fun sameScreen(a: ScreenObservation, b: ScreenObservation): Boolean {
        // 混合读屏补进来的 OCR 文字不算：同一屏两次 OCR 结果常差一两个字，算进来会把"没变化"误判成"变了"
        return treeText(a) == treeText(b) &&
            a.packageName == b.packageName &&
            a.elements.count { !it.ocr } == b.elements.count { !it.ocr } &&
            a.focusedIndex == b.focusedIndex &&
            controlState(a) == controlState(b) &&
            layout(a) == layout(b)
    }

    /**
     * 每段文字在哪一格（[LAYOUT_CELL] 像素）。文字不变、只是挪了位置也算变化：
     * AndroidWorld BrowserMaze 里「X」从一格走到另一格，文字和控件数一模一样，旧判定说"没变化"，于是去换招、按返回，把页面关了。
     */
    private fun layout(o: ScreenObservation): List<String> =
        o.elements.filter { !it.ocr && it.text.isNotBlank() }.map { "${it.text}@${it.centerX / LAYOUT_CELL},${it.centerY / LAYOUT_CELL}" }

    private const val LAYOUT_CELL = 48

    /** 去掉 OCR 补进来的那一段（[ScreenObserver.mergeOcr] 在树文字后面接「\n + OCR 文字」）。 */
    private fun treeText(o: ScreenObservation): String {
        val ocr = o.elements.filter { it.ocr }
        if (ocr.isEmpty()) return o.visibleText
        return o.visibleText.removeSuffix("\n" + ocr.joinToString("\n") { it.text })
    }

    private fun controlState(o: ScreenObservation): List<String> =
        o.elements.filter { it.checkable || it.rangeFraction != null }
            .map { "${it.checked}|${it.rangeFraction?.let { f -> (f * 100).toInt() }}" }

    /**
     * 最近 [window] 步里同一签名出现的次数（不要求连续）。
     * 连续判重抓不到来回横跳：真机实测 DeepSeek 在「打开设置 → 点关于手机（误进 vivo 使用手册）→ 打开设置 → …」
     * 之间交替，每次都不是"连续两次同一动作"，一直耗到步数上限。
     */
    fun recentCount(history: List<StepNote>, signature: String, window: Int = CYCLE_WINDOW): Int {
        val recent = history.takeLast(window).filter { it.signature == signature }
        // 滚动是"同一动作做多次"的正常用法（长列表一屏一屏往下找）：每次屏幕都变了就不算兜圈，
        // 真没动由 noChange 计数兜底。真机实测「关于手机」在设置第三屏，旧规则滚到第三次就误停。
        if (signature.startsWith("SCROLL_")) return recent.count { it.changed != true }
        return recent.size
    }

    /**
     * 这次任务里已经证明走不通的动作签名，下一轮候选里直接去掉：
     * - 提交/清空/输入这类"原地动作"做了一次屏幕没变 → 再做也一样（真机：搜索结果已经出来了，模型还在反复按回车）；
     * - 点进去离开了当前应用、紧接着又被拉回来（跳出 → 打开原应用）→ 这个入口是死路
     *   （真机：设置搜索里的「关于手机 使用手册」会跳进 vivo 使用手册，模型来回跳了 4 次）。
     * 滚动、返回、桌面不进死路名单：它们的"没变化"由 noChange/换招处理。
     */
    fun deadEnds(history: List<StepNote>): Set<String> {
        val dead = mutableSetOf<String>()
        history.forEachIndexed { i, note ->
            if (note.operation in IN_PLACE_OPS && note.changed == false) dead.add(note.signature)
            val next = history.getOrNull(i + 1)
            if (note.operation == "TAP" && note.leftApp && next != null &&
                (next.operation == "OPEN_APP" || next.operation == "BACK")
            ) {
                dead.add(note.signature)
            }
            // 点进一个子页面、几步之内又退回来：这个入口找过了，没有要的东西
            // （真机：模型以为「关于手机」在「系统管理与升级」里，进去翻两下返回，再进去，来回耗光步数）
            if (note.operation == "TAP" && note.changed == true) {
                val after = history.subList(i + 1, minOf(history.size, i + 1 + EXPLORE_WINDOW))
                if (after.any { it.operation == "BACK" && it.changed == true }) dead.add(note.signature)
            }
        }
        return dead
    }

    private val IN_PLACE_OPS = setOf("PRESS_ENTER", "CLEAR_TEXT", "TYPE_TEXT", "LONG_PRESS")
    private const val EXPLORE_WINDOW = 4

    /** 同一签名在 history 末尾连续出现的次数。 */
    fun repeatCount(history: List<StepNote>, signature: String): Int {
        var count = 0
        for (note in history.asReversed()) {
            if (note.signature != signature) break
            count++
        }
        return count
    }

    /** 同一操作的次优候选：没试过的、没在本屏失败过的，按与目标的贴合度排。 */
    fun retryCandidate(
        catalog: ActionCatalog,
        failed: CatalogAction,
        goal: String,
        history: List<StepNote>,
    ): CatalogAction? {
        // 滑块失败不换档；工具失败可以换别的工具（「设为最高」失败→「打开显示设置页」），
        // 但不能换同一工具的另一档（「设为最高」失败改成「设为中等」等于偷换目标）
        if (failed.operation == "SET_SLIDER") return null
        val failedTool = failed.toolKey?.substringBefore('|')
        val tried = history.map { it.actionId }.toSet() + failed.id
        // 已经在本屏失败过的锚点不再试（同一 viewId/文字再点一次结果一样）
        val failedAnchors = history.filter { it.changed == false }
            .map { it.signature.substringAfter('|').substringBeforeLast('|') }
            .toSet()
        return catalog.candidatesFor(failed.operation, goal, exclude = tried)
            .filter { failedTool == null || it.toolKey?.substringBefore('|') != failedTool }
            .firstOrNull { candidate ->
                val anchor = candidate.viewId.ifEmpty { candidate.matchText ?: candidate.target.orEmpty() }
                anchor !in failedAnchors
            }
    }

    /**
     * 屏幕不动时的"换个招"：优先滚动（可能只是内容在下面），其次返回（可能卡在二级页），
     * 都已经做过就回到桌面。全做过则返回 null，由调用方结束。
     */
    fun recoveryAction(catalog: ActionCatalog, history: List<StepNote>, avoid: String? = null): CatalogAction? {
        val tried = history.takeLast(6).map { it.operation }.toSet()
        // 正在重复的就是滚动：说明这一页翻不出来，先返回上一层，而不是换个方向接着滚
        val order = if (avoid?.startsWith("SCROLL_") == true) {
            listOf("BACK", "SCROLL_UP", "HOME")
        } else {
            listOf("SCROLL_DOWN", "BACK", "SCROLL_UP", "HOME")
        }
        val op = order.firstOrNull { it !in tried && it != avoid } ?: order.first { it != avoid }
        return catalog.resolve(op, null)
    }

    /**
     * 低于这个置信度的动作不执行（Jev 会返回 confidence；DeepSeek/本地恒为 1.0）。
     * 注意：只是"偏低"不再丢步，只有低于 [DISCARD_CONFIDENCE] 才跳过。
     */
    const val MIN_CONFIDENCE = 0.3

    /** 真正丢弃的阈值。真机实测两个云端模型都会给 0.2~0.3，旧值 0.3 等于全丢。 */
    const val DISCARD_CONFIDENCE = 0.15
    const val MAX_WAITS = 3

    /** 连续低置信多少步才停。 */
    const val MAX_UNSURE = 3

    /** 同一动作在同一屏幕上最多连续做几次。 */
    const val MAX_SAME_ACTION = 2

    /** 连续几次没变化后开始注入 SCROLL/BACK。 */
    const val FORCE_AFTER_NO_CHANGE = 2

    /** 连续几次没变化后彻底结束。 */
    const val STOP_AFTER_NO_CHANGE = 4

    /** 模型选到候选外目标时，最多允许重选几次。 */
    const val MAX_INVALID_PICKS = 2

    /** 一次任务里最多替模型换几次招；超过还在重复就停。 */
    const val MAX_RECOVERIES = 2

    /** 循环检测：最近 [CYCLE_WINDOW] 步里同一动作出现 [MAX_IN_WINDOW] 次就判为来回横跳。 */
    const val CYCLE_WINDOW = 6
    const val MAX_IN_WINDOW = 3
}

/**
 * 自主任务的三层结构（参考 jev-mobile / jev-ultrafast）：
 *
 * ```
 * 目标 ─► 规划（System 2，DeepSeek）：拆成几步，每步有屏幕上可验证的完成条件，能用工具就用工具
 *          │
 *          ▼
 *   每一步 ─► 工具步：直接调设备工具，读回结果；失败退回界面步
 *          └► 界面步：读屏 → 候选 → 执行器（Jev/DeepSeek/本地，System 1）只选一个 → 代码执行 → 代码验证
 *               卡住（兜圈/连续无变化/低置信）─► 规划器从当前屏幕重规划剩下的部分（有上限）
 *          │
 *          ▼
 *   复核（System 2）：按屏幕证据判断目标是否真的完成；提问类目标在这里读出答案
 * ```
 *
 * 控制权在代码手里：步骤推进、导航栈/页面图、判重、换招、安全闸都不交给模型。
 * 没有规划器时整个目标就是一步，行为与旧版一致（另外多了导航记忆和工具快捷方式）。
 */
class AgentLoop(
    private val context: Context,
    private val provider: DecisionProvider,
    private val maxSteps: Int = 20,
    /** 本机发起为 true；远程发起为 false，目标原文不能解锁敏感按钮和权限弹窗。 */
    private val allowGoalUnlock: Boolean = true,
    private val planner: Planner? = null,
    /**
     * true：失败项按 [Penalties] 软惩罚（留在候选里、降低选中概率），每次卡住先反思（[Planner.reflect]）再重规划。
     * false：旧行为，死路直接从候选里删掉，不反思。
     */
    private val softPenalties: Boolean = false,
    /** true：多步计划里每做 [STEP_CHECK_EVERY] 步让规划模型复查一次这一步完成没有（[Planner.checkStep]）。 */
    private val stepChecks: Boolean = false,
    /** true：执行器每一步看得到截图，可以按截图坐标点（TAP_POINT）。只在本机发起时生效（[Catalogs.build]）。 */
    private val pointTaps: Boolean = false,
) {
    @Volatile var stopRequested: Boolean = false

    /**
     * 重规划次数：默认 [MAX_REPLANS]；步数预算放宽时按比例多给（每 20 步多一次，最多 6 次）——
     * 步数多了，卡住后换一种做法的机会也该多，否则多出来的步数用不上。
     */
    private val maxReplans: Int = (maxSteps / 20).coerceIn(MAX_REPLANS, 6)

    /** 本次任务每轮决策看到的屏幕来自哪里（a11y / ocr / a11y+ocr …）→ 次数。评测里算 OCR 参与比例。 */
    val obsSources: Map<String, Int> get() = obsCounts.toMap()
    private val obsCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** 计划与步骤进度（Agent 页的实时清单）。 */
    @Volatile var planListener: PlanListener? = null

    /** 本轮任务的标识：小屏/工作区据此区分"同一次运行"。 */
    val runId: String = "agent-" + java.util.UUID.randomUUID().toString().take(8)

    /** 本轮已经成功交给用户的输出工具（show_on_screen / save_note），结束时不再重复交付。 */
    private val delivered = mutableSetOf<String>()

    /** 本轮成功执行、且有效果的动作（按顺序），用于「存为工作流」（[RunReplay]）。 */
    private val recorded = java.util.Collections.synchronizedList(mutableListOf<RecordedAction>())

    val recordedActions: List<RecordedAction> get() = synchronized(recorded) { recorded.toList() }

    private val factory by lazy {
        NodeHandlerFactory(AppAccessibilityProvider, ShizukuShellExecutor(context))
            .apply { applyAppHandlers(context) }
    }

    /** 一次任务的全部可变状态。 */
    private class Run(
        val goal: String,
        val apps: List<AppEntry>,
        var observation: ScreenObservation,
        val intent: ParsedIntent,
        val scope: ToolScope,
    ) {
        val history = mutableListOf<StepNote>()
        val facts = linkedMapOf<String, String>()
        val completed = mutableListOf<String>()
        val nav = NavigationState()
        var used = 0
        var seq = 0
        var replans = 0
        /** 复查员记下的跨屏笔记总数（facts 里只留最近几条，键号一直往上加）。 */
        var noteCount = 0
        /** 按规划器的 replan_after 读到内容后接着规划的次数（[MAX_REFRESHES]）。 */
        var refreshes = 0
        /** 本次任务里执行失败过的工具调用（权限不足等），重规划后不再尝试。 */
        val failedTools = mutableSetOf<String>()
        /** 失败后的反思（最多留 [MAX_REFLECTIONS] 条），进重规划和执行器提示词。 */
        val reflections = mutableListOf<Reflection>()
    }

    private data class StepOutcome(val status: String, val detail: String)

    private data class NodeResult(val ok: Boolean, val detail: String, val outputs: Map<String, String> = emptyMap())

    suspend fun run(goal: String, onStep: (String) -> Unit): LoopResult {
        val result = runGoal(goal, onStep)
        if (goal.isNotBlank()) {
            try {
                deliver(goal, result, onStep)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onStep("结果没能交付：${e.message}")
            }
        }
        return result
    }

    private suspend fun runGoal(goal: String, onStep: (String) -> Unit): LoopResult {
        if (goal.isBlank()) return LoopResult("error", 0, "目标为空")
        val intent = IntentParser.parse(goal)
        val scope = scopeFor(intent)
        val run = Run(goal, ScreenObserver.launcherApps(context), ScreenObserver.observe(context), intent, scope)
        LoopPolicy.screenVerdict(run.observation)?.let {
            onStep(it)
            return LoopResult("error", 0, it)
        }
        run.nav.enter(run.observation, AppAccessibilityProvider.foregroundActivity)
        if (intent.kind != IntentKind.GENERIC) {
            val kind = if (intent.kind == IntentKind.CALL) "打电话" else "发消息"
            onStep("意图：$kind" + listOfNotNull(intent.contact?.let { "对象「$it」" }, intent.text?.let { "内容「$it」" })
                .joinToString("，", prefix = " ") + (scope.callee?.let { "（号码已在本机查到）" } ?: ""))
        }
        var plan = when (val skill = SkillPlans.build(intent, goal, run.apps, scope, ContactResolver.readable(context), allowGoalUnlock)) {
            is SkillPlans.Result.Ready -> skill.plan.also { p ->
                onStep("固定做法（${p.steps.size} 步）：" + p.steps.mapIndexed { i, s -> "${i + 1}. ${s.instruction}" }.joinToString("；"))
            }
            is SkillPlans.Result.Refuse -> {
                onStep(skill.reason)
                return LoopResult("blocked", 0, skill.reason)
            }
            SkillPlans.Result.None -> makePlan(run, null, onStep) ?: return LoopResult("error", 0, "规划失败")
        }
        val note = plan.note
        val finish = plan.finishWhen
        /**
         * 终点已在屏幕上成立：直接结束（远程只填不发 → 带说明；本机已发送 → 代码已核实，不再复核）。
         * 「已发送」必须是本次任务自己输入过这段文字之后：聊天记录里可能早就有同样的话。
         * 「输入框里正好是这段文字」不要求：那就是要的状态（QQ 会保留上次没发的草稿）。
         */
        fun finished(): LoopResult? {
            if (finish == null) return null
            if (finish is StepCheck.Sent && !typedInRun(run, finish.text)) return null
            if (!finish.satisfied(run.observation)) return null
            val detail = note.ifEmpty { "已发送" }
            onStep(detail)
            return LoopResult("done", run.used, detail)
        }

        while (true) {
            var index = 0
            var replanned = false
            planListener?.onPlan(plan.steps)
            while (index < plan.steps.size) {
                if (stopRequested) return LoopResult("stopped", run.used, "已停止")
                finished()?.let { return it }
                if (run.used >= maxSteps) return outOfSteps(run, onStep)
                val step = plan.steps[index]
                run.seq++
                if (plan.steps.size > 1) onStep("步骤 ${index + 1}/${plan.steps.size}：${step.instruction}（完成条件：${step.doneWhen}）")
                planListener?.onStepStatus(index, PlanStatus.RUNNING)
                val outcome = if (step.tool != null) {
                    runToolStep(run, step, index, plan.steps.size, budgetFor(run, plan), onStep)
                } else {
                    runUiStep(run, step, index, plan.steps.size, budgetFor(run, plan), onStep)
                }
                when (outcome.status) {
                    "done" -> {
                        run.completed += step.instruction
                        planListener?.onStepStatus(index, PlanStatus.DONE)
                        // 跨屏记忆：要离开这一屏了，先把屏幕上之后用得到的信息记下来（读了文件内容、看到数字再去别的应用填）。
                        // 周期复查每两步才一次，读文件这种一两步就做完的步骤常常一次都轮不上
                        val refresh = step.replanAfter && planner != null && run.refreshes < MAX_REFRESHES
                        if (step.tool == null && (refresh || index + 1 < plan.steps.size)) noteScreen(run, step, onStep)
                        if (refresh) {
                            // 规划器说过"看到这份内容再接着规划"：带着记下的事实规划剩下的部分（不算一次卡住）
                            run.refreshes++
                            onStep("已经看到需要的内容，接着规划剩下的部分")
                            val next = makePlan(run, replanContext(run, step.instruction, "").copy(refresh = true), onStep)
                            if (next != null) {
                                plan = next
                                replanned = true
                                break
                            }
                        }
                        index++
                    }
                    "stopped" -> return LoopResult("stopped", run.used, outcome.detail)
                    "fatal" -> return LoopResult("error", run.used, outcome.detail)
                    else -> {
                        planListener?.onStepStatus(index, PlanStatus.FAILED)
                        // 卡住时先看终点：远程任务卡在被挡住的发送按钮上，其实消息已经填好了
                        finished()?.let { return it }
                        if (run.used >= maxSteps) return outOfSteps(run, onStep)
                        if (planner == null || run.replans >= maxReplans) {
                            return LoopResult(if (outcome.status == "error") "error" else "blocked", run.used, outcome.detail)
                        }
                        run.replans++
                        onStep("这一步卡住（${outcome.detail}），重新规划（第 ${run.replans} 次）")
                        reflectOnFailure(run, step.instruction, outcome.detail, onStep)
                        plan = makePlan(run, replanContext(run, step.instruction, outcome.detail), onStep)
                            ?: return LoopResult("blocked", run.used, outcome.detail)
                        replanned = true
                        break
                    }
                }
            }
            if (replanned) continue
            finished()?.let { return it }

            // 有意不做完的固定做法（远程发起只填好消息不发送）：复核必然判"没发出去"，再重规划只会去找发送按钮
            if (note.isNotEmpty()) {
                onStep(note)
                return LoopResult("done", run.used, note)
            }

            // 复核：不相信"每一步都选了 DONE"，按屏幕证据再判断一次
            val reviewer = planner ?: return LoopResult("done", run.used, withFacts("完成", run))
            run.observation = ScreenObserver.observe(context)
            val review = runCatching {
                reviewer.review(goal, run.observation, run.completed, run.facts)
            }.getOrNull()
            if (review == null) {
                onStep("复核不可用，按执行器判断结束")
                return LoopResult("done", run.used, withFacts("完成（未复核）", run))
            }
            if (review.done) {
                val answer = AnswerFormat.normalize(goal, review.answer)
                val detail = listOf(answer, review.reason).filter { it.isNotBlank() }.joinToString(" — ").ifBlank { "完成" }
                onStep("复核通过：$detail")
                return LoopResult("done", run.used, detail, answer = answer)
            }
            onStep("复核未通过：${review.reason}")
            if (run.replans >= maxReplans || run.used >= maxSteps) {
                return LoopResult("blocked", run.used, "复核未通过：${review.reason}", answer = AnswerFormat.normalize(goal, review.answer))
            }
            run.replans++
            reflectOnFailure(run, "复核", review.reason, onStep)
            plan = makePlan(run, replanContext(run, "复核", review.reason), onStep)
                ?: return LoopResult("blocked", run.used, "复核未通过：${review.reason}")
        }
    }

    /** 本次任务里成功执行过「输入 X」。 */
    private fun typedInRun(run: Run, text: String): Boolean =
        run.history.any { it.operation == "TYPE_TEXT" && it.error.isEmpty() && it.label.contains("「$text」") }

    /**
     * 跨屏记忆：屏幕上和目标有关的信息记成事实，后面的执行器和复核员都看得到（问答、跨应用填写要用）。
     * 只留最近 [MAX_NOTES] 条：每条都进每一次执行器/复核员的提示词。
     */
    private fun saveNote(run: Run, note: String, onStep: (String) -> Unit) {
        if (note.isBlank() || note in run.facts.values) return
        val notes = run.facts.keys.filter { it.startsWith("note_") }
        if (notes.size >= MAX_NOTES) run.facts.remove(notes.first())
        run.noteCount++
        run.facts["note_${run.noteCount}"] = note
        onStep("记下：$note")
    }

    /** 一步做完、要离开这一屏之前，让复查员看一眼当前屏幕，只为了记笔记（不改变步骤结果，不算步数）。 */
    private suspend fun noteScreen(run: Run, step: PlanStep, onStep: (String) -> Unit) {
        val checker = planner ?: return
        if (!stepChecks || stopRequested) return
        val check = runCatching { checker.checkStep(run.goal, step.instruction, step.doneWhen, run.observation) }.getOrNull()
        check?.note?.let { saveNote(run, it, onStep) }
    }

    /**
     * 步数用完：结束前让复核员看一眼当前屏幕（只是一次模型调用，不点任何东西，不算步数）。
     * 提问类目标常常是答案已经在屏幕上、只差最后一步"作答"——不问就白白交空卷。
     * 状态仍是 blocked（没确认完成），答案放在 [LoopResult.answer]，交给调用方决定用不用。
     */
    private suspend fun outOfSteps(run: Run, onStep: (String) -> Unit): LoopResult {
        val reason = "达到步数上限 $maxSteps"
        val reviewer = planner ?: return LoopResult("blocked", run.used, reason)
        run.observation = ScreenObserver.observe(context)
        val review = runCatching { reviewer.review(run.goal, run.observation, run.completed, run.facts) }.getOrNull()
        val answer = AnswerFormat.normalize(run.goal, review?.answer.orEmpty())
        // 复核员按屏幕证据判定已完成：就是在最后一步做完了，照常报完成（调用方的步数预算里已经给这一步留了位）
        if (review?.done == true) {
            val detail = listOf(answer, review.reason).filter { it.isNotBlank() }.joinToString(" — ").ifBlank { "完成" }
            onStep("步数用完时复核通过：$detail")
            return LoopResult("done", run.used, detail, answer = answer)
        }
        if (answer.isNotBlank()) onStep("步数用完，屏幕上读到的答案（未确认完成）：$answer")
        return LoopResult("blocked", run.used, if (answer.isBlank()) reason else "$reason；屏幕上的答案（未确认）：$answer", answer = answer)
    }

    /**
     * 写小屏/工作区：不看屏幕变化（小屏不在读屏里），成功就算这一步完成；失败不影响任务本身。
     * 成功后记成事实，复核员才知道"已经显示/记下了"。
     */
    private suspend fun runOutputTool(run: Run, inv: DeviceTools.Invocation, onStep: (String) -> Unit): StepOutcome {
        onStep("工具：${inv.label}")
        val result = runNode(inv.node, inv.params, inv.successFlag, inv.outputs)
        run.used++
        if (!result.ok) {
            onStep("${inv.label}没成功：${result.detail}")
            return StepOutcome("done", result.detail)
        }
        delivered += inv.tool
        val text = (inv.params["text"] ?: inv.params["content"])?.toString().orEmpty()
        run.facts[if (inv.tool == "show_on_screen") "mini_screen" else "workspace_note"] =
            (if (inv.tool == "show_on_screen") "已显示：" else "已记下：") + text.take(60)
        return StepOutcome("done", inv.label)
    }

    /**
     * 目标要求"显示在小屏上 / 记下来"而计划里没做：结束时把结果交给用户。
     * 只看用户目标原文（[DeviceTools.SHOW_WORDS] / [DeviceTools.NOTE_WORDS]）；被停止的不交。
     */
    private suspend fun deliver(goal: String, result: LoopResult, onStep: (String) -> Unit) {
        if (result.status == "stopped") return
        val ok = result.status == "done"
        val wanted = DeviceTools.available(goal).map { it.id }.filter { it in DeviceTools.OUTPUT_TOOLS && it !in delivered }
        for (tool in wanted) {
            if (tool == "save_note" && !ok) continue
            val text = (if (ok) result.detail else "没完成：${result.detail}").take(DeviceTools.MAX_TOOL_TEXT)
            val args = if (tool == "show_on_screen") mapOf("text" to text, "title" to DeviceTools.noteName(goal)) else mapOf("text" to text)
            val inv = DeviceTools.resolve(tool, args, goal, emptyList()) ?: continue
            val node = runNode(inv.node, inv.params, inv.successFlag, inv.outputs)
            onStep(if (node.ok) "结果已${inv.label}" else "${inv.label}没成功：${node.detail}")
        }
    }

    /** 没有复核时，把读到的事实（电量、时间…）带进结果，提问类目标也有答案可看。 */
    private fun withFacts(detail: String, run: Run): String =
        if (run.facts.isEmpty()) detail else detail + "：" + run.facts.entries.joinToString("，") { "${it.key}=${it.value}" }

    /**
     * 打电话/发消息的对象在本机查号码（目标里的号码 → 通讯录 → 客服表）。号码只给设备工具用，不进提示词。
     * 直接拨出：本机发起且有 CALL_PHONE；否则只填进拨号盘。
     */
    private fun scopeFor(intent: ParsedIntent): ToolScope {
        if (intent.kind == IntentKind.GENERIC) return ToolScope()
        val callee = intent.contact?.let { name ->
            ServiceNumbers.resolve(name, { ContactResolver.exact(context, it) }, { ContactResolver.fuzzy(context, it) })
        }
        val canCall = context.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        return ToolScope(callee = callee, allowDirectCall = allowGoalUnlock && canCall)
    }

    private fun replanContext(run: Run, failedStep: String, reason: String) = ReplanContext(
        failedStep = failedStep,
        reason = if (run.failedTools.isEmpty()) reason else "$reason；本次已失败、不要再用的工具：${run.failedTools.joinToString()}",
        completed = run.completed.toList(),
        path = run.nav.snapshot().path,
        explored = run.nav.explored(),
        reflections = run.reflections.toList(),
        facts = run.facts.toMap(),
    )

    /**
     * 卡住后、重规划前先反思一次（软惩罚模式）：原因 + 下一步换什么 + 哪些动作别再选。
     * 反思进重规划和后面每一轮执行器的提示词；「别再选」的动作在候选里加惩罚（[Penalties.forCatalog]），不删除。
     */
    private suspend fun reflectOnFailure(run: Run, failedStep: String, reason: String, onStep: (String) -> Unit) {
        val p = planner ?: return
        if (!softPenalties) return
        val recent = run.history.takeLast(12).map { n ->
            val flag = when {
                n.error.isNotEmpty() -> "失败: ${n.error.take(60)}"
                n.changed == false -> "屏幕没变"
                n.changed == true -> "有变化"
                else -> ""
            }
            "${n.label}（$flag）"
        }
        val r = runCatching { p.reflect(run.goal, failedStep, reason, recent, run.observation) }.getOrNull() ?: return
        run.reflections += r
        while (run.reflections.size > MAX_REFLECTIONS) run.reflections.removeAt(0)
        onStep("反思：${r.cause}；下一步：${r.next}" + if (r.avoid.isEmpty()) "" else "；别再选：${r.avoid.joinToString("、")}")
    }

    /** 首次规划失败退回单步（整件事交给执行器）；重规划失败返回 null，由调用方结束。 */
    private suspend fun makePlan(run: Run, replan: ReplanContext?, onStep: (String) -> Unit): Plan? {
        val single = Plan(listOf(PlanStep(run.goal, "目标在屏幕上已完成")))
        val p = planner ?: return single
        run.observation = ScreenObserver.observe(context)
        val plan = runCatching { p.plan(run.goal, run.observation, run.apps, replan, run.scope) }.getOrNull()
        if (plan == null) {
            if (replan != null) return null
            onStep("规划不可用，整件事交给执行器")
            return single
        }
        val head = if (replan == null) "规划" else "新计划"
        onStep("$head（${plan.steps.size} 步）：" + plan.steps.mapIndexed { i, s ->
            "${i + 1}. ${s.instruction}" + (s.tool?.let { " [工具 $it]" } ?: "")
        }.joinToString("；"))
        return plan
    }

    /** 每步的步数上限：单步拿全部剩余，多步时一步不超过 [STEP_BUDGET]，给后面的步骤留余量。 */
    private fun budgetFor(run: Run, plan: Plan): Int {
        val left = maxSteps - run.used
        return if (plan.steps.size <= 1) left else minOf(left, maxOf(STEP_BUDGET, maxSteps / 4))
    }

    private fun stepContext(run: Run, step: PlanStep, index: Int, total: Int) = StepContext(
        goal = run.goal,
        instruction = step.instruction,
        doneWhen = step.doneWhen,
        index = index,
        total = total,
        completed = run.completed.toList(),
        facts = run.facts.toMap(),
        nav = run.nav.snapshot(),
        reflections = run.reflections.toList(),
    )

    /** 工具步：直接调设备工具。工具不合法或没起作用时，把同一步交给界面执行器。 */
    private suspend fun runToolStep(
        run: Run, step: PlanStep, index: Int, total: Int, budget: Int, onStep: (String) -> Unit,
    ): StepOutcome {
        val args = step.args.mapValues { (_, v) -> DeviceTools.fillFacts(v, run.facts) }
        val output = step.tool in DeviceTools.OUTPUT_TOOLS
        val noUi = step.tool in DeviceTools.NO_UI_FALLBACK
        val inv = DeviceTools.resolve(step.tool!!, args, run.goal, run.apps, run.scope)
            ?: return if (noUi) StepOutcome("done", "跳过（参数不合法）") else runUiStep(run, step, index, total, budget, onStep)
        if (output) return runOutputTool(run, inv, onStep)
        val toolKey = "${inv.tool}|${inv.args}"
        if (toolKey in run.failedTools) {
            onStep("工具「${inv.label}」本次已失败过，直接在界面上完成这一步")
            return runUiStep(run, step, index, total, budget, onStep)
        }
        onStep("工具：${inv.label}")
        val before = run.observation
        val result = runNode(inv.node, inv.params, inv.successFlag, inv.outputs)
        run.used++
        run.facts.putAll(result.outputs)
        // 应用冷启动常要 1~2 秒：只看 700ms 时的前台会把成功判成失败，再在界面上重做一遍，白花一步
        var after = ScreenObserver.observe(context)
        if (result.ok && inv.expectPackage != null) {
            var waited = 0L
            delay(700)
            after = ScreenObserver.observe(context)
            while (!after.packageName.contains(inv.expectPackage, ignoreCase = true) && waited < 1_800L) {
                delay(450)
                waited += 450
                after = ScreenObserver.observe(context)
            }
        } else {
            delay(700)
            after = ScreenObserver.observe(context)
        }
        val pkgOk = inv.expectPackage == null || after.packageName.contains(inv.expectPackage, ignoreCase = true)
        val changed = !LoopPolicy.sameScreen(after, before)
        run.history += StepNote(
            "tool_${inv.tool}", "USE_TOOL", inv.label, changed = changed,
            signature = "USE_TOOL|${inv.tool}|${inv.args}", error = if (result.ok) "" else result.detail, planStep = run.seq,
        )
        run.nav.record(after, AppAccessibilityProvider.foregroundActivity, "USE_TOOL", inv.label, changed, result.ok)
        run.observation = after
        if (result.ok && pkgOk) {
            recorded += RecordedAction(inv.node, inv.params, inv.label, successFlag = inv.successFlag)
            if (result.outputs.isNotEmpty()) onStep("读到：" + result.outputs.entries.joinToString("，") { "${it.key}=${it.value}" })
            return StepOutcome("done", inv.label)
        }
        run.failedTools += toolKey
        if (noUi) {
            onStep("${inv.label}没成功：${result.detail}")
            return StepOutcome("done", result.detail)
        }
        onStep("工具没起作用（${if (!result.ok) result.detail else "前台不是预期页面"}），改为在界面上完成这一步")
        return runUiStep(run, step, index, total, budget, onStep)
    }

    /**
     * 界面步：读屏 → 决策 → 执行 → 再读屏，直到这一步的完成条件成立。
     *
     * 失败不一步就退出：低置信只记日志；动作失败换次优候选重试一次；
     * 屏幕连续没变化或模型重复无效动作时，先替它换招（返回/滚动），换过还在重复才结束这一步。
     */
    private suspend fun runUiStep(
        run: Run, step: PlanStep, index: Int, total: Int, budget: Int, onStep: (String) -> Unit,
    ): StepOutcome {
        var stale = 0
        var waits = 0
        var unsure = 0
        var noChange = 0
        var recoveries = 0
        var invalidPicks = 0
        var badReplies = 0
        var nodeFailures = 0
        var autoTaps = 0
        var redecides = 0
        var nextCheckAt = STEP_CHECK_EVERY
        val excluded = mutableSetOf<String>()
        val start = run.used
        while (run.used - start < budget && run.used < maxSteps) {
            if (stopRequested) return StepOutcome("stopped", "已停止")
            val observation = run.observation
            LoopPolicy.screenVerdict(observation)?.let {
                onStep(it)
                return StepOutcome("fatal", it)
            }
            // 技能模板给了代码判定的完成条件：屏幕上成立就推进，不问模型（也不会被模型的 DONE 骗）
            if (step.check?.satisfied(observation) == true) {
                onStep("这一步已完成：${step.doneWhen}")
                return StepOutcome("done", step.doneWhen)
            }
            // 每做两步问一次规划模型（看截图）"这一步完成没有"：执行器常常已经到了却认不出来——
            // AndroidWorld（Joplin）实测点进 Health 文件夹后，因为标题栏不写「Health」，执行器又去开侧边栏，把步数耗光。
            // 提问类目标每一步都问（单步计划也问）：答案常常要翻好几屏凑（这周的活动、某天的所有日程），
            // 复核员只看最后一屏，前面几屏的内容要靠这里记成笔记。只是一次模型调用，不点任何东西，不算步数。
            val checker = planner
            val question = AnswerFormat.isQuestion(run.goal)
            if (stepChecks && checker != null && (total > 1 || question) && run.used - start >= nextCheckAt) {
                nextCheckAt = run.used - start + if (question) 1 else STEP_CHECK_EVERY
                val check = runCatching { checker.checkStep(run.goal, step.instruction, step.doneWhen, observation) }.getOrNull()
                check?.note?.let { saveNote(run, it, onStep) }
                // 单步的提问计划：复查只为记笔记。它看到第一屏就会说"完成了"，可答案可能还要往下翻
                if (check?.done == true && total > 1) {
                    onStep("复查：这一步的完成条件已经成立（${step.doneWhen}）")
                    return StepOutcome("done", step.doneWhen)
                }
            }
            // 代码先点名字对得上的那一行（最多两次）；点错了屏幕会变，下一轮由 check 或执行器接手
            val auto = step.autoTap?.takeIf { autoTaps < MAX_AUTO_TAPS }?.let { Catalogs.leadingMatch(observation, it) }
            if (auto != null) {
                autoTaps++
                val action = CatalogAction(
                    id = "auto_${auto.index}", operation = "TAP", target = auto.index,
                    label = "点击「${step.autoTap}」", tool = "ACTION_CLICK",
                    params = mapOf("target" to "${auto.centerX},${auto.centerY}"),
                    centerX = auto.centerX, centerY = auto.centerY, matchText = auto.text, viewId = auto.viewId,
                )
                onStep(action.label)
                val changed = execute(run, action, observation)
                noChange = if (changed) 0 else noChange + 1
                continue
            }
            // 判重/兜圈只看本步；死路名单看整个任务（重规划后也不再进已证明走不通的入口）
            val stepHistory = run.history.filter { it.planStep == run.seq }
            val ctx = stepContext(run, step, index, total)
            val built = Catalogs.build(
                run.goal, observation, run.apps, allowGoalUnlock, ctx, step.text, run.failedTools, run.facts.keys, run.scope,
                tappedBefore = { el ->
                    val sig = "TAP|${el.viewId.ifEmpty { el.text }}|${observation.packageName}"
                    stepHistory.any { it.signature == sig }
                },
                pointTaps = pointTaps,
            )
            // 软惩罚：死路和做过没用的动作留在候选里、按分数降权；硬模式（旧行为）：死路直接删掉
            val scored = if (softPenalties) {
                built.copy(penalties = Penalties.forCatalog(built, run.history, observation, run.reflections.flatMap { it.avoid }))
            } else {
                built.without(LoopPolicy.deadEnds(run.history)) { LoopPolicy.signatureOf(it, observation) }
            }
            // 本步里判过"在重复"的动作：这一步内不再给（[MAX_REDECIDES]）
            val catalog = if (excluded.isEmpty()) scored else scored.without(excluded) { LoopPolicy.signatureOf(it, observation) }
            obsCounts.merge(observation.source, 1, Int::plus)
            onStep("读屏 ${ctx.nav?.path ?: observation.packageName} / ${observation.source} / ${observation.elements.size} 个控件（已用 ${run.used} 步，剩 ${maxSteps - run.used} 步）")
            catalog.guard?.let { onStep("限制：$it") }
            val decision = provider.decide(run.goal, observation, catalog, run.history, ctx)
            if (decision.fatal) return StepOutcome("fatal", decision.detail)

            // 执行器在同一次请求里判断"这一步已经完成"，概率够高就直接推进（只在多步计划里用，最终还有复核兜底）
            val stepDone = decision.stepDone
            if (ctx.multiStep && stepDone != null && stepDone >= STEP_DONE_THRESHOLD && decision.operation != "BLOCKED") {
                onStep("这一步的完成条件已成立（${fmt(stepDone)}）")
                return StepOutcome("done", step.doneWhen)
            }

            when (decision.status) {
                "done" -> return StepOutcome("done", decision.detail.ifBlank { "完成" })
                "blocked" -> return StepOutcome("blocked", decision.detail.ifBlank { "无法继续" })
                "error" -> {
                    // 模型选了不在候选里的目标（多半是照着控件一览选了个不可点的序号）：给它重选机会，不整轮报废
                    if (decision.operation.isNotEmpty() && invalidPicks < LoopPolicy.MAX_INVALID_PICKS) {
                        invalidPicks++
                        onStep("决策无效（${decision.detail}），重新选择")
                        run.history += StepNote(
                            "invalid_${run.seq}_$invalidPicks", decision.operation,
                            "无效选择 ${decision.operation}/${decision.target.orEmpty()}",
                            changed = false, signature = "INVALID|${decision.operation}|${decision.target.orEmpty()}",
                            error = decision.detail, planStep = run.seq,
                        )
                        continue
                    }
                    // 没拿到可用的决策（回复不是 JSON、网络失败）：同一屏再问一次，不为一次坏回复重规划
                    if (decision.operation.isEmpty() && badReplies < MAX_BAD_REPLIES) {
                        badReplies++
                        onStep("决策没拿到（${decision.detail}），再问一次")
                        continue
                    }
                    return StepOutcome("error", decision.detail.ifBlank { "决策失败" })
                }
                "wait" -> {
                    waits++
                    if (waits >= LoopPolicy.MAX_WAITS) {
                        // 屏幕多半早就加载好了：执行器连着选等待，是因为在候选里找不到要点的东西。原因写清楚，反思和重规划才不会去"等它加载"
                        return StepOutcome("blocked", "执行器连续 ${LoopPolicy.MAX_WAITS} 次选择等待（多半是候选里找不到要点的控件）")
                    }
                    onStep("等待")
                    delay(800)
                    run.used++
                    run.observation = ScreenObserver.observe(context)
                    continue
                }
            }
            waits = 0
            val action = decision.action ?: return StepOutcome("error", "决策没有可执行动作")
            when (LoopPolicy.confidenceVerdict(decision.confidence)) {
                LoopPolicy.Verdict.DROP -> {
                    unsure++
                    onStep("置信度 ${fmt(decision.confidence)} 极低（低于 ${LoopPolicy.DISCARD_CONFIDENCE}），跳过「${action.label}」")
                    if (unsure >= LoopPolicy.MAX_UNSURE) return StepOutcome("blocked", "决策连续极低置信")
                    delay(600)
                    run.observation = ScreenObserver.observe(context)
                    continue
                }
                // 低置信照样执行一次：模型给的 0.2~0.3 是"不确定但倾向"，不是"别做"。
                LoopPolicy.Verdict.EXECUTE_LOW -> {
                    unsure++
                    onStep("置信度 ${fmt(decision.confidence)} 偏低，仍执行「${action.label}」看结果")
                    if (unsure >= LoopPolicy.MAX_UNSURE) return StepOutcome("blocked", "决策连续低置信")
                }
                LoopPolicy.Verdict.EXECUTE -> unsure = 0
            }
            val signature = LoopPolicy.signatureOf(action, observation)
            // 模型想再做一遍已经重复/兜圈的动作：先替它换一次招（返回/滚动）；换过还在重复才结束这一步
            val repeatReason = when {
                LoopPolicy.repeatCount(stepHistory, signature) >= LoopPolicy.MAX_SAME_ACTION ->
                    "同一动作「${action.label}」已连续 ${LoopPolicy.MAX_SAME_ACTION} 次"
                LoopPolicy.recentCount(stepHistory, signature) >= LoopPolicy.MAX_IN_WINDOW ->
                    "「${action.label}」在最近 ${LoopPolicy.CYCLE_WINDOW} 步里已做了 ${LoopPolicy.MAX_IN_WINDOW} 次，在原地兜圈"
                else -> null
            }
            if (repeatReason != null && recoveries >= LoopPolicy.MAX_RECOVERIES) {
                return StepOutcome("blocked", "$repeatReason，换招后仍在重复")
            }
            // 先让模型在去掉这个动作的候选里重选（不花步数）；重选还是不行，才由代码换招（返回/滚动，要花一步）。
            // AndroidWorld 官方步数下最简单的任务只有 10 步，换招平均每个失败任务要吃掉 1~2 步。
            if (repeatReason != null && redecides < MAX_REDECIDES) {
                redecides++
                excluded += signature
                onStep("$repeatReason，去掉它重新选择")
                continue
            }
            if (action.operation == "TAP" && !stillThere(action, observation)) {
                stale++
                onStep("目标已离开原位置，不点击")
                if (stale >= 2) return StepOutcome("blocked", "屏幕一直在变，点不准")
                run.observation = ScreenObserver.observe(context)
                continue
            }
            stale = 0

            if (noChange >= LoopPolicy.FORCE_AFTER_NO_CHANGE || repeatReason != null) {
                if (noChange >= LoopPolicy.STOP_AFTER_NO_CHANGE) {
                    return StepOutcome("blocked", "屏幕连续 $noChange 次没有变化")
                }
                val kick = LoopPolicy.recoveryAction(catalog, stepHistory, avoid = action.operation)
                    ?: return StepOutcome("blocked", "${repeatReason ?: "屏幕连续 $noChange 次没有变化"}，且没有可用的换招动作")
                val reason = repeatReason ?: "屏幕连续 $noChange 次没有变化"
                recoveries++
                onStep("$reason，改试「${kick.label}」")
                val changed = execute(run, kick, observation)
                noChange = if (changed) 0 else noChange + 1
                continue
            }

            onStep(action.label)
            var result = runNode(action.tool ?: return StepOutcome("error", "动作缺少节点"), action.params, action.successFlag, action.outputs)
            run.used++
            // 执行器选的工具快捷方式失败（多半是缺权限）：本次任务不再提供它
            if (!result.ok && action.operation == "USE_TOOL") action.toolKey?.let { run.failedTools += it }
            // 跳页类工具（open_settings / open_app）成功了但屏幕没变：已经在那一页了，再跳一次还是一样。
            // AndroidWorld 实测：已在蓝牙页时模型反复选「直接打开蓝牙设置页」，直到被判兜圈结束。
            if (result.ok && action.operation == "USE_TOOL" && action.tool == "ACTION_LAUNCH_APP") {
                delay(500)
                if (LoopPolicy.sameScreen(ScreenObserver.observe(context), observation)) action.toolKey?.let { run.failedTools += it }
            }
            var executed = action
            if (!result.ok) {
                onStep("${action.tool} 失败: ${result.detail}")
                // 换同操作的次优候选再试一次：同一文字常有多层可点节点，第一候选点不到不代表这步走不通
                val retry = LoopPolicy.retryCandidate(catalog, action, run.goal, run.history)
                if (retry == null) {
                    record(run, action, observation, observation, changed = false, ok = false, error = result.detail)
                    // 一个动作失败不等于这一步走不通：记下失败（软惩罚会压低它），让执行器换一个；同一步里失败多次才结束
                    nodeFailures++
                    if (nodeFailures >= MAX_NODE_FAILURES) return StepOutcome("error", "${action.tool} 失败: ${result.detail}")
                    noChange++
                    continue
                }
                onStep("改用「${retry.label}」重试")
                result = runNode(retry.tool ?: return StepOutcome("error", "重试动作缺少节点"), retry.params, retry.successFlag, retry.outputs)
                run.used++
                executed = retry
            }
            run.facts.putAll(result.outputs)
            delay(700)
            val after = ScreenObserver.observe(context)
            // 读数工具不改屏幕：读到了值就是进展，不能算"无变化"（否则会被反复调用直到判兜圈）
            val changed = !LoopPolicy.sameScreen(after, observation) || (result.ok && result.outputs.isNotEmpty())
            record(run, executed, observation, after, changed, result.ok, if (result.ok) "" else result.detail)
            if (!result.ok) {
                nodeFailures++
                if (nodeFailures >= MAX_NODE_FAILURES) return StepOutcome("error", "「${executed.label}」失败: ${result.detail}")
            }
            noChange = if (changed) 0 else noChange + 1
            if (!changed) onStep("屏幕没有变化（连续 $noChange 次）")
        }
        return StepOutcome("blocked", if (run.used >= maxSteps) "达到步数上限 $maxSteps" else "这一步用完了 $budget 步还没完成")
    }

    /** 执行一个代码选的动作（换招用），记账并返回屏幕是否变化。 */
    private suspend fun execute(run: Run, action: CatalogAction, before: ScreenObservation): Boolean {
        val tool = action.tool ?: return false
        val result = runNode(tool, action.params, action.successFlag, action.outputs)
        run.used++
        delay(700)
        val after = ScreenObserver.observe(context)
        val changed = result.ok && !LoopPolicy.sameScreen(after, before)
        record(run, action, before, after, changed, result.ok, if (result.ok) "" else "$tool 失败: ${result.detail}")
        return changed
    }

    /** 记一步：history（判重/死路/提示词）+ 导航栈 + 当前观测。 */
    private fun record(
        run: Run, action: CatalogAction, before: ScreenObservation, after: ScreenObservation,
        changed: Boolean, ok: Boolean, error: String,
    ) {
        run.history += StepNote(
            action.id, action.operation, action.label,
            changed = changed,
            signature = LoopPolicy.signatureOf(action, before),
            error = error,
            leftApp = after.packageName.isNotEmpty() && before.packageName.isNotEmpty() && after.packageName != before.packageName,
            planStep = run.seq,
        )
        val label = action.matchText?.takeIf { it.isNotBlank() } ?: action.label
        run.nav.record(after, AppAccessibilityProvider.foregroundActivity, action.operation, label, changed, ok)
        run.observation = after
        // 输入不一定改变"屏幕签名"，也算有效果
        val tool = action.tool
        if (ok && tool != null && (changed || tool == "ACTION_INPUT_TEXT")) {
            recorded += RecordedAction(tool, action.params, action.label, action.matchText, action.successFlag)
        }
    }

    /**
     * 决策期间屏幕可能已经变了：再读一次，确认同样文字的控件还在原位附近。
     * 复查读不到（OCR 限频、树暂时为空）按"未知"放行，不当成目标离开——否则 OCR 场景下点击几乎必被拦。
     */
    private suspend fun stillThere(action: CatalogAction, before: ScreenObservation): Boolean {
        val text = action.matchText ?: return true
        val x = action.centerX ?: return true
        val y = action.centerY ?: return true
        val again = ScreenObserver.observe(context)
        if (again.elements.isEmpty() || again.source != before.source) return true
        return again.elements.any {
            // 图标按钮的 matchText 是生成的名字（Catalogs.iconLabel），复查时也按同样的规则算
            val label = it.text.ifBlank { Catalogs.iconLabel(it, again.screenWidth, again.screenHeight).orEmpty() }
            label == text && kotlin.math.abs(it.centerX - x) <= 120 && kotlin.math.abs(it.centerY - y) <= 120
        }
    }

    private fun fmt(value: Double): String = "%.2f".format(value)

    private suspend fun runNode(tool: String, params: Map<String, Any>, successFlag: String?, outputs: List<String>): NodeResult {
        val handler = factory.get(tool) ?: return NodeResult(false, "未知节点")
        val node = WorkflowNode().apply {
            id = "agent_loop"
            type = tool
            this.params = params
        }
        val ctx = WorkflowContext(context)
        ctx.origin = if (allowGoalUnlock) WorkflowContext.ORIGIN_AGENT else ORIGIN_REMOTE_AGENT
        ctx.runKey = runId
        val result = withContext(Dispatchers.Default) {
            withTimeoutOrNull(30_000) {
                try {
                    Result.success(handler.handle(node, ctx))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Result.failure(e)
                }
            }
        }
        if (result == null) return NodeResult(false, "超时")
        if (result.isFailure) return NodeResult(false, result.exceptionOrNull()?.message ?: "异常")
        val flag = successFlag ?: when (tool) {
            "ACTION_CLICK" -> "click_success"
            "ACTION_SWIPE" -> "swipe_success"
            "ACTION_INPUT_TEXT" -> "input_success"
            "ACTION_LAUNCH_APP" -> "launch_success"
            "ACTION_GLOBAL_NAV" -> "nav_success"
            "ACTION_LONG_PRESS" -> "long_press_success"
            "ACTION_GLOBAL_KEY" -> "key_success"
            else -> null
        }
        val ok = flag == null || ctx.variables[flag] == true
        // 笔记内容要完整交给复核/小屏；其它读数短
        val values = outputs.mapNotNull { key ->
            ctx.variables[key]?.let { key to it.toString().take(if (key == "workspace_content") 600 else 80) }
        }.toMap()
        val error = ctx.variables.entries.firstOrNull { it.key.endsWith("_error") }?.value?.toString()
        return NodeResult(ok, if (ok) "ok" else (error ?: "节点返回失败"), values)
    }

    companion object {
        /** 远程（MCP agent_start）发起的 Agent 写小屏/工作区时的来源：界面上和本机 Agent 区分开。 */
        const val ORIGIN_REMOTE_AGENT = "agent:remote"

        /** 一次任务最多重规划几次（步骤卡住或复核未通过）。 */
        const val MAX_REPLANS = 2

        /** 多步计划里每步最多用几步。 */
        const val STEP_BUDGET = 8

        /** 失败反思最多留几条（新的挤掉旧的），控制提示词长度。 */
        const val MAX_REFLECTIONS = 3

        /** 一步里模型想重复同一动作时，最多几次"去掉它重选"（不花步数），之后才由代码换招。 */
        const val MAX_REDECIDES = 2

        /** 多步计划里，一步做了几个动作后复查一次完成条件（[stepChecks]）。 */
        const val STEP_CHECK_EVERY = 2
        /** 一步里允许几次"没拿到可用决策"（回复不是 JSON、网络失败）再问。 */
        const val MAX_BAD_REPLIES = 2
        /** 一步里动作执行失败（节点返回失败）几次才算这一步走不通。 */
        const val MAX_NODE_FAILURES = 3
        /** 跨屏笔记最多留几条。 */
        const val MAX_NOTES = 6
        /** 一次任务里按 replan_after 接着规划的上限：防止每版计划都写 replan_after 来回规划（录入很多条时要接着规划好几次）。 */
        const val MAX_REFRESHES = 4

        /** 技能步里代码自己点名字对得上的那一行，最多几次。 */
        const val MAX_AUTO_TAPS = 2

        /** Jev 判断"当前步骤已完成"的概率达到这个值，代码就推进到下一步。 */
        const val STEP_DONE_THRESHOLD = 0.7
    }
}
