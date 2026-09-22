package com.ailun.habitat.app.agent

import android.content.Context
import com.ailun.habitat.NodeHandlerFactory
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode
import com.ailun.habitat.app.bridge.AppAccessibilityProvider
import com.ailun.habitat.app.bridge.ShizukuShellExecutor
import com.ailun.habitat.app.bridge.applyAppHandlers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class LoopResult(val status: String, val steps: Int, val detail: String)

/**
 * 读屏 → 决策（只选题）→ 用 Habitat 节点执行 → 再读屏。
 * 同一动作连续三次，或点击目标已经不在原位置，就停，不自动重放。
 */
class AgentLoop(
    private val context: Context,
    private val provider: DecisionProvider,
    private val maxSteps: Int = 12,
) {
    @Volatile var stopRequested: Boolean = false

    suspend fun run(goal: String, onStep: (String) -> Unit): LoopResult {
        if (goal.isBlank()) return LoopResult("error", 0, "目标为空")
        val apps = ScreenObserver.launcherApps(context)
        val history = mutableListOf<StepNote>()
        var stale = 0
        var steps = 0
        while (steps < maxSteps && !stopRequested) {
            val observation = ScreenObserver.observe(context)
            if (observation.source == "none" && observation.elements.isEmpty()) {
                return LoopResult("error", steps, "无障碍未连接，读不到屏幕")
            }
            val catalog = Catalogs.build(goal, observation, apps)
            onStep("读屏 ${observation.packageName} / ${observation.source} / ${observation.elements.size} 个控件")
            val decision = provider.decide(goal, observation, catalog, history)
            when (decision.status) {
                "done" -> return LoopResult("done", steps, decision.detail.ifBlank { "完成" })
                "blocked" -> return LoopResult("blocked", steps, decision.detail.ifBlank { "无法继续" })
                "error" -> return LoopResult("error", steps, decision.detail.ifBlank { "决策失败" })
                "wait" -> {
                    onStep("等待")
                    delay(800)
                    steps++
                    continue
                }
            }
            val action = decision.action ?: return LoopResult("error", steps, "决策没有可执行动作")
            if (history.takeLast(2).size == 2 && history.takeLast(2).all { it.actionId == action.id }) {
                return LoopResult("blocked", steps, "同一动作已重复，停止")
            }
            if (action.operation == "TAP" && !stillThere(action)) {
                stale++
                onStep("目标已离开原位置，不点击")
                if (stale >= 2) return LoopResult("blocked", steps, "屏幕已变化，停止")
                continue
            }
            stale = 0
            val tool = action.tool ?: return LoopResult("error", steps, "动作缺少节点")
            onStep(action.label)
            val (ok, detail) = runNode(tool, action.params)
            history.add(StepNote(action.id, action.operation, action.label))
            steps++
            if (!ok) return LoopResult("error", steps, "$tool 失败: $detail")
            delay(700)
        }
        if (stopRequested) return LoopResult("stopped", steps, "已停止")
        return LoopResult("blocked", steps, "达到步数上限 $maxSteps")
    }

    private suspend fun stillThere(action: CatalogAction): Boolean {
        val text = action.matchText ?: return true
        val x = action.centerX ?: return true
        val y = action.centerY ?: return true
        val again = ScreenObserver.observe(context)
        return again.elements.any {
            it.text == text && kotlin.math.abs(it.centerX - x) <= 120 && kotlin.math.abs(it.centerY - y) <= 120
        }
    }

    private suspend fun runNode(tool: String, params: Map<String, Any>): Pair<Boolean, String> {
        val factory = NodeHandlerFactory(AppAccessibilityProvider, ShizukuShellExecutor(context))
            .apply { applyAppHandlers(context) }
        val handler = factory.get(tool) ?: return false to "未知节点"
        val node = WorkflowNode().apply {
            id = "agent_loop"
            type = tool
            this.params = params
        }
        val ctx = WorkflowContext(context)
        val result = withContext(Dispatchers.Default) {
            withTimeoutOrNull(30_000) { runCatching { handler.handle(node, ctx) } }
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
}
