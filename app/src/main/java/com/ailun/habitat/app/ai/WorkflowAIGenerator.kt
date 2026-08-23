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
     */
    suspend fun generate(context: Context, request: String, baseWorkflow: String? = null): Result<GeneratedWorkflow> {
        val apiKey = WorkflowAIClient.getApiKey(context)
        val baseUrl = WorkflowAIClient.getBaseUrl(context)
        val systemPrompt = buildSystemPrompt()

        val userPrompt = buildString {
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

    /** 系统提示词：角色 + 工作流格式 + 节点白名单 + few-shot。 */
    private fun buildSystemPrompt(): String = """
你是 Habitat 安卓自动化引擎的工作流生成器。根据用户需求，生成一个 Habitat 工作流 JSON。

【工作流格式】
{
  "name": "工作流名称（简短中文）",
  "description": "一句话说明这个工作流做什么",
  "workflow": {
    "start_node_id": "起始节点id",
    "nodes": {
      "节点id": {"id":"节点id","type":"节点类型","params":{...},"next":"下一节点id"},
      ...
    }
  }
}
规则：
- start_node_id 必须指向一个已存在的节点
- 节点必须有 id/type；params 可选；next 可选（最后一个节点可省略 next）
- 变量用 ${'$'}{变量名} 引用（跨节点传值）
- 每个节点 id 唯一，用 s1/s2/s3... 或语义化命名
- 可选加顶层 "trigger"（自动触发）：{"trigger":{"type":"notification|timer|clipboard|sms|key",...}}，触发器相关变量 ${'$'}{trigger_package}/${'$'}{trigger_text}/${'$'}{trigger_title}
- 只输出上面这一个 JSON 对象，不要多余文字

【可用节点类型与参数】（只用这些 type；优先用"直接 API"节点，避免 UI 点击模拟）
- ACTION_SET_VARIABLE: name, value, type=string/int/bool —— 设变量
- ACTION_LOG: message（支持 ${'$'}{var}）—— 记录日志
- ACTION_DELAY: millis —— 延时
- CONDITION_SWITCH: expression(如 "screen_data == true"), branches={"true":id,"false":id} —— 条件分支
- ACTION_TOAST: message —— 弹提示
- ACTION_GET_TIME: format, output_var —— 取时间
- ACTION_MATH: operation=add/subtract/multiply/divide, a, b, output_var —— 数学
- ACTION_TEXT_OPERATION: action=uppercase/lowercase/trim/replace, input, output_var —— 文本处理
- ACTION_CLIPBOARD: action=get/set, text, output_var —— 剪贴板
- ACTION_PARSE_JSON: json, path(如 data.items[0]), output_var —— 解析 JSON
- ACTION_HTTP_REQUEST: url, method=GET/POST, output_var, timeout —— HTTP 请求
- ACTION_LAUNCH_APP: package_name —— 打开应用（com.android.settings 设置 / com.tencent.mm 微信 / com.tencent.mobileqq QQ / com.android.dialer 拨号）
- ACTION_CLICK: target（"x,y"坐标 或 界面文字）—— 点击
- ACTION_INPUT_TEXT: text, mode=a11y —— 输入文字
- ACTION_READ_SCREEN: keyword, output_var —— 读屏幕(变量 screen_data=是否找到)
- ACTION_FIND_ELEMENT: selector, search_mode=text, timeout_ms, output_var —— 找元素(变量 element_found)
- ACTION_GLOBAL_KEY: key=back/home/recents —— 全局按键
- ACTION_SHELL: command, mode=auto —— shell 命令
- ACTION_GET_BATTERY: output_var —— 电量(battery_level)
- ACTION_NETWORK_STATUS: action=all —— 网络(network_type)
- ACTION_GET_DEVICE_INFO: —— 设备信息
- ACTION_SCREENSHOT: format=base64, output_var —— 截图
- ACTION_VIBRATE: duration —— 振动
- ACTION_TEXT_TO_SPEECH: text, language —— 语音
- ACTION_FILE_OPERATION: action=read/write/exists, path, content, output_var —— 文件
- ACTION_AI_CHAT: prompt, output_var —— 本地 AI 对话
- ACTION_CALL_DIRECT: phone_number, sim —— 直接拨号（无需 UI 模拟，最优先）
- ACTION_CALL_PHONE: phone_number —— 拨打（走 Intent）
- ACTION_SEND_NOTIFICATION: title, message, channel_id —— 发通知
- ACTION_SWIPE: x1,y1,x2,y2,duration_ms —— 滑动
- ACTION_MEDIA_CONTROL: cmd=play/pause/next —— 媒体控制

【优先规则】
- 能用"直接 API"节点就不用 UI 点击模拟。例如拨号用 ACTION_CALL_DIRECT，不要用 LAUNCH_APP+CLICK+INPUT_TEXT 一条龙点 UI。
- 打开应用、发通知、截图、设备信息等都有直接节点；UI 点击（CLICK/SWIPE/INPUT_TEXT）只在没有专门节点（如填表单、点特殊按钮）时才用。

【示例1】需求"启动应用并显示提示"
{"name":"启动设置","description":"打开系统设置后弹提示","workflow":{"start_node_id":"s1","nodes":{"s1":{"id":"s1","type":"ACTION_LAUNCH_APP","params":{"package_name":"com.android.settings"},"next":"s2"},"s2":{"id":"s2","type":"ACTION_DELAY","params":{"millis":1500},"next":"s3"},"s3":{"id":"s3","type":"ACTION_TOAST","params":{"message":"已打开设置"}}}}}}

【示例2】需求"读取网络状态，连上则提示"
{"name":"网络检查","description":"读取网络类型，WiFi 则 Toast 提示","workflow":{"start_node_id":"n1","nodes":{"n1":{"id":"n1","type":"ACTION_NETWORK_STATUS","params":{"action":"all"},"next":"n2"},"n2":{"id":"n2","type":"CONDITION_SWITCH","params":{"expression":"network_type == wifi"},"branches":{"true":"n3","false":"n4"}},"n3":{"id":"n3","type":"ACTION_TOAST","params":{"message":"当前是 WiFi"}},"n4":{"id":"n4","type":"ACTION_TOAST","params":{"message":"当前不是 WiFi"}}}}}}

【示例3】需求"拨打10086并开启录音"
{"name":"拨号录音","description":"直接拨10086","workflow":{"start_node_id":"s1","nodes":{"s1":{"id":"s1","type":"ACTION_CALL_DIRECT","params":{"phone_number":"10086"},"next":"s2"},"s2":{"id":"s2","type":"ACTION_TOAST","params":{"message":"已拨打10086"}}}}}}

只输出 JSON 对象。
""".trimIndent()
}
