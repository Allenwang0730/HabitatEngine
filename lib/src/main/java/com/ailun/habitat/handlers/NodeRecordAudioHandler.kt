package com.ailun.habitat.handlers

import android.media.MediaRecorder
import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.File

/**
 * [ACTION_RECORD_AUDIO]：录制一段音频（需 RECORD_AUDIO 运行时权限，非通话录音）。
 * params：
 * - `duration`（可选）：录制毫秒数，默认 10000，范围 500-60000
 * - `path`（可选）：输出文件路径；默认 filesDir/record_<时间戳>.m4a
 * - `output_var`（可选）：文件路径输出变量名，默认 "record_path"
 * 输出：record_success、record_path、record_error
 *
 * 实现要点：MediaRecorder 同步 start/stop（无 Looper 依赖），finally 中 release。
 * 注意：属于高风险节点，建议在测试专用机/模拟器验证。
 */
class NodeRecordAudioHandler : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        val durationMs = (node.params?.get("duration") as? Number)?.toLong()
            ?: node.params?.get("duration")?.toString()?.toLongOrNull()
            ?: 10_000L
        val rawPath = node.params?.get("path")?.toString()
        val outputVar = node.params?.get("output_var")?.toString()?.trim()?.ifEmpty { null }
            ?: "record_path"

        val path = if (!rawPath.isNullOrBlank()) {
            context.interpolate(rawPath)
        } else {
            File(context.appContext.filesDir, "habitat_record_${System.currentTimeMillis()}.m4a")
                .absolutePath
        }

        var recorder: MediaRecorder? = null
        var started = false
        try {
            recorder = MediaRecorder()
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioEncodingBitRate(128_000)
            recorder.setAudioSamplingRate(44_100)
            recorder.setOutputFile(path)
            recorder.prepare()
            recorder.start()
            started = true

            delay(durationMs.coerceIn(500L, 60_000L))

            recorder.stop()
            started = false

            context.putVariable("record_path", path)
            if (outputVar != null) context.putVariable(outputVar, path)
            context.putVariable("record_success", true)
            context.log("RecordAudio OK: $path (${durationMs}ms)")
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            context.putVariable("record_success", false)
            context.putVariable("record_error", "缺少 RECORD_AUDIO 权限: ${e.message}")
            context.log("RecordAudio FAILED (RECORD_AUDIO missing)")
        } catch (e: Exception) {
            context.putVariable("record_success", false)
            context.putVariable("record_error", e.message ?: "Unknown error")
            context.log("RecordAudio FAILED: ${e.message}")
        } finally {
            if (started) {
                runCatching { recorder?.stop() }
            }
            runCatching { recorder?.release() }
        }
        return node.next
    }
}
