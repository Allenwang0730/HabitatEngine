package com.ailun.habitat

/**
 * 节点参数读取工具：字符串先插值再解析，数字既接受 Number 也接受 "5000"/"10.0" 这类字符串。
 * 缺失或无法解析时返回 default（OrNull 版本返回 null，用于区分"未提供"与"提供了 0"）。
 */

private fun rawParam(node: WorkflowNode, ctx: WorkflowContext?, key: String): Any? {
    val v = node.params?.get(key) ?: return null
    return if (v is String && ctx != null) ctx.interpolate(v) else v
}

private fun Any.asDoubleOrNull(): Double? = when (this) {
    is Number -> toDouble()
    is Boolean -> if (this) 1.0 else 0.0
    else -> toString().trim().toDoubleOrNull()
}?.takeIf { !it.isNaN() }

fun paramString(node: WorkflowNode, ctx: WorkflowContext?, key: String, default: String? = null): String? =
    rawParam(node, ctx, key)?.toString() ?: default

fun paramDoubleOrNull(node: WorkflowNode, ctx: WorkflowContext?, key: String): Double? =
    rawParam(node, ctx, key)?.asDoubleOrNull()

fun paramLongOrNull(node: WorkflowNode, ctx: WorkflowContext?, key: String): Long? {
    val v = rawParam(node, ctx, key) ?: return null
    if (v is Long || v is Int || v is Short || v is Byte) return (v as Number).toLong()
    (v as? String)?.trim()?.toLongOrNull()?.let { return it }
    val d = v.asDoubleOrNull() ?: return null
    return if (d.isInfinite()) null else d.toLong()
}

fun paramIntOrNull(node: WorkflowNode, ctx: WorkflowContext?, key: String): Int? =
    paramLongOrNull(node, ctx, key)?.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())?.toInt()

fun paramDouble(node: WorkflowNode, ctx: WorkflowContext?, key: String, default: Double): Double =
    paramDoubleOrNull(node, ctx, key) ?: default

fun paramLong(node: WorkflowNode, ctx: WorkflowContext?, key: String, default: Long): Long =
    paramLongOrNull(node, ctx, key) ?: default

fun paramInt(node: WorkflowNode, ctx: WorkflowContext?, key: String, default: Int): Int =
    paramIntOrNull(node, ctx, key) ?: default

fun paramBool(node: WorkflowNode, ctx: WorkflowContext?, key: String, default: Boolean): Boolean {
    val v = rawParam(node, ctx, key) ?: return default
    return when (v) {
        is Boolean -> v
        is Number -> v.toDouble() != 0.0
        else -> when (v.toString().trim().lowercase()) {
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            else -> default
        }
    }
}
