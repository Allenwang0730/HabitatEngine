package com.ailun.habitat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowLintTest {

    private fun lint(json: String, known: Set<String>? = null) =
        HabitatJson.lint(HabitatJson.fromJson(json), known)

    private fun List<LintIssue>.of(severity: String) = filter { it.severity == severity }

    @Test
    fun cleanGraphHasNoIssues() {
        val issues = lint(
            """{"start_node_id":"a","nodes":{
                "a":{"id":"a","type":"CONDITION_SWITCH","branches":{"true":"b","false":null}},
                "b":{"id":"b","type":"ACTION_LOG"}}}""",
            setOf("CONDITION_SWITCH", "ACTION_LOG"),
        )
        assertTrue(issues.toString(), issues.isEmpty())
    }

    @Test
    fun danglingNextAndBranchAreErrors() {
        val issues = lint(
            """{"start_node_id":"a","nodes":{
                "a":{"id":"a","type":"T","next":"nope"},
                "b":{"id":"b","type":"T","branches":{"true":"gone"}}}}"""
        )
        val errors = issues.of(LintIssue.ERROR)
        assertTrue(errors.any { it.nodeId == "a" && "nope" in it.message })
        assertTrue(errors.any { it.nodeId == "b" && "gone" in it.message })
    }

    @Test
    fun unknownTypesOnlyWhenKnownTypesGiven() {
        val json = """{"start_node_id":"f","nodes":{
            "f":{"id":"f","type":"ACTION_FORK","params":{"targets":["x"]},"next":"j"},
            "x":{"id":"x","type":"MYSTERY"},
            "j":{"id":"j","type":"ACTION_JOIN"}}}"""
        assertTrue(lint(json).of(LintIssue.ERROR).isEmpty())
        val errors = lint(json, setOf("ACTION_LOG")).of(LintIssue.ERROR)
        assertEquals(1, errors.size)
        assertEquals("x", errors[0].nodeId)
    }

    @Test
    fun unreachableIsWarningAndParamRefsCountAsEdges() {
        val issues = lint(
            """{"start_node_id":"f","nodes":{
                "f":{"id":"f","type":"ACTION_FORK","params":{"targets":"[\"l\", \"r\"]"},"next":"end"},
                "l":{"id":"l","type":"ACTION_LOOP","params":{"body_node":"body"}},
                "body":{"id":"body","type":"T","next":"l"},
                "r":{"id":"r","type":"ACTION_RETRY","params":{"target":"work"}},
                "work":{"id":"work","type":"T","next":"r"},
                "end":{"id":"end","type":"T"},
                "island":{"id":"island","type":"T"}}}"""
        )
        assertEquals(listOf("island"), issues.of(LintIssue.WARNING).map { it.nodeId })
        assertTrue(issues.of(LintIssue.ERROR).isEmpty())
    }

    @Test
    fun missingForkTargetIsError() {
        val errors = lint(
            """{"start_node_id":"f","nodes":{
                "f":{"id":"f","type":"ACTION_FORK","params":{"targets":"a, ghost"}},
                "a":{"id":"a","type":"T"}}}"""
        ).of(LintIssue.ERROR)
        assertTrue(errors.any { it.nodeId == "f" && "ghost" in it.message })
    }

    @Test
    fun keyIdMismatchIsWarning() {
        val warnings = lint(
            """{"start_node_id":"a","nodes":{"a":{"id":"a","type":"T","next":"k"},"k":{"id":"other","type":"T"}}}"""
        ).of(LintIssue.WARNING)
        assertTrue(warnings.any { it.nodeId == "k" && "other" in it.message })
    }

    @Test
    fun dynamicRefsAreSkipped() {
        val issues = lint(
            """{"start_node_id":"a","nodes":{"a":{"id":"a","type":"T","next":"${'$'}{dest}"}}}"""
        )
        assertTrue(issues.toString(), issues.isEmpty())
    }

    @Test
    fun lintNeverThrowsOnBrokenGraph() {
        val g = WorkflowGraph()
        assertEquals(LintIssue.ERROR, HabitatJson.lint(g).single().severity)
        g.nodes = mapOf("a" to WorkflowNode())
        val issues = HabitatJson.lint(g)
        assertTrue(issues.any { it.nodeId == null && "start_node_id" in it.message })
    }
}
