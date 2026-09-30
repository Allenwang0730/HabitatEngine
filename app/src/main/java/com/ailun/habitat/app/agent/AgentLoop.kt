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
 * 读屏 → 决策（只选题）→ 用 Habitat 节点执行 → 再读屏。
 * 同一动作连续三次、连续等待、点击目标已经不在原位置、连续低置信，都停下来，不自动重放。
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
        var steps = 0
        var observation = ScreenObserver.observe(context)
        while (steps < maxSteps && !stopRequested) {
            if (observation.source == "none" && observation.elements.isEmpty()) {
                return LoopResult("error", steps, "无障碍未连接，读不到屏幕")
            }
            val catalog = Catalogs.build(goal, observation, apps, allowGoalUnlock)
            onStep("读屏 ${observation.packageName} / ${observation.source} / ${observation.elements.size} 个控件")
            catalog.guard?.let { onStep("限制：$it") }
            val decision = provider.decide(goal, observation, catalog, history)
            when (decision.status) {
                "done" -> return LoopResult("done", steps, decision.detail.ifBlank { "完成" })
                "blocked" -> return LoopResult("blocked", steps, decision.detail.ifBlank { "无法继续" })
                "error" -> return LoopResult("error", steps, decision.detail.ifBlank { "决策失败" })
                "wait" -> {
                    waits++
                    if (waits >= MAX_WAITS) return LoopResult("blocked", steps, "连续等待 $MAX_WAITS 次，屏幕没有加载出来")
                    onStep("等待")
                    delay(800)
                    steps++
                    observation = ScreenObserver.observe(context)
                    continue
                }
            }
            waits = 0
            val action = decision.action ?: return LoopResult("error", steps, "决策没有可执行动作")
            if (decision.confidence < MIN_CONFIDENCE) {
                unsure++
                onStep("置信度 ${"%.2f".format(decision.confidence)} 过低，不执行「${action.label}」")
                if (unsure >= 2) return LoopResult("blocked", steps, "决策连续低置信，停止")
                delay(600)
                observation = ScreenObserver.observe(context)
                continue
            }
            unsure = 0
            val signature = signatureOf(action, observation)
            val recent = history.takeLast(2)
            if (recent.size == 2 && recent.all { it.signature == signature }) {
                return LoopResult("blocked", steps, "同一动作已重复三次，停止")
            }
            if (action.operation == "TAP" && !stillThere(action, observation)) {
                stale++
                onStep("目标已离开原位置，不点击")
                if (stale >= 2) return LoopResult("blocked", steps, "屏幕已变化，停止")
                observation = ScreenObserver.observe(context)
                continue
            }
            stale = 0
            val tool = action.tool ?: return LoopResult("error", steps, "动作缺少节点")
            onStep(action.label)
            val (ok, detail) = runNode(tool, action.params)
            steps++
            if (!ok) {
                history.add(StepNote(action.id, action.operation, action.label, changed = null, signature = signature))
                return LoopResult("error", steps, "$tool 失败: $detail")
            }
            delay(700)
            val after = ScreenObserver.observe(context)
            val changed = after.visibleText != observation.visibleText || after.packageName != observation.packageName
            history.add(StepNote(action.id, action.operation, action.label, changed = changed, signature = signature))
            if (!changed) onStep("屏幕没有变化")
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

    private fun signatureOf(action: CatalogAction, observation: ScreenObservation): String =
        "${action.operation}|${action.matchText ?: action.target.orEmpty()}|${observation.packageName}"

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
            else -> null
        }
        val ok = flag == null || ctx.variables[flag] == true
        return ok to if (ok) "ok" else "节点返回失败"
    }

    companion object {
        /** 低于这个置信度的动作不执行（Jev 会返回 confidence；DeepSeek/本地恒为 1.0）。 */
        const val MIN_CONFIDENCE = 0.3
        const val MAX_WAITS = 3
    }
}
