package com.ailun.habitat.app.agent

import android.content.Context
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

    /** 屏幕是否"看起来没变"。文字、前台包、控件数、焦点都算，单看文字会漏判。 */
    fun sameScreen(a: ScreenObservation, b: ScreenObservation): Boolean =
        a.visibleText == b.visibleText &&
            a.packageName == b.packageName &&
            a.elements.size == b.elements.size &&
            a.focusedIndex == b.focusedIndex

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
        val tried = history.map { it.actionId }.toSet() + failed.id
        // 已经在本屏失败过的锚点不再试（同一 viewId/文字再点一次结果一样）
        val failedAnchors = history.filter { it.changed == false }
            .map { it.signature.substringAfter('|').substringBeforeLast('|') }
            .toSet()
        return catalog.candidatesFor(failed.operation, goal, exclude = tried)
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
 * 读屏 → 决策（只选题）→ 用 Habitat 节点执行 → 再读屏。
 *
 * 失败不再一步就退出：
 * - 低置信只记日志，不丢步（真机上两个云端模型都会给 0.2~0.3，硬丢掉等于让模型没法干活）。
 * - 动作失败换同操作的次优候选重试一次，并把失败原因写进 history 供模型换招。
 * - 屏幕连续没变化时主动注入 SCROLL/BACK 升级，而不是原地重复同一个动作。
 */
class AgentLoop(
    private val context: Context,
    private val provider: DecisionProvider,
    private val maxSteps: Int = 12,
    /** 本机发起为 true；远程发起为 false，目标原文不能解锁敏感按钮和权限弹窗。 */
    private val allowGoalUnlock: Boolean = true,
) {
    @Volatile var stopRequested: Boolean = false

    private val factory by lazy {
        NodeHandlerFactory(AppAccessibilityProvider, ShizukuShellExecutor(context))
            .apply { applyAppHandlers(context) }
    }

    suspend fun run(goal: String, onStep: (String) -> Unit): LoopResult {
        if (goal.isBlank()) return LoopResult("error", 0, "目标为空")
        val apps = ScreenObserver.launcherApps(context)
        val history = mutableListOf<StepNote>()
        var stale = 0
        var waits = 0
        var unsure = 0
        var noChange = 0
        var recoveries = 0
        var invalidPicks = 0
        var steps = 0
        var observation = ScreenObserver.observe(context)
        while (steps < maxSteps && !stopRequested) {
            LoopPolicy.screenVerdict(observation)?.let {
                onStep(it)
                return LoopResult("error", steps, it)
            }
            val left = maxSteps - steps
            val deadEnds = LoopPolicy.deadEnds(history)
            val catalog = Catalogs.build(goal, observation, apps, allowGoalUnlock)
                .without(deadEnds) { LoopPolicy.signatureOf(it, observation) }
            onStep("读屏 ${observation.packageName} / ${observation.source} / ${observation.elements.size} 个控件（已用 $steps 步，剩 $left 步）")
            catalog.guard?.let { onStep("限制：$it") }
            val decision = provider.decide(goal, observation, catalog, history)
            when (decision.status) {
                "done" -> return LoopResult("done", steps, decision.detail.ifBlank { "完成" })
                "blocked" -> return LoopResult("blocked", steps, decision.detail.ifBlank { "无法继续" })
                "error" -> {
                    // 模型选了不在候选里的目标（多半是照着控件一览选了个不可点的序号）：给它一次重选机会，不整轮报废
                    if (decision.operation.isNotEmpty() && invalidPicks < LoopPolicy.MAX_INVALID_PICKS) {
                        invalidPicks++
                        onStep("决策无效（${decision.detail}），重新选择")
                        history.add(
                            StepNote(
                                "invalid_$invalidPicks", decision.operation, "无效选择 ${decision.operation}/${decision.target.orEmpty()}",
                                changed = false, signature = "INVALID|${decision.operation}|${decision.target.orEmpty()}",
                                error = decision.detail,
                            ),
                        )
                        continue
                    }
                    return LoopResult("error", steps, decision.detail.ifBlank { "决策失败" })
                }
                "wait" -> {
                    waits++
                    if (waits >= LoopPolicy.MAX_WAITS) {
                        return LoopResult("blocked", steps, "连续等待 ${LoopPolicy.MAX_WAITS} 次，屏幕没有加载出来")
                    }
                    onStep("等待")
                    delay(800)
                    steps++
                    observation = ScreenObserver.observe(context)
                    continue
                }
            }
            waits = 0
            val action = decision.action ?: return LoopResult("error", steps, "决策没有可执行动作")
            when (LoopPolicy.confidenceVerdict(decision.confidence)) {
                LoopPolicy.Verdict.DROP -> {
                    unsure++
                    onStep("置信度 ${fmt(decision.confidence)} 极低（低于 ${LoopPolicy.DISCARD_CONFIDENCE}），跳过「${action.label}」")
                    if (unsure >= LoopPolicy.MAX_UNSURE) return LoopResult("blocked", steps, "决策连续极低置信，停止")
                    delay(600)
                    observation = ScreenObserver.observe(context)
                    continue
                }
                // 低置信照样执行一次：模型给的 0.2~0.3 是"不确定但倾向"，不是"别做"。
                LoopPolicy.Verdict.EXECUTE_LOW -> {
                    unsure++
                    onStep("置信度 ${fmt(decision.confidence)} 偏低，仍执行「${action.label}」看结果")
                    if (unsure >= LoopPolicy.MAX_UNSURE) return LoopResult("blocked", steps, "决策连续低置信，停止")
                }
                LoopPolicy.Verdict.EXECUTE -> unsure = 0
            }
            val signature = LoopPolicy.signatureOf(action, observation)
            // 模型想再做一遍已经重复/兜圈的动作：不是直接结束，先替它换一次招（返回/滚动）；
            // 换过招还在重复才停。真机实测：误进「系统管理与升级」后连续下滑无效，旧规则直接停，其实按一次返回就能回到正路。
            val repeatReason = when {
                LoopPolicy.repeatCount(history, signature) >= LoopPolicy.MAX_SAME_ACTION ->
                    "同一动作「${action.label}」已连续 ${LoopPolicy.MAX_SAME_ACTION} 次"
                LoopPolicy.recentCount(history, signature) >= LoopPolicy.MAX_IN_WINDOW ->
                    "「${action.label}」在最近 ${LoopPolicy.CYCLE_WINDOW} 步里已做了 ${LoopPolicy.MAX_IN_WINDOW} 次，在原地兜圈"
                else -> null
            }
            if (repeatReason != null && recoveries >= LoopPolicy.MAX_RECOVERIES) {
                return LoopResult("blocked", steps, "$repeatReason，换招后仍在重复，停止")
            }
            if (action.operation == "TAP" && !stillThere(action, observation)) {
                stale++
                onStep("目标已离开原位置，不点击")
                if (stale >= 2) return LoopResult("blocked", steps, "屏幕已变化，停止")
                observation = ScreenObserver.observe(context)
                continue
            }
            stale = 0

            // 连续没变化，或模型要重复已经无效的动作：先注入一次返回/滚动把屏幕推动起来，再让模型继续。
            if (noChange >= LoopPolicy.FORCE_AFTER_NO_CHANGE || repeatReason != null) {
                if (noChange >= LoopPolicy.STOP_AFTER_NO_CHANGE) {
                    return LoopResult("blocked", steps, "屏幕连续 $noChange 次没有变化，已无法推进")
                }
                val kick = LoopPolicy.recoveryAction(catalog, history, avoid = action.operation)
                    ?: return LoopResult("blocked", steps, "${repeatReason ?: "屏幕连续 $noChange 次没有变化"}，且没有可用的换招动作")
                val kickTool = kick.tool ?: return LoopResult("error", steps, "换招动作缺少节点")
                recoveries++
                onStep("${repeatReason ?: "屏幕连续 $noChange 次没有变化"}，改试「${kick.label}」")
                steps++
                val (kickOk, kickDetail) = runNode(kickTool, kick.params)
                delay(700)
                val kicked = ScreenObserver.observe(context)
                val kickedChanged = !LoopPolicy.sameScreen(kicked, observation)
                history.add(
                    StepNote(
                        kick.id, kick.operation, kick.label,
                        changed = kickOk && kickedChanged,
                        signature = LoopPolicy.signatureOf(kick, observation),
                        error = if (kickOk) "" else "$kickTool 失败: $kickDetail",
                    ),
                )
                noChange = if (kickOk && kickedChanged) 0 else noChange + 1
                if (!kickOk) onStep("「${kick.label}」也没成功：$kickDetail")
                observation = kicked
                continue
            }

            val tool = action.tool ?: return LoopResult("error", steps, "动作缺少节点")
            onStep(action.label)
            var (ok, detail) = runNode(tool, action.params)
            steps++
            var executed = action
            var failure = ""
            if (!ok) {
                onStep("$tool 失败: $detail")
                // 换同操作的次优候选再试一次：同一文字常有多层可点节点，第一候选点不到不代表这步走不通。
                val retry = LoopPolicy.retryCandidate(catalog, action, goal, history)
                if (retry == null) {
                    history.add(StepNote(action.id, action.operation, action.label, changed = false, signature = signature, error = "$tool 失败: $detail"))
                    return LoopResult("error", steps, "$tool 失败: $detail")
                }
                val retryTool = retry.tool ?: return LoopResult("error", steps, "重试动作缺少节点")
                onStep("改用「${retry.label}」重试")
                val (retryOk, retryDetail) = runNode(retryTool, retry.params)
                steps++
                executed = retry
                ok = retryOk
                detail = retryDetail
                failure = "$tool 失败: $detail"
            }
            delay(700)
            val after = ScreenObserver.observe(context)
            val changed = !LoopPolicy.sameScreen(after, observation)
            history.add(
                StepNote(
                    executed.id, executed.operation, executed.label,
                    changed = changed,
                    signature = LoopPolicy.signatureOf(executed, observation),
                    error = if (ok) "" else failure,
                    leftApp = after.packageName.isNotEmpty() && observation.packageName.isNotEmpty() &&
                        after.packageName != observation.packageName,
                ),
            )
            if (!ok) {
                onStep("「${executed.label}」失败: $detail")
                return LoopResult("error", steps, "$tool 失败: $detail")
            }
            noChange = if (changed) 0 else noChange + 1
            if (!changed) onStep("屏幕没有变化（连续 $noChange 次）")
            observation = after
        }
        if (stopRequested) return LoopResult("stopped", steps, "已停止")
        return LoopResult("blocked", steps, "达到步数上限 $maxSteps")
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

    private suspend fun runNode(tool: String, params: Map<String, Any>): Pair<Boolean, String> {
        val handler = factory.get(tool) ?: return false to "未知节点"
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
        if (result == null) return false to "超时"
        if (result.isFailure) return false to (result.exceptionOrNull()?.message ?: "异常")
        val flag = when (tool) {
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
        return ok to if (ok) "ok" else "节点返回失败"
    }
}
