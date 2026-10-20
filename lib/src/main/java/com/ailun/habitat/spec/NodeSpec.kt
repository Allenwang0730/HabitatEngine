package com.ailun.habitat.spec

/**
 * 节点参数规格：每种节点有哪些参数、什么类型、是否必填、可选值、输出哪些变量。
 *
 * 单一数据源，下游都从这里生成：MCP 工具的 inputSchema、AI 生成工作流的提示词、
 * 工作流 lint（未知参数 / 缺必填 / 枚举不符）、docs/NODES.md。
 * 条目按模块放在 `*Specs.kt`，由 [NodeSpecs] 汇总。
 */
enum class ParamType(val jsonType: String) {
    STRING("string"),
    INT("integer"),
    NUMBER("number"),
    BOOL("boolean"),
    /** 字符串或数字都接受（如 "5000" / 5000），handler 用 paramInt/paramLong 解析。 */
    STRING_OR_NUMBER("string"),
    OBJECT("object"),
    ARRAY("array"),
    /** 任意 JSON 值（如 SET_VARIABLE 的 value）。 */
    ANY(""),
}

data class ParamSpec(
    val name: String,
    val type: ParamType,
    /** 一句话说明（中文）；条件必填写在这里，如「action=set 时必填」。 */
    val description: String,
    /** 只有无条件必填才为 true。 */
    val required: Boolean = false,
    /** 可选值（小写比较）；空表示不限。 */
    val enum: List<String> = emptyList(),
    val default: String? = null,
    /** 值是节点 id（lint 校验引用）。 */
    val nodeRef: Boolean = false,
    /** 同义参数名（handler 也认），lint 不报未知。 */
    val aliases: List<String> = emptyList(),
)

data class NodeSpec(
    val type: String,
    /** 一句话用途（中文）。 */
    val summary: String,
    val params: List<ParamSpec> = emptyList(),
    /** 写出的变量（不含 output_var 指定的自定义名）。 */
    val outputs: List<String> = emptyList(),
    /** 前置条件：需无障碍 / 需 Shizuku / 需某权限；空表示无。 */
    val requires: String = "",
    /** 给模型/读者的补充提示（坑、用法）。 */
    val notes: String = "",
)

/** 所有节点都能用、由执行器处理的通用参数（不在各节点里重复声明）。 */
object CommonParams {
    val all: List<ParamSpec> = listOf(
        ParamSpec("on_error", ParamType.STRING, "节点出错时：skip=跳过继续（默认），stop=停止整个工作流", enum = listOf("skip", "stop")),
        ParamSpec("require", ParamType.STRING, "执行后要求为 true 的变量名（如 click_success），不满足按出错处理"),
        ParamSpec("node_timeout_ms", ParamType.INT, "单节点超时毫秒，0=不限；默认 30 分钟"),
    )
    val names: Set<String> = all.map { it.name }.toSet()
}
