package com.ailun.habitat.app.ai

import com.ailun.habitat.HabitatJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** AI 生成工作流的提示词：触发器写法与变量名要讲清楚，示例本身要能解析、带触发器。 */
class WorkflowPromptTest {

    private val prompt = WorkflowAIGenerator.buildSystemPrompt()

    @Test
    fun promptDocumentsTriggerVariables() {
        assertTrue(prompt.contains("\"trigger\":{\"type\":\"notification\"}"))
        assertTrue(prompt.contains("\${trigger_text}"))
        assertTrue(prompt.contains("\${trigger_body}"))
        assertTrue("要明确告诉模型不要编变量名", prompt.contains("不是 notification_text"))
        assertTrue(prompt.contains("ACTION_MINI_SCREEN"))
        assertTrue(prompt.contains("ACTION_WORKSPACE"))
    }

    @Test
    fun examplesParseAndTriggeredExampleHasTrigger() {
        NodeKnowledge.examples.forEach { ex ->
            val json = ex.substring(ex.indexOf('{'))
            val parsed = WorkflowAIGenerator.parseAndValidate(json)
            assertTrue("示例解析失败：${ex.take(40)}", parsed.isSuccess)
        }
        val sms = NodeKnowledge.examples.first { it.contains("验证码提醒") }
        val gen = WorkflowAIGenerator.parseAndValidate(sms.substring(sms.indexOf('{'))).getOrThrow()
        val graph = HabitatJson.fromJson(gen.workflowJson)
        assertEquals("sms", graph.triggerConfig()?.type)
        assertNotNull(graph.nodes?.get("s2"))
        assertFalse(gen.workflowJson.contains("notification_text"))
    }
}
