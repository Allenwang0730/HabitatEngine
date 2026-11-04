package com.ailun.habitat.spec

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/** 从 [NodeSpec] 生成下游产物：MCP inputSchema、AI 提示词一行、docs/NODES.md。 */
object NodeSpecRender {

    /** 提示词里每个参数说明的最长字数（全部节点都列，控制总长）。 */
    private const val PROMPT_DESC_MAX = 60

    /** MCP `tools/list` 的 inputSchema（JSON Schema）。允许额外参数：规格漏写的参数不至于被客户端拦掉。 */
    fun jsonSchema(spec: NodeSpec): JsonObject = JsonObject().apply {
        addProperty("type", "object")
        val props = JsonObject()
        (spec.params + CommonParams.all).forEach { p ->
            props.add(p.name, JsonObject().apply {
                when {
                    p.type == ParamType.STRING_OR_NUMBER -> add("type", JsonArray().apply { add("string"); add("number") })
                    p.type.jsonType.isNotEmpty() -> addProperty("type", p.type.jsonType)
                }
                addProperty("description", describe(p))
                if (p.enum.isNotEmpty()) add("enum", JsonArray().apply { p.enum.forEach(::add) })
            })
        }
        add("properties", props)
        val required = spec.params.filter { it.required }
        if (required.isNotEmpty()) add("required", JsonArray().apply { required.forEach { add(it.name) } })
        addProperty("additionalProperties", true)
    }

    /** MCP 工具说明：用途 + 前置条件 + 输出变量。 */
    fun toolDescription(spec: NodeSpec): String = buildString {
        append(spec.summary)
        if (spec.requires.isNotBlank()) append("（").append(spec.requires).append("）")
        if (spec.outputs.isNotEmpty()) append("。输出：").append(spec.outputs.joinToString(", "))
        if (spec.notes.isNotBlank()) append("。").append(spec.notes)
        append("。字符串参数支持 \${var} 插值；结果以变量快照 JSON 返回。")
    }

    /** AI 生成工作流提示词里的一行：`- TYPE: a*（说明）, b=x/y（说明） —— 用途（前置）→ 输出。注意事项`。 */
    fun promptLine(spec: NodeSpec): String = buildString {
        append("- ").append(spec.type).append(": ")
        append(
            spec.params.joinToString("; ") { p ->
                val name = p.name + if (p.required) "*" else ""
                val values = if (p.enum.isNotEmpty()) "=" + p.enum.joinToString("/") else ""
                // output_var 的说明都是"存到哪个变量"，省掉；其它参数带一句说明
                if (p.name == "output_var") "$name$values" else "$name$values（${p.description.take(PROMPT_DESC_MAX)}）"
            }.ifEmpty { "无参数" },
        )
        append(" —— ").append(spec.summary)
        if (spec.requires.isNotBlank()) append("（").append(spec.requires).append("）")
        if (spec.outputs.isNotEmpty()) append(" → ").append(spec.outputs.joinToString(","))
        if (spec.notes.isNotBlank()) append("。").append(spec.notes)
    }

    private fun describe(p: ParamSpec): String = buildString {
        append(p.description)
        p.default?.let { append("（默认 ").append(it).append("）") }
        if (p.aliases.isNotEmpty()) append("（也认：").append(p.aliases.joinToString("、")).append("）")
    }

    /** docs/NODES.md 全文（由测试生成并比对，见 NodeSpecDocTest）。 */
    fun markdown(specs: List<NodeSpec>): String = buildString {
        appendLine("# 节点参考")
        appendLine()
        appendLine("> 本文件由 `lib/src/main/java/com/ailun/habitat/spec/` 自动生成，不要手改。")
        appendLine("> 更新：改规格后运行 `UPDATE_DOCS=1 ./gradlew :lib:testDebugUnitTest --tests '*NodeSpecDocTest*'`。")
        appendLine()
        appendLine("共 ${specs.size} 种节点。所有节点都可以加通用参数：")
        CommonParams.all.forEach { appendLine("- `${it.name}`：${describe(it)}") }
        appendLine()
        appendLine("必填参数标 ★。字符串参数支持 `\${var}` 插值。")
        appendLine()
        appendLine("## 目录")
        appendLine()
        appendLine(specs.joinToString(" · ") { "[`${it.type}`](#${it.type.lowercase()})" })
        for (s in specs) {
            appendLine()
            appendLine("### ${s.type}")
            appendLine()
            appendLine(s.summary)
            if (s.requires.isNotBlank()) {
                appendLine()
                appendLine("前置：${s.requires}")
            }
            if (s.params.isNotEmpty()) {
                appendLine()
                appendLine("| 参数 | 类型 | 说明 |")
                appendLine("|---|---|---|")
                s.params.forEach { p ->
                    val type = if (p.enum.isNotEmpty()) p.enum.joinToString(" / ") else p.type.name.lowercase()
                    appendLine("| `${p.name}`${if (p.required) " ★" else ""} | ${cell(type)} | ${cell(describe(p))} |")
                }
            }
            if (s.outputs.isNotEmpty()) {
                appendLine()
                appendLine("输出：" + s.outputs.joinToString(" ") { "`$it`" })
            }
            if (s.notes.isNotBlank()) {
                appendLine()
                appendLine("> ${s.notes}")
            }
        }
    }

    private fun cell(text: String) = text.replace("|", "\\|").replace("\n", " ")
}
