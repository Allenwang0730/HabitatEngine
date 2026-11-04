package com.ailun.habitat.spec

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** docs/NODES.md 由规格生成；规格改了文档没更新就失败。`UPDATE_DOCS=1` 时重写文档。 */
class NodeSpecDocTest {

    @Test
    fun nodesDocIsUpToDate() {
        val root = listOf(File(".."), File(".")).first { File(it, "settings.gradle.kts").isFile }
        val doc = File(root, "docs/NODES.md")
        val expected = NodeSpecRender.markdown(NodeSpecs.all)
        if (System.getenv("UPDATE_DOCS") == "1") {
            doc.writeText(expected)
            return
        }
        assertEquals(
            "docs/NODES.md 过期：运行 UPDATE_DOCS=1 ./gradlew :lib:testDebugUnitTest --tests '*NodeSpecDocTest*'",
            expected,
            if (doc.isFile) doc.readText().replace("\r\n", "\n") else "",
        )
    }
}
