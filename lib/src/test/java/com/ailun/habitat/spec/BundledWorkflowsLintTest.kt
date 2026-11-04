package com.ailun.habitat.spec

import com.ailun.habitat.HabitatJson
import com.ailun.habitat.WorkflowRepository
import org.junit.Assert.assertEquals
import org.junit.Test

/** 自带的示例工作流按规格检查不应有参数问题：有的话不是示例写错就是规格漏了参数。 */
class BundledWorkflowsLintTest {

    @Test
    fun defaultWorkflowsHaveNoParamIssues() {
        val problems = WorkflowRepository.defaultWorkflows().flatMap { wf ->
            HabitatJson.lint(HabitatJson.fromJson(wf.jsonContent), null)
                .filter { "参数" in it.message || "可选值" in it.message }
                .map { "${wf.name} / ${it.nodeId}: ${it.message}" }
        }
        assertEquals(problems.joinToString("\n"), 0, problems.size)
    }
}
