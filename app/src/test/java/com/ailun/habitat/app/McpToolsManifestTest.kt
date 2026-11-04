package com.ailun.habitat.app

import com.ailun.habitat.NodeHandlerFactory
import com.ailun.habitat.app.bridge.APP_NODE_TYPES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `scripts/habitat_mcp_tools.json` 是 stdio 桥连不上手机时回答 `tools/list` 用的离线清单，
 * 和手机端默认设置下的 `tools/list` 同一来源；过期就失败。`UPDATE_DOCS=1` 时重写。
 */
class McpToolsManifestTest {

    private fun defaultTools() = McpTools.build(
        NodeHandlerFactory().availableTypes() + APP_NODE_TYPES,
        AgentService.REMOTE_DANGEROUS_TOOLS,
        allowDangerous = false,
    )

    @Test
    fun manifestIsUpToDate() {
        val root = listOf(File(".."), File(".")).first { File(it, "settings.gradle.kts").isFile }
        val file = File(root, "scripts/habitat_mcp_tools.json")
        val expected = McpTools.manifest(com.ailun.habitat.BuildConfig.VERSION_NAME, defaultTools())
        if (System.getenv("UPDATE_DOCS") == "1") {
            file.writeText(expected)
            return
        }
        assertEquals(
            "scripts/habitat_mcp_tools.json 过期：运行 UPDATE_DOCS=1 ./gradlew :app:testStandardDebugUnitTest --tests '*McpToolsManifestTest*'",
            expected,
            if (file.isFile) file.readText().replace("\r\n", "\n") else "",
        )
    }

    @Test
    fun defaultListHidesDangerousNodes() {
        val names = defaultTools().map { it.asJsonObject.get("name").asString }.toSet()
        assertTrue("screen" in names)
        assertTrue(NodeHandlerFactory.ACTION_MINI_SCREEN in names)
        assertFalse(NodeHandlerFactory.ACTION_SHELL in names)
        AgentService.REMOTE_DANGEROUS_TOOLS.forEach { assertFalse(it, it in names) }
    }

    @Test
    fun builtinsDescribeArgsAndBehavior() {
        val byName = defaultTools().associateBy { it.asJsonObject.get("name").asString }.mapValues { it.value.asJsonObject }
        val builtins = listOf("screen", "debug_screen", "ping", "mini_screen_status", "agent_start", "agent_stop", "agent_status")
        builtins.forEach { name ->
            val tool = byName.getValue(name)
            assertTrue("$name 有注解", tool.has("annotations"))
            assertTrue("$name 描述写清楚", tool.get("description").asString.length > 60)
        }
        val start = byName.getValue("agent_start")
        assertFalse(start.getAsJsonObject("annotations").get("readOnlyHint").asBoolean)
        val schema = start.getAsJsonObject("inputSchema")
        assertEquals(setOf("goal", "provider", "max_steps", "planner"), schema.getAsJsonObject("properties").keySet())
        assertEquals("goal", schema.getAsJsonArray("required").single().asString)
        assertTrue(byName.getValue("screen").getAsJsonObject("annotations").get("readOnlyHint").asBoolean)
    }
}
