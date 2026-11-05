package com.ailun.habitat.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowAIClientTest {

    @Test
    fun textOnlyContentIsAPlainString() {
        // 不带图时和以前一模一样：DeepSeek 原生接口只收字符串 content
        val c = WorkflowAIClient.userContent("hello", null)
        assertTrue(c.isJsonPrimitive)
        assertEquals("hello", c.asString)
        assertTrue(WorkflowAIClient.userContent("hello", "").isJsonPrimitive)
    }

    @Test
    fun imageGoesAsOpenAiImageUrlPart() {
        val c = WorkflowAIClient.userContent("look", "QUJD").asJsonArray
        assertEquals("text", c[0].asJsonObject.get("type").asString)
        assertEquals("look", c[0].asJsonObject.get("text").asString)
        assertEquals("image_url", c[1].asJsonObject.get("type").asString)
        assertEquals("data:image/jpeg;base64,QUJD", c[1].asJsonObject.getAsJsonObject("image_url").get("url").asString)
    }
}
