package com.ailun.habitat.app.ai

import android.content.Context
import android.util.Log
import com.ailun.habitat.HabitatJson
import com.google.gson.Gson
import com.google.gson.JsonObject

/** AI 生成的工作流结果。 */
data class GeneratedWorkflow(
    val name: String,
    val description: String,
    val workflowJson: String, // 已通过 HabitatJson 验证
)

/**
 * 工作流 AI 生成器：DeepSeek 生成 Habitat 工作流 JSON → 提取 → 验证。
 */
object WorkflowAIGenerator {

    private const val TAG = "WorkflowAIGen"
    private val gson = Gson()

    /**
     * 生成工作流。
     * @param request 用户最新需求
     * @param baseWorkflow 已有工作流 JSON（用于"在现有基础上修改"的多轮对话），可为 null
     * @param history 最近几轮对话（用户需求 → 生成结果描述），用于会话记忆，可为 null
     */
    suspend fun generate(
        context: Context,
        request: String,
        baseWorkflow: String? = null,
        history: List<Pair<String, String>>? = null,
    ): Result<GeneratedWorkflow> {
        val apiKey = WorkflowAIClient.getApiKey(context)
        val baseUrl = WorkflowAIClient.getBaseUrl(context)
        val systemPrompt = buildSystemPrompt()

        val userPrompt = buildString {
            if (history != null && history.isNotEmpty()) {
                append("【之前的对话】（用于会话记忆，保持连贯）\n")
                history.takeLast(3).forEach { (u, a) ->
                    append("用户: $u\n助手: $a\n")
                }
                append("\n")
            }
            if (baseWorkflow != null) {
                append("这是现有的 Habitat 工作流：\n$baseWorkflow\n\n")
                append("请根据新的需求【修改】这个工作流，输出修改后的完整工作流 JSON（不要省略任何节点）。\n")
            }
            append("用户需求：$request")
        }

        val llmResult = WorkflowAIClient.chat(apiKey, systemPrompt, userPrompt, baseUrl)
        val raw = llmResult.getOrElse {
            return Result.failure(it)
        }

        return parseAndValidate(raw)
    }

    /** 从 LLM 响应解析并验证工作流。 */
    fun parseAndValidate(raw: String): Result<GeneratedWorkflow> {
        val jsonText = extractJson(raw)
            ?: return Result.failure(IllegalArgumentException("响应中未找到 JSON 对象"))

        val obj = runCatching { gson.fromJson(jsonText, JsonObject::class.java) }.getOrNull()
            ?: return Result.failure(IllegalArgumentException("JSON 解析失败"))

        // 支持包装格式 {name, description, workflow} 和裸工作流 {start_node_id, nodes}
        val name: String
        val description: String
        val workflowJson: String

        if (obj.has("workflow") && obj.get("workflow").isJsonObject) {
            name = obj.get("name")?.asString?.takeIf { it.isNotBlank() } ?: "AI 工作流"
            description = obj.get("description")?.asString?.takeIf { it.isNotBlank() } ?: ""
            workflowJson = obj.get("workflow").asJsonObject.toString()
        } else if (obj.has("start_node_id")) {
            name = obj.get("name")?.asString?.takeIf { it.isNotBlank() } ?: "AI 工作流"
            description = obj.get("description")?.asString?.takeIf { it.isNotBlank() } ?: ""
            workflowJson = obj.toString()
        } else {
            return Result.failure(IllegalArgumentException("JSON 不是有效的工作流（缺少 start_node_id / workflow）"))
        }

        // 用引擎解析器验证（会做 start_node_id/nodes 校验）
        runCatching { HabitatJson.fromJson(workflowJson) }.getOrElse {
            return Result.failure(IllegalArgumentException("工作流校验失败: ${it.message}"))
        }

        return Result.success(GeneratedWorkflow(name, description, workflowJson))
    }

    /** 提取第一个平衡的 JSON 对象（{ ... }）。 */
    private fun extractJson(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var esc = false
        for (i in start until text.length) {
            val ch = text[i]
            if (inString) {
                if (esc) esc = false
                else if (ch == '\\') esc = true
                else if (ch == '"') inString = false
            } else {
                when (ch) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return text.substring(start, i + 1)
                    }
                }
            }
        }
        return null
    }

    /** 系统提示词：从 NodeKnowledge（节点百科）动态生成——加新节点只改 NodeKnowledge 一处。 */
    private fun buildSystemPrompt(): String {
        val sb = StringBuilder()
        sb.append("你是 Habitat 安卓自动化引擎的工作流生成器。根据用户需求，生成一个 Habitat 工作流 JSON。\n\n")
        sb.append("【工作流格式】\n")
        sb.append("{\"name\":\"名称\",\"description\":\"说明\",\"workflow\":{\"start_node_id\":\"起始id\",\"nodes\":{\"节点id\":{\"id\":\"节点id\",\"type\":\"节点类型\",\"params\":{...},\"next\":\"下一id\"},...}}}\n")
        sb.append("规则：start_node_id 必须指向已存在节点；节点有 id/type；params 可选；next 可选；变量用 \${变量名} 引用；节点 id 唯一；只输出这一个 JSON 对象。可选顶层 trigger（notification|timer|clipboard|sms|key）。\n\n")
        sb.append("【可用节点类型与参数】（只用这些 type；优先用直接 API 节点，避免 UI 点击模拟）\n")
        NodeKnowledge.nodes.forEach { n ->
            val beforePart = if (n.before.isNotBlank()) "（${n.before}）" else ""
            sb.append("- ${n.type}: ${n.params} —— ${n.usage}${beforePart}\n")
        }
        sb.append("\n【安卓操作指南】\n")
        val pkgStr = NodeKnowledge.packageHints.entries.joinToString(" / ") { "${it.key}=${it.value}" }
        sb.append("- 常见包名：$pkgStr\n")
        NodeKnowledge.rules.forEach { r -> sb.append("- $r\n") }
        sb.append("\n【示例】\n")
        NodeKnowledge.examples.forEachIndexed { i, e -> sb.append("示例${i + 1}：$e\n") }
        sb.append("\n只输出 JSON 对象。\n")
        return sb.toString()
    }
}
