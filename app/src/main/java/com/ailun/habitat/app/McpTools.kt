package com.ailun.habitat.app

import com.ailun.habitat.ALL_NODE_MODULES
import com.ailun.habitat.spec.NodeSpecRender
import com.ailun.habitat.spec.NodeSpecs
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * MCP `tools/list` 清单的纯逻辑：手机端 [AgentService] 实时生成；同一份结果也导出成
 * `scripts/habitat_mcp_tools.json`，stdio 桥连不上手机时用它回答 `tools/list`（见 McpToolsManifestTest）。
 */
object McpTools {

    const val SERVER_NAME = "habitat-agent"

    /** 节点 + 内置 screen/ping/agent_*。[allowDangerous] 为 false 时不列出 [dangerous] 里的节点。 */
    fun build(types: Collection<String>, dangerous: Set<String>, allowDangerous: Boolean): JsonArray {
        val arr = JsonArray()
        arr.add(schemaless("screen", "获取当前屏幕无障碍树文本 + 前台应用包名（AI 的眼睛）"))
        arr.add(schemaless("debug_screen", "排查用：当前观测快照 + 候选动作清单。arguments: goal（可空，默认「打开设置」）"))
        arr.add(schemaless("ping", "连通性测试"))
        arr.add(schemaless("mini_screen_status", "悬浮小屏当前内容（是否显示、标题、来源、文字、进度），只读"))
        arr.add(schemaless("agent_start", "启动自主循环。arguments: goal（必填）, provider（jev/deepseek/local，可空）, max_steps（1-40，默认 20，可空）, planner（true/false，可空，覆盖界面开关）。配了 DeepSeek key 且开启规划时，先由 DeepSeek 拆步骤、最后复核"))
        arr.add(schemaless("agent_stop", "停止自主循环"))
        arr.add(schemaless("agent_status", "自主循环状态与最近步骤"))
        types.sorted().forEach { type ->
            if (!allowDangerous && type in dangerous) return@forEach
            // 参数定义来自节点规格（lib/.../spec），外部 AI 不用猜参数名
            val spec = NodeSpecs.get(type)
            if (spec != null) {
                arr.add(JsonObject().apply {
                    addProperty("name", type)
                    addProperty("description", NodeSpecRender.toolDescription(spec))
                    add("inputSchema", NodeSpecRender.jsonSchema(spec))
                })
            } else {
                val module = ALL_NODE_MODULES.entries.firstOrNull { (_, entries) -> entries.any { it.first == type } }?.key?.name ?: "?"
                arr.add(
                    schemaless(
                        type,
                        "执行 Habitat 节点（模块 $module）。arguments 为该节点的 params 键值对，值支持 \${var} 插值；" +
                            "执行结果（变量快照）以 JSON 返回。失败时 isError=true。",
                    ),
                )
            }
        }
        return arr
    }

    /** 导出给 stdio 桥的离线清单（默认设置：不列高危节点）。 */
    fun manifest(version: String, tools: JsonArray): String {
        val root = JsonObject().apply {
            add("serverInfo", JsonObject().apply {
                addProperty("name", SERVER_NAME)
                addProperty("version", version)
            })
            add("tools", tools)
        }
        return GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root) + "\n"
    }

    private fun schemaless(name: String, description: String): JsonObject = JsonObject().apply {
        addProperty("name", name)
        addProperty("description", description)
        add("inputSchema", JsonObject().apply {
            addProperty("type", "object")
            add("properties", JsonObject())
            addProperty("additionalProperties", true)
        })
    }
}
