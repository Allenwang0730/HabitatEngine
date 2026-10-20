package com.ailun.habitat.spec

import com.ailun.habitat.NodeHandlerFactory as F

/** AI：端侧大模型对话。 */
internal object AiSpecs {
    val all: List<NodeSpec> = listOf(
        NodeSpec(
            F.ACTION_AI_CHAT, "调用端侧本地大模型对话，回复存入变量",
            params = listOf(
                ParamSpec("prompt", ParamType.STRING, "提示词，支持 \${var}", required = true),
                ParamSpec("output_var", ParamType.STRING, "回复存到的变量名", default = "llm_result"),
            ),
            outputs = listOf("llm_success", "llm_result", "llm_error"),
            requires = "需宿主注入 LLM 服务且本地模型已加载",
            notes = "模型未加载时 llm_success=false 并写 llm_error；推理抛错时错误文本也会写进 output_var，判断成败看 llm_success。" +
                "Habitat 自带实现推理失败会返回以 \"Error:\" 开头的文本且 llm_success 仍为 true。",
        ),
    )
}
