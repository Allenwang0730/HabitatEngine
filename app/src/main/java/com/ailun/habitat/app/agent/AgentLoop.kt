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

data class LoopResult(val status: String, val steps: Int, val detail: String)

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
    fun sameScreen(a: ScreenObservation, b: ScreenObservation): Boolean =
        a.visibleText == b.visibleText &&
            a.packageName == b.packageName &&
            a.elements.size == b.elements.size &&
            a.focusedIndex == b.focusedIndex &&
            controlState(a) == controlState(b)

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
) {
    @Volatile var stopRequested: Boolean = false

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
        /** 本次任务里执行失败过的工具调用（权限不足等），重规划后不再尝试。 */
        val failedTools = mutableSetOf<String>()
    }

    private data class StepOutcome(val status: String, val detail: String)

    private data class NodeResult(val ok: Boolean, val detail: String, val outputs: Map<String, String> = emptyMap())

    suspend fun run(goal: String, onStep: (String) -> Unit): LoopResult {
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

        while (true) {
            var index = 0
            var replanned = false
            while (index < plan.steps.size) {
                if (stopRequested) return LoopResult("stopped", run.used, "已停止")
                if (run.used >= maxSteps) return LoopResult("blocked", run.used, "达到步数上限 $maxSteps")
                val step = plan.steps[index]
                run.seq++
                if (plan.steps.size > 1) onStep("步骤 ${index + 1}/${plan.steps.size}：${step.instruction}（完成条件：${step.doneWhen}）")
                val outcome = if (step.tool != null) {
                    runToolStep(run, step, index, plan.steps.size, budgetFor(run, plan), onStep)
                } else {
                    runUiStep(run, step, index, plan.steps.size, budgetFor(run, plan), onStep)
                }
                when (outcome.status) {
                    "done" -> {
                        run.completed += step.instruction
                        index++
                    }
                    "stopped" -> return LoopResult("stopped", run.used, outcome.detail)
                    "fatal" -> return LoopResult("error", run.used, outcome.detail)
                    else -> {
                        if (planner == null || run.replans >= MAX_REPLANS || run.used >= maxSteps) {
                            return LoopResult(if (outcome.status == "error") "error" else "blocked", run.used, outcome.detail)
                        }
                        run.replans++
                        onStep("这一步卡住（${outcome.detail}），重新规划（第 ${run.replans} 次）")
                        plan = makePlan(run, replanContext(run, step.instruction, outcome.detail), onStep)
                            ?: return LoopResult("blocked", run.used, outcome.detail)
                        replanned = true
                        break
                    }
                }
            }
            if (replanned) continue

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
                val detail = listOf(review.answer, review.reason).filter { it.isNotBlank() }.joinToString(" — ").ifBlank { "完成" }
                onStep("复核通过：$detail")
                return LoopResult("done", run.used, detail)
            }
            onStep("复核未通过：${review.reason}")
            if (run.replans >= MAX_REPLANS || run.used >= maxSteps) {
                return LoopResult("blocked", run.used, "复核未通过：${review.reason}")
            }
            run.replans++
            plan = makePlan(run, replanContext(run, "复核", review.reason), onStep)
                ?: return LoopResult("blocked", run.used, "复核未通过：${review.reason}")
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
    )

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
        return if (plan.steps.size <= 1) left else minOf(left, STEP_BUDGET)
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
    )

    /** 工具步：直接调设备工具。工具不合法或没起作用时，把同一步交给界面执行器。 */
    private suspend fun runToolStep(
        run: Run, step: PlanStep, index: Int, total: Int, budget: Int, onStep: (String) -> Unit,
    ): StepOutcome {
        val inv = DeviceTools.resolve(step.tool!!, step.args, run.goal, run.apps, run.scope)
            ?: return runUiStep(run, step, index, total, budget, onStep)
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
        delay(700)
        val after = ScreenObserver.observe(context)
        val pkgOk = inv.expectPackage == null || after.packageName.contains(inv.expectPackage, ignoreCase = true)
        val changed = !LoopPolicy.sameScreen(after, before)
        run.history += StepNote(
            "tool_${inv.tool}", "USE_TOOL", inv.label, changed = changed,
            signature = "USE_TOOL|${inv.tool}|${inv.args}", error = if (result.ok) "" else result.detail, planStep = run.seq,
        )
        run.nav.record(after, AppAccessibilityProvider.foregroundActivity, "USE_TOOL", inv.label, changed, result.ok)
        run.observation = after
        if (result.ok && pkgOk) {
            if (result.outputs.isNotEmpty()) onStep("读到：" + result.outputs.entries.joinToString("，") { "${it.key}=${it.value}" })
            return StepOutcome("done", inv.label)
        }
        run.failedTools += toolKey
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
            // 判重/兜圈只看本步；死路名单看整个任务（重规划后也不再进已证明走不通的入口）
            val stepHistory = run.history.filter { it.planStep == run.seq }
            val ctx = stepContext(run, step, index, total)
            val catalog = Catalogs.build(
                run.goal, observation, run.apps, allowGoalUnlock, ctx, step.text, run.failedTools, run.facts.keys, run.scope,
            ).without(LoopPolicy.deadEnds(run.history)) { LoopPolicy.signatureOf(it, observation) }
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
                    return StepOutcome("error", decision.detail.ifBlank { "决策失败" })
                }
                "wait" -> {
                    waits++
                    if (waits >= LoopPolicy.MAX_WAITS) {
                        return StepOutcome("blocked", "连续等待 ${LoopPolicy.MAX_WAITS} 次，屏幕没有加载出来")
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
            var executed = action
            if (!result.ok) {
                onStep("${action.tool} 失败: ${result.detail}")
                // 换同操作的次优候选再试一次：同一文字常有多层可点节点，第一候选点不到不代表这步走不通
                val retry = LoopPolicy.retryCandidate(catalog, action, run.goal, run.history)
                if (retry == null) {
                    record(run, action, observation, observation, changed = false, ok = false, error = result.detail)
                    return StepOutcome("error", "${action.tool} 失败: ${result.detail}")
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
            if (!result.ok) return StepOutcome("error", "「${executed.label}」失败: ${result.detail}")
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
            it.text == text && kotlin.math.abs(it.centerX - x) <= 120 && kotlin.math.abs(it.centerY - y) <= 120
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
        val values = outputs.mapNotNull { key -> ctx.variables[key]?.let { key to it.toString().take(80) } }.toMap()
        val error = ctx.variables.entries.firstOrNull { it.key.endsWith("_error") }?.value?.toString()
        return NodeResult(ok, if (ok) "ok" else (error ?: "节点返回失败"), values)
    }

    companion object {
        /** 一次任务最多重规划几次（步骤卡住或复核未通过）。 */
        const val MAX_REPLANS = 2

        /** 多步计划里每步最多用几步。 */
        const val STEP_BUDGET = 8

        /** Jev 判断"当前步骤已完成"的概率达到这个值，代码就推进到下一步。 */
        const val STEP_DONE_THRESHOLD = 0.7
    }
}
