package com.ailun.habitat

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class HabitatExecutorTest {

    private val visited: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** 空模块集合 → 只含测试注册的 handler，不触碰任何 android 实现。 */
    private val factory = NodeHandlerFactory(modules = emptySet()).apply {
        register("REC", handler { node, _ -> visited += node.id!!; node.next })
        register("SLOW", handler { node, _ ->
            delay((node.params?.get("ms") as Number).toLong())
            visited += node.id!!
            node.next
        })
        register("BRANCH", handler { node, ctx ->
            visited += node.id!!
            node.branches?.get(ctx.getVariable("flag").toString())
        })
        register("THROW", handler { node, _ -> visited += node.id!!; throw IllegalStateException("boom") })
        register("THROW_ERROR", handler { node, _ -> visited += node.id!!; throw LinkageError("linkage") })
        register("SET_OK", handler { node, ctx -> visited += node.id!!; ctx.variables["x_success"] = false; node.next })
    }

    private fun handler(block: suspend (WorkflowNode, WorkflowContext) -> String?) = object : INodeHandler {
        override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? = block(node, context)
    }

    private fun run(json: String, vars: Map<String, Any> = emptyMap()): WorkflowContext {
        val ctx = WorkflowContext.detached()
        vars.forEach { (k, v) -> ctx.variables[k] = v }
        runBlocking {
            withTimeout(10_000) { HabitatExecutor(factory).execute(HabitatJson.fromJson(json), ctx).join() }
        }
        return ctx
    }

    @Test
    fun followsNext() {
        run("""{"start_node_id":"a","nodes":{
            "a":{"id":"a","type":"REC","next":"b"},
            "b":{"id":"b","type":"REC","next":"c"},
            "c":{"id":"c","type":"REC"}}}""")
        assertEquals(listOf("a", "b", "c"), visited)
    }

    @Test
    fun followsBranches() {
        val json = """{"start_node_id":"s","nodes":{
            "s":{"id":"s","type":"BRANCH","branches":{"true":"t","false":"f"}},
            "t":{"id":"t","type":"REC"},
            "f":{"id":"f","type":"REC"}}}"""
        run(json, mapOf("flag" to true))
        assertEquals(listOf("s", "t"), visited)
        visited.clear()
        run(json, mapOf("flag" to false))
        assertEquals(listOf("s", "f"), visited)
    }

    @Test
    fun joinWaitsForAllBranchesAcrossConsecutiveAndNestedForks() {
        run("""{"start_node_id":"f1","nodes":{
            "f1":{"id":"f1","type":"ACTION_FORK","params":{"targets":["slow1"]},"next":"f2"},
            "f2":{"id":"f2","type":"ACTION_FORK","params":{"targets":"[\"inner_fork\"]"},"next":"join"},
            "slow1":{"id":"slow1","type":"SLOW","params":{"ms":150}},
            "inner_fork":{"id":"inner_fork","type":"ACTION_FORK","params":{"targets":"slow2, slow3"},"next":"inner_join"},
            "slow2":{"id":"slow2","type":"SLOW","params":{"ms":200}},
            "slow3":{"id":"slow3","type":"SLOW","params":{"ms":50}},
            "inner_join":{"id":"inner_join","type":"REC"},
            "join":{"id":"join","type":"ACTION_JOIN","next":"after"},
            "after":{"id":"after","type":"REC"}}}""")
        // 旧实现：f2 覆盖 f1 的 forkJobs、嵌套 FORK 覆盖父级 → join 不等 slow1
        assertEquals("after", visited.last())
        assertTrue(visited.containsAll(listOf("slow1", "slow2", "slow3", "inner_join")))
        // inner_fork 分支未显式 JOIN：分支结束时隐式等待 slow2/slow3，外层 JOIN 因此也等到它们
        assertTrue(visited.indexOf("slow2") < visited.indexOf("after"))
    }

    @Test
    fun innerJoinWaitsOnlyForItsOwnForks() {
        run("""{"start_node_id":"f","nodes":{
            "f":{"id":"f","type":"ACTION_FORK","params":{"targets":["b1"]},"next":"j"},
            "b1":{"id":"b1","type":"ACTION_FORK","params":{"targets":["s"]},"next":"bj"},
            "s":{"id":"s","type":"SLOW","params":{"ms":100}},
            "bj":{"id":"bj","type":"ACTION_JOIN","next":"b_after"},
            "b_after":{"id":"b_after","type":"REC"},
            "j":{"id":"j","type":"ACTION_JOIN","next":"end"},
            "end":{"id":"end","type":"REC"}}}""")
        assertEquals(listOf("s", "b_after", "end"), visited)
    }

    @Test
    fun unjoinedForksAreAwaitedBeforeFinish() {
        run("""{"start_node_id":"f","nodes":{
            "f":{"id":"f","type":"ACTION_FORK","params":{"targets":["s"]},"next":"a"},
            "s":{"id":"s","type":"SLOW","params":{"ms":150}},
            "a":{"id":"a","type":"REC"}}}""")
        assertEquals(listOf("a", "s"), visited)
    }

    @Test
    fun onErrorSkipContinuesAndRecordsError() {
        val ctx = run("""{"start_node_id":"a","nodes":{
            "a":{"id":"a","type":"THROW","next":"b"},
            "b":{"id":"b","type":"REC"}}}""")
        assertEquals(listOf("a", "b"), visited)
        assertNull(ctx.getVariable("last_error")) // b 成功后清空
        assertEquals("boom", ctx.getVariable("last_error_any"))
        assertEquals(1, ctx.getVariable("run_errors"))
    }

    @Test
    fun onErrorStopTerminatesWholeTree() {
        val ctx = run("""{"start_node_id":"f","nodes":{
            "f":{"id":"f","type":"ACTION_FORK","params":{"targets":["s"]},"next":"a"},
            "s":{"id":"s","type":"SLOW","params":{"ms":100},"next":"s_after"},
            "s_after":{"id":"s_after","type":"REC"},
            "a":{"id":"a","type":"THROW","params":{"on_error":"stop"},"next":"b"},
            "b":{"id":"b","type":"REC"}}}""")
        assertTrue(ctx.stopRequested)
        assertFalse("b" in visited)
        assertFalse("s_after" in visited)
        assertEquals("boom", ctx.getVariable("last_error_any"))
    }

    @Test
    fun requireFailureCountsAsNodeFailure() {
        val ctx = run("""{"start_node_id":"a","nodes":{
            "a":{"id":"a","type":"SET_OK","params":{"require":"x_success","on_error":"stop"},"next":"b"},
            "b":{"id":"b","type":"REC"}}}""")
        assertEquals(listOf("a"), visited)
        assertTrue(ctx.getVariable("last_error").toString().contains("x_success"))
    }

    @Test
    fun handlerErrorDoesNotCrash() {
        val ctx = run("""{"start_node_id":"a","nodes":{
            "a":{"id":"a","type":"THROW_ERROR","next":"b"},
            "b":{"id":"b","type":"REC"}}}""")
        assertEquals(listOf("a", "b"), visited)
        assertEquals("linkage", ctx.getVariable("last_error_any"))
    }

    @Test
    fun nodeTimeoutAndZeroMeansUnlimited() {
        val ctx = run("""{"start_node_id":"a","nodes":{
            "a":{"id":"a","type":"SLOW","params":{"ms":400,"node_timeout_ms":100},"next":"b"},
            "b":{"id":"b","type":"SLOW","params":{"ms":150,"node_timeout_ms":0}}}}""")
        assertEquals(listOf("b"), visited)
        assertTrue(ctx.getVariable("last_error_any").toString().contains("超时"))
        assertNull(ctx.getVariable("last_error"))
    }

    @Test
    fun cancelStopsForkBranches() {
        val ctx = WorkflowContext.detached()
        val graph = HabitatJson.fromJson("""{"start_node_id":"f","nodes":{
            "f":{"id":"f","type":"ACTION_FORK","params":{"targets":["s"]},"next":"j"},
            "s":{"id":"s","type":"SLOW","params":{"ms":5000}},
            "j":{"id":"j","type":"ACTION_JOIN","next":"end"},
            "end":{"id":"end","type":"REC"}}}""")
        runBlocking {
            val job = HabitatExecutor(factory).execute(graph, ctx)
            delay(100)
            job.cancel()
            withTimeout(2_000) { job.join() }
        }
        assertTrue(visited.isEmpty())
    }
}
