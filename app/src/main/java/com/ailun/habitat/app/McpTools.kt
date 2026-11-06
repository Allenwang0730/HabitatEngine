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
        arr.add(
            readOnly(schemaless(
                "screen",
                "读当前屏幕（只读，不点任何东西）：每个可见窗口（应用、系统栏、输入法）的无障碍文字，加前台应用包名。" +
                    "用它判断现在在哪个页面、上一步有没有生效；要看可点的候选和坐标用 debug_screen。" +
                    "返回 JSON：screen_on、screen_locked、screen_text（主应用窗口的文字）、windows（每个窗口的 package/type/active/focused/text）、" +
                    "foreground_package、window_count。需要手机上开着 Habitat 无障碍服务；没开或熄屏时 windows 为空、screen_text 为空字符串，" +
                    "不报错。不做 OCR，纯图片/游戏界面可能没有文字。",
            )),
        )
        arr.add(
            JsonObject().apply {
                addProperty("name", "debug_screen")
                addProperty(
                    "description",
                    "排查用（只读）：自主 Agent 此刻看到的屏幕，以及它针对 goal 会考虑的候选动作。和 screen 的区别：" +
                        "这里是 Agent 的观测（控件下标、坐标、是否可点/可输入、开关状态；无障碍树几乎没有文字时自动改用 OCR），" +
                        "再加上代码生成的候选（TAP / TYPE_TEXT / OPEN_APP 等，带 id 和坐标）和安全闸（guard）。" +
                        "agent_start 结果不对、想知道它为什么点错时用。返回纯文本；屏幕锁定时只返回提示和屏幕尺寸。",
                )
                add("inputSchema", JsonObject().apply {
                    addProperty("type", "object")
                    add("properties", JsonObject().apply {
                        add("goal", prop("string", "用来生成候选的目标，影响哪些按钮被列出、哪些被安全闸拦下（默认「打开设置」）"))
                    })
                    addProperty("additionalProperties", false)
                })
                add("annotations", annotations(readOnly = true))
            },
        )
        arr.add(
            readOnly(schemaless(
                "ping",
                "连通性测试（只读，不碰手机状态）：返回 {\"pong\":true}。接入后先调一次，确认令牌和端口没问题；" +
                    "经 stdio 桥时，手机不可达会返回错误并说明原因。",
            )),
        )
        arr.add(
            readOnly(schemaless(
                "mini_screen_status",
                "读回悬浮小屏现在显示的内容（只读）。小屏由 ACTION_MINI_SCREEN 节点或 Agent 的 show_on_screen 写入，" +
                    "用来核对「显示到小屏上」类任务是否完成。返回 JSON：visible、collapsed、title、source、text（各段文字拼接）、" +
                    "images（图片数）、progress。",
            )),
        )
        arr.add(
            JsonObject().apply {
                addProperty("name", "agent_start")
                addProperty(
                    "description",
                    "把一句话目标交给手机上的自主 Agent，后台执行，立即返回（不等完成）；之后用 agent_status 轮询、agent_stop 中止。" +
                        "适合多步、要看屏幕的任务（如「打开设置进入蓝牙页面」）；单个确定的动作直接调对应节点更快更稳。" +
                        "Agent 会真的点击和输入。远程发起的目标受限：发消息只填好不发送，打电话只填拨号盘，支付/授权/权限类按钮不会点，" +
                        "输入的文字必须出现在目标里。同一时间只能跑一个。" +
                        "成功返回 JSON {running:true, phase:\"running\", detail, log}；以下情况返回错误：目标为空、所选执行器没配 key、" +
                        "执行器名字不认识、已有任务在运行。开规划时目标和屏幕文字会发给 DeepSeek，执行器为 jev/deepseek 时发给对应服务；local 不联网。",
                )
                add("inputSchema", JsonObject().apply {
                    addProperty("type", "object")
                    add("properties", JsonObject().apply {
                        add("goal", prop("string", "要完成的目标，一句话，中文效果最好，例如「把屏幕亮度调到最高」"))
                        add(
                            "provider",
                            prop("string", "每一步由谁选动作：jev（TypeSafe Jev）、deepseek、local（规则，不联网）。不填用手机上的设置（默认 local）").apply {
                                add("enum", JsonArray().apply { add("jev"); add("deepseek"); add("local") })
                            },
                        )
                        add(
                            "max_steps",
                            prop("integer", "最多执行多少步，超出范围会被截到 1–120（默认 20）").apply {
                                addProperty("minimum", 1)
                                addProperty("maximum", 120)
                            },
                        )
                        add("planner", prop("boolean", "true：用 DeepSeek 先拆步骤、最后复核；false：不规划。不填跟随手机设置。没配 DeepSeek key 时不规划"))
                    })
                    add("required", JsonArray().apply { add("goal") })
                    addProperty("additionalProperties", false)
                })
                // 会点击、输入；开规划或用 jev/deepseek 时访问外部服务
                add("annotations", annotations(readOnly = false, destructive = true, idempotent = false, openWorld = true))
            },
        )
        arr.add(
            schemaless(
                "agent_stop",
                "中止正在运行的自主 Agent 任务（agent_start 启动的）。正在执行的那一步可能先做完，已经做过的操作不会撤销。" +
                    "没有任务在运行时什么也不做。返回和 agent_status 相同的 JSON，phase 变为 stopped。",
            ).apply { add("annotations", annotations(readOnly = false, destructive = false, idempotent = true, openWorld = false)) },
        )
        arr.add(
            readOnly(schemaless(
                "agent_status",
                "查询自主 Agent 任务的进度和结果（只读）。agent_start 之后每隔几秒调一次，直到 running=false。" +
                    "返回 JSON：running（是否还在跑）、phase（running / done / blocked / error / stopped；没跑过时为 idle）、" +
                    "detail（结束原因或「答案 — 依据」）、answer（提问类目标的答案本身，按目标要求的格式；没有为空串）、" +
                    "obs_sources（各轮读屏来自无障碍树还是 OCR 的次数，结束后才有）、log（最近 40 行步骤记录）。" +
                    "phase=done 是 Agent 自己判断完成，关键任务请再用 screen 或对应节点核对。",
            )),
        )
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

    /** MCP 工具注解（2025-03-26 起）：只是提示，客户端可能据此决定是否要用户确认。 */
    private fun annotations(
        readOnly: Boolean,
        destructive: Boolean = false,
        idempotent: Boolean = true,
        openWorld: Boolean = false,
    ): JsonObject = JsonObject().apply {
        addProperty("readOnlyHint", readOnly)
        if (!readOnly) addProperty("destructiveHint", destructive)
        addProperty("idempotentHint", idempotent)
        addProperty("openWorldHint", openWorld)
    }

    private fun readOnly(tool: JsonObject): JsonObject = tool.apply { add("annotations", annotations(readOnly = true)) }

    private fun prop(type: String, description: String): JsonObject = JsonObject().apply {
        addProperty("type", type)
        addProperty("description", description)
    }

    /** 无参数的内置工具。 */
    private fun schemaless(name: String, description: String): JsonObject = JsonObject().apply {
        addProperty("name", name)
        addProperty("description", description)
        add("inputSchema", JsonObject().apply {
            addProperty("type", "object")
            add("properties", JsonObject())
            addProperty("additionalProperties", false)
        })
    }
}
