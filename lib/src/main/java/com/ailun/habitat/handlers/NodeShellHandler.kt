package com.ailun.habitat.handlers

import com.ailun.habitat.INodeHandler
import com.ailun.habitat.ShellQuote
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode
import com.ailun.habitat.api.IShellExecutor
import com.ailun.habitat.paramBool
import com.ailun.habitat.paramLong

/**
 * [ACTION_SHELL]：执行 shell 命令。
 * params：
 * - `command`（必填）
 * - `mode` 可选：`auto` | `shizuku` | `root`
 * - `raw_interpolation`（可选，默认 false）：
 *   默认情况下 command 中每个 `${var}` 替换后的值都会被 POSIX 单引号包裹（[ShellQuote.quote]），
 *   变量值（如短信/通知正文）只能作为一个完整参数，无法注入命令；因此命令里不要再给 `${var}` 加引号。
 *   设为 true 恢复旧行为（原样拼接，存在注入风险，会记录警告）。
 * - `timeout_ms`（可选，默认 60000）：仅对无 Shizuku 时的本地 Runtime.exec 兜底生效
 */
class NodeShellHandler(
    private val shellExecutor: IShellExecutor? = null
) : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        val rawCommand = node.params?.get("command")?.toString()?.trim().orEmpty()
        if (rawCommand.isEmpty()) return node.next

        val raw = paramBool(node, context, "raw_interpolation", false)
        val command = if (raw) {
            context.log("⚠️ Shell raw_interpolation=true：变量值将原样拼入命令，存在注入风险")
            context.interpolate(rawCommand)
        } else {
            interpolateQuoted(rawCommand, context)
        }
        val modeStr = node.params?.get("mode")?.toString()?.trim()?.lowercase() ?: MODE_AUTO
        val asRoot = modeStr == MODE_ROOT
        val timeoutMs = paramLong(node, context, "timeout_ms", DEFAULT_TIMEOUT_MS).coerceAtLeast(100L)

        val shell = shellExecutor
        val output = if (shell != null) {
            shell.exec(command, asRoot)
        } else {
            runBasicShell(command, timeoutMs)
        }

        context.putVariable("shell_output", output)
        val success = !output.startsWith("Error:")
        context.putVariable("shell_success", success)
        val preview = if (command.length > 60) command.take(60) + "…" else command
        context.log("Shell [$modeStr]: $preview (len=${command.length}) → success=$success, output ${output.length} chars")
        return node.next
    }

    /** 逐个替换 `${var}`，每个值单独 [ShellQuote.quote]；未定义变量与 [WorkflowContext.interpolate] 一致视为 "null"。单次替换，不递归。 */
    private fun interpolateQuoted(text: String, context: WorkflowContext): String {
        if (!text.contains("\${")) return text
        return VAR_REGEX.replace(text) { match ->
            val value = context.getVariable(match.groupValues[1])?.toString() ?: "null"
            ShellQuote.quote(value)
        }
    }

    private suspend fun runBasicShell(command: String, timeoutMs: Long): String = LocalShell.sh(command, timeoutMs)

    companion object {
        const val MODE_AUTO = "auto"
        const val MODE_SHIZUKU = "shizuku"
        const val MODE_ROOT = "root"
        private const val DEFAULT_TIMEOUT_MS = 60_000L
        private val VAR_REGEX = Regex("""\$\{([\w.]+)\}""")
    }
}
