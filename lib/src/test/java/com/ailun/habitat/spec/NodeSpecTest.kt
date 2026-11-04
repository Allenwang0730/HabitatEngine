package com.ailun.habitat.spec

import com.ailun.habitat.HabitatExecutor
import com.ailun.habitat.HabitatJson
import com.ailun.habitat.NodeHandlerFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 规格是 MCP 参数定义、AI 提示词、lint、文档的唯一来源，必须和代码一致：
 * - 每个注册的节点都有规格，没有多余/重复的规格；
 * - 规格里的每个参数名都真的出现在 handler 源码里（防止写错名、编造参数）。
 */
class NodeSpecTest {

    /** 宿主 :app 注册、引擎里没有 handler 的节点（见 app/.../bridge/HabitatFactoryExt.kt）。 */
    private val hostTypes = setOf(
        NodeHandlerFactory.ACTION_SEND_NOTIFICATION,
        NodeHandlerFactory.ACTION_DYNAMIC_ISLAND,
        NodeHandlerFactory.ACTION_MINI_SCREEN,
        NodeHandlerFactory.ACTION_OCR,
    )

    @Test
    fun everyNodeHasExactlyOneSpec() {
        val registered = NodeHandlerFactory(null, null).availableTypes() + hostTypes +
            setOf(HabitatExecutor.ACTION_FORK, HabitatExecutor.ACTION_JOIN)
        val specTypes = NodeSpecs.all.map { it.type }
        assertEquals("重复的规格", specTypes.size, specTypes.toSet().size)
        assertEquals("缺规格", emptySet<String>(), registered - specTypes.toSet())
        assertEquals("多出来的规格", emptySet<String>(), specTypes.toSet() - registered)
    }

    @Test
    fun specsAreWellFormed() {
        for (s in NodeSpecs.all) {
            assertTrue("${s.type} 缺用途", s.summary.isNotBlank())
            val names = s.params.flatMap { listOf(it.name) + it.aliases }
            assertEquals("${s.type} 参数名重复", names.size, names.toSet().size)
            assertFalse("${s.type} 重复声明了通用参数", names.any { it in CommonParams.names })
            s.params.forEach { p ->
                assertTrue("${s.type}.${p.name} 缺说明", p.description.isNotBlank())
                assertTrue("${s.type}.${p.name} 的可选值要小写", p.enum.all { it == it.lowercase() })
            }
        }
    }

    @Test
    fun paramNamesAppearInHandlerSource() {
        val handlers = handlerFiles()
        val missing = mutableListOf<String>()
        for (s in NodeSpecs.all) {
            if (s.type == HabitatExecutor.ACTION_FORK || s.type == HabitatExecutor.ACTION_JOIN) continue
            val files = handlers[s.type] ?: run { missing += "${s.type}: 找不到 handler 源码"; continue }
            val source = files.joinToString("\n") { it.readText() }
            for (name in s.params.flatMap { listOf(it.name) + it.aliases }) {
                if ("\"$name\"" !in source) missing += "${s.type}.$name"
            }
        }
        assertTrue("规格里的参数在 handler 源码里找不到（写错名或编造）：\n" + missing.joinToString("\n"), missing.isEmpty())
    }

    @Test
    fun jsonSchemaListsParamsAndRequired() {
        val click = NodeSpecRender.jsonSchema(NodeSpecs.get(NodeHandlerFactory.ACTION_CLICK)!!)
        assertTrue(click.getAsJsonObject("properties").has("target"))
        assertTrue(click.getAsJsonObject("properties").has("on_error"))
        assertTrue(click.getAsJsonArray("required").map { it.asString }.contains("target"))
    }

    @Test
    fun lintFlagsTyposMissingRequiredAndBadEnum() {
        val graph = HabitatJson.fromJson(
            """{"start_node_id":"a","nodes":{
              "a":{"id":"a","type":"ACTION_CLICK","params":{"taget":"确定"},"next":"b"},
              "b":{"id":"b","type":"ACTION_CLIPBOARD","params":{"action":"copy","on_error":"stop"}}}}""",
        )
        val messages = HabitatJson.lint(graph, null).map { it.message }
        assertTrue(messages.toString(), messages.any { it.contains("'taget'") && it.contains("'target'") })
        assertTrue(messages.toString(), messages.any { it.contains("缺少必填参数 'target'") })
        assertTrue(messages.toString(), messages.any { it.contains("'copy'") })
        assertFalse("通用参数不报", messages.any { it.contains("'on_error'") })
    }

    /** 节点类型 → handler 源文件（加上它直接用到的同目录辅助类，参数可能在那里读）。 */
    private fun handlerFiles(): Map<String, List<File>> {
        val root = listOf(File("."), File("lib")).first { File(it, "src/main/java/com/ailun/habitat/NodeModules.kt").isFile }
        val libDir = File(root, "src/main/java/com/ailun/habitat")
        val appDir = File(root, "../app/src/main/java/com/ailun/habitat/app")
        val all = (libDir.walkTopDown() + appDir.walkTopDown()).filter { it.extension == "kt" }.toList()
        val byClass = all.associateBy { it.nameWithoutExtension }
        fun withHelpers(file: File): List<File> {
            val text = file.readText()
            val helpers = Regex("""\b([A-Z][A-Za-z0-9]+)\b""").findAll(text).map { it.value }.toSet()
                .mapNotNull { byClass[it] }.filter { it != file && it.parentFile == file.parentFile }
            return listOf(file) + helpers
        }
        val out = mutableMapOf<String, List<File>>()
        val modules = File(libDir, "NodeModules.kt").readText()
        Regex("""NodeHandlerFactory\.([A-Z0-9_]+) to \{[^}]*-> (\w+)\(""").findAll(modules).forEach { m ->
            byClass[m.groupValues[2]]?.let { out[m.groupValues[1]] = withHelpers(it) }
        }
        val host = File(appDir, "bridge/HabitatFactoryExt.kt").readText()
        Regex("""register\(NodeHandlerFactory\.([A-Z0-9_]+), (\w+)\(""").findAll(host).forEach { m ->
            byClass[m.groupValues[2]]?.let { out[m.groupValues[1]] = withHelpers(it) }
        }
        return out
    }
}
