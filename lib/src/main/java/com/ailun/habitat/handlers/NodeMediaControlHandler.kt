package com.ailun.habitat.handlers

import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode
import com.ailun.habitat.api.IShellExecutor

/**
 * [ACTION_MEDIA_CONTROL]：控制其他播放器（play/pause/next/prev/stop）。
 * 实现：shell `input keyevent`（KEYCODE_MEDIA_*），需 Shizuku/root。
 * params：
 * - `cmd`（必填）："play" / "pause" / "play_pause" / "next" / "prev" / "previous" / "stop"
 * 输出：media_success、media_error
 */
class NodeMediaControlHandler(
    private val shell: IShellExecutor?,
) : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        val cmd = node.params?.get("cmd")?.toString()?.trim()?.lowercase() ?: "play"
        val keyCode = KEYCODES[cmd]
        if (keyCode == null) {
            context.variables["media_success"] = false
            context.variables["media_error"] = "未知 cmd: $cmd（play/pause/play_pause/next/prev/stop）"
            return node.next
        }

        val executor = shell
        if (executor == null) {
            context.variables["media_success"] = false
            context.variables["media_error"] = "需要 Shizuku（shell 执行器不可用）"
            context.log("MediaControl FAILED: no shell executor")
            return node.next
        }

        try {
            val out = executor.exec("input keyevent $keyCode")
            val ok = !out.contains("Error", ignoreCase = true) && !out.contains("exception", ignoreCase = true)
            context.variables["media_success"] = ok
            if (!ok) context.variables["media_error"] = out.take(200)
            context.log("MediaControl $cmd (keyevent $keyCode) → $ok")
        } catch (e: Exception) {
            context.variables["media_success"] = false
            context.variables["media_error"] = e.message ?: "Unknown error"
            context.log("MediaControl FAILED: ${e.message}")
        }
        return node.next
    }

    companion object {
        // KEYCODE_MEDIA_PLAY_PAUSE=85, NEXT=87, PREVIOUS=88, STOP=86
        private val KEYCODES = mapOf(
            "play" to 85, "pause" to 85, "play_pause" to 85,
            "next" to 87, "prev" to 88, "previous" to 88,
            "stop" to 86,
        )
    }
}
