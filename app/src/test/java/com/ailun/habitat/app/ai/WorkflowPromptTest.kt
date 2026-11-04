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
    fun dailyTimeExampleUsesAtNotInterval() {
        val water = NodeKnowledge.examples.first { it.contains("喝水提醒") }
        val gen = WorkflowAIGenerator.parseAndValidate(water.substring(water.indexOf('{'))).getOrThrow()
        val trigger = HabitatJson.fromJson(gen.workflowJson).triggerConfig()!!
        assertEquals(java.time.LocalTime.of(8, 0), trigger.at)
        assertTrue(prompt.contains("\"at\":\"08:00\""))
    }

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
    fun promptListsEveryNodeFromSpecs() {
        com.ailun.habitat.spec.NodeSpecs.all.forEach { spec ->
            assertTrue("提示词缺 ${spec.type}", prompt.contains("- ${spec.type}: "))
        }
        // 参数说明带进去了（如 shell 的自动转义提示）
        assertTrue("shell 的转义提示要带进提示词", prompt.contains("不要再给它加引号"))
        println("PROMPT_LENGTH ${prompt.length}")
        assertTrue("提示词太长：${prompt.length}", prompt.length < 32_000)
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
