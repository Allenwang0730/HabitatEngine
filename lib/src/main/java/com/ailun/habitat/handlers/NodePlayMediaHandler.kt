package com.ailun.habitat.handlers

import android.media.AudioAttributes
import android.media.MediaPlayer
import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode
import com.ailun.habitat.paramBool
import com.ailun.habitat.paramLongOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [ACTION_PLAY_MEDIA]：播放本地媒体文件（零权限）。
 * params：
 * - `path`（必填）：文件路径，支持 ${var}
 * - `output_var`（可选）：成功标志变量名，默认 "play_success"
 * - `loop`（可选）：true 循环播放
 * - `timeout_ms`（可选）：等待播放结束的超时（默认 文件时长+3s，兜底 60s）
 * 输出：play_success、play_completed(Boolean，播放是否自然结束)、play_duration_ms、play_error
 *
 * 实现要点：同步 prepare()（无 Looper 线程安全）；挂起等待播放完成或超时；
 * finally 中 release() 释放 MediaPlayer。
 */
class NodePlayMediaHandler : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        val rawPath = node.params?.get("path")?.toString()?.trim().orEmpty()
        if (rawPath.isEmpty()) {
            context.putVariable("play_success", false)
            context.putVariable("play_error", "No 'path' specified")
            return node.next
        }
        val path = context.interpolate(rawPath)
        val outputVar = node.params?.get("output_var")?.toString()?.trim()?.ifEmpty { null }
        val loop = paramBool(node, context, "loop", false)
        val explicitTimeout = paramLongOrNull(node, context, "timeout_ms")

        var player: MediaPlayer? = null
        try {
            player = MediaPlayer()
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            player.setDataSource(path)
            player.isLooping = loop
            player.prepare() // 同步准备：无 Looper 线程安全

            val duration = player.duration
            val timeout = explicitTimeout?.coerceAtLeast(1000L)
                ?: (if (duration > 0) duration + 3000L else 60_000L)

            // 监听须在 start 前设置，否则极短音频可能在设置前就已播完/出错而丢事件
            val done = CompletableDeferred<Boolean>()
            player.setOnCompletionListener { done.complete(true) }
            player.setOnErrorListener { _, _, _ -> done.complete(false); true }
            player.start()

            val completed = withTimeoutOrNull(timeout) { done.await() } ?: false

            context.putVariable("play_completed", completed)
            context.putVariable("play_duration_ms", duration)
            if (outputVar != null) context.putVariable(outputVar, true)
            context.putVariable("play_success", true)
            context.log("PlayMedia $path duration=${duration}ms completed=$completed")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            context.putVariable("play_success", false)
            context.putVariable("play_error", e.message ?: "Unknown error")
            context.log("PlayMedia FAILED: ${e.message}")
        } finally {
            runCatching { player?.release() }
        }
        return node.next
    }
}
