package com.ailun.habitat.selftest

import android.content.Context
import android.util.Log
import com.ailun.habitat.HabitatExecutor
import com.ailun.habitat.HabitatJson
import com.ailun.habitat.NodeHandlerFactory
import com.ailun.habitat.WorkflowContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 节点自测用例。
 *
 * @param workflowJson 测试工作流 JSON。约定以 "done" 节点（ACTION_SET_VARIABLE 写
 *                     wf_completed=true）结尾，verify 断言通常同时检查 wf_completed
 *                     与节点特定的输出变量。
 * @param verify 对执行完成后的变量快照做断言；返回 true = PASS。
 * @param description 用例期望说明（PASS/FAIL 时展示）。
 * @param requiresAccessibility 为 true 时，无障碍服务未连接则标记 SKIP。
 * @param skipReason 非空时该用例固定标记 SKIP（如需要真实文件/通话的节点，留待真机验收）。
 * @param timeoutMs 单条用例超时（毫秒）。
 */
data class SelfTestCase(
    val name: String,
    val workflowJson: String,
    val verify: (Map<String, Any?>) -> Boolean,
    val description: String = "",
    val requiresAccessibility: Boolean = false,
    val skipReason: String? = null,
    val timeoutMs: Long = 30_000L,
)

/** 单条用例结果。 */
data class SelfTestResult(
    val name: String,
    val pass: Boolean,
    val skipped: Boolean = false,
    val detail: String = "",
    val durationMs: Long = 0L,
    val error: String? = null,
    val logs: List<String> = emptyList(),
) {
    /** PASS / FAIL / SKIP */
    val status: String
        get() = when {
            skipped -> "SKIP"
            pass -> "PASS"
            else -> "FAIL"
        }
}

/**
 * 阶段 A · 自跑测试架核心。
 *
 * 顺序执行一组测试工作流，每条用例独立计时、独立捕获异常：
 * - JSON 解析失败 / 执行异常 → 该用例 FAIL（error 记录原因）
 * - 超时（withTimeoutOrNull）→ FAIL（引擎协程独立 scope，会被自己的步数上限收住）
 * - 无障碍未连接且用例 requiresAccessibility → SKIP
 *
 * 执行前会向变量空间注入 filesDir / cacheDir，供测试工作流插值
 * （如 ACTION_FILE_OPERATION 的 "path": "${filesDir}/selftest.txt"）。
 */
object NodeSelfTestRunner {

    private const val TAG = "HabitatSelfTest"

    /** 顺序执行全部用例，返回逐条结果。 */
    suspend fun run(
        context: Context,
        factory: NodeHandlerFactory,
        cases: List<SelfTestCase>,
        accessibilityConnected: Boolean = false,
        onLog: (String) -> Unit = {},
    ): List<SelfTestResult> = withContext(Dispatchers.Default) {
        cases.map { runCase(context, factory, it, accessibilityConnected, onLog) }
    }

    private suspend fun runCase(
        context: Context,
        factory: NodeHandlerFactory,
        case: SelfTestCase,
        accessibilityConnected: Boolean,
        onLog: (String) -> Unit,
    ): SelfTestResult {
        val start = System.currentTimeMillis()

        if (case.requiresAccessibility && !accessibilityConnected) {
            return SelfTestResult(
                name = case.name,
                pass = false,
                skipped = true,
                detail = "需要无障碍服务（未连接），跳过",
                durationMs = System.currentTimeMillis() - start,
            )
        }

        if (case.skipReason != null) {
            return SelfTestResult(
                name = case.name,
                pass = false,
                skipped = true,
                detail = case.skipReason,
                durationMs = System.currentTimeMillis() - start,
            )
        }

        val logs = mutableListOf<String>()
        try {
            val graph = HabitatJson.fromJson(case.workflowJson)
            val ctx = WorkflowContext(context)
            // 供测试工作流插值使用（"{filesDir}/..."、"{cacheDir}/..."）
            ctx.variables["filesDir"] = context.filesDir.absolutePath
            ctx.variables["cacheDir"] = context.cacheDir.absolutePath
            // 测试用时间戳（WAIT_UNTIL_TIME 用例需要精确整数毫秒，避免 Double 科学计数法）
            ctx.variables["selftest_now_ms"] = System.currentTimeMillis()
            ctx.variables["selftest_target_ms"] = System.currentTimeMillis() + 3000L
            ctx.onLog = { line ->
                logs.add(line)
                onLog(line)
            }

            val finished = withTimeoutOrNull(case.timeoutMs) {
                HabitatExecutor(factory).execute(graph, ctx).join()
                true
            } ?: false

            val duration = System.currentTimeMillis() - start
            if (!finished) {
                Log.w(TAG, "${case.name}: TIMEOUT after ${case.timeoutMs}ms")
                return SelfTestResult(
                    name = case.name,
                    pass = false,
                    detail = "超时（${case.timeoutMs}ms）",
                    durationMs = duration,
                    logs = logs.takeLast(50),
                )
            }

            // verify 抛异常要记进 error（诊断可见），且不吞 CancellationException
            val pass = try {
                case.verify(ctx.variables)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "${case.name}: verify failed: ${e.message}")
                return SelfTestResult(
                    name = case.name,
                    pass = false,
                    detail = case.description.ifEmpty { "断言未通过" },
                    durationMs = duration,
                    error = e.message ?: e.javaClass.simpleName,
                    logs = logs.takeLast(50),
                )
            }
            val detail = if (pass) case.description.ifEmpty { "OK" } else case.description.ifEmpty { "断言未通过" }
            return SelfTestResult(
                name = case.name,
                pass = pass,
                detail = detail,
                durationMs = duration,
                logs = logs.takeLast(50),
            )
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - start
            Log.e(TAG, "${case.name}: EXCEPTION ${e.message}", e)
            return SelfTestResult(
                name = case.name,
                pass = false,
                detail = "执行异常",
                durationMs = duration,
                error = e.message ?: e.javaClass.simpleName,
                logs = logs.takeLast(50),
            )
        }
    }
}
