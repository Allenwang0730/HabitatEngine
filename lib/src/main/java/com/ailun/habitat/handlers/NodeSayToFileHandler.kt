package com.ailun.habitat.handlers

import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale

/**
 * [ACTION_SAY_TO_FILE]：TTS 合成语音为音频文件（零权限，离线）。
 * params：
 * - `text`（必填）：要合成的文本
 * - `language`（可选）："zh"/"en"/"ja"/"ko"，默认 zh
 * - `path`（可选）：输出 .wav 文件路径；默认 filesDir/tts_<时间戳>.wav
 * - `output_var`（可选）：结果变量名，默认 "tts_file"
 * 输出：tts_file、say_success、say_error
 */
class NodeSayToFileHandler : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        val text = context.interpolate(node.params?.get("text")?.toString() ?: "")
        if (text.isEmpty()) {
            context.putVariable("say_success", false)
            context.putVariable("say_error", "缺少 text")
            return node.next
        }
        val lang = node.params?.get("language")?.toString()?.trim()?.lowercase() ?: "zh"
        val rawPath = node.params?.get("path")?.toString()
        val outputVar = node.params?.get("output_var")?.toString()?.trim()?.ifEmpty { null } ?: "tts_file"
        val path = if (!rawPath.isNullOrBlank()) {
            context.interpolate(rawPath)
        } else {
            File(context.appContext.filesDir, "tts_${System.currentTimeMillis()}.wav").absolutePath
        }
        val locale = when (lang) {
            "zh" -> Locale.CHINESE
            "en" -> Locale.ENGLISH
            "ja" -> Locale.JAPANESE
            "ko" -> Locale.KOREAN
            else -> Locale.getDefault()
        }

        FilePathGuard.check(context.appContext, path, FilePathGuard.Access.WRITE)?.let { reason ->
            context.putVariable("say_success", false)
            context.putVariable("say_error", reason)
            return node.next
        }

        // TTS 初始化（异步）+ 合成到文件（等待合成完成或超时）
        val ttsReady = CompletableDeferred<Int>()
        val tts = TextToSpeech(context.appContext) { status -> ttsReady.complete(status) }
        try {
            val status = withTimeoutOrNull(5000) { ttsReady.await() }
            if (status != TextToSpeech.SUCCESS) {
                context.putVariable("say_success", false)
                context.putVariable(
                    "say_error",
                    if (status == null) "TTS 初始化超时" else "TTS 初始化失败（status=$status，可能无 TTS 引擎）",
                )
                return node.next
            }
            tts.setLanguage(locale)

            val done = CompletableDeferred<Boolean>()
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { done.complete(true) }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { done.complete(false) }
                override fun onError(utteranceId: String?, errorCode: Int) { done.complete(false) }
            })

            val outFile = File(path)
            val rc = withContext(Dispatchers.IO) {
                outFile.absoluteFile.parentFile?.mkdirs()
                tts.synthesizeToFile(text, null, outFile, "habitat_tts")
            }
            if (rc != TextToSpeech.SUCCESS) {
                context.putVariable("say_success", false)
                context.putVariable("say_error", "synthesizeToFile 失败（rc=$rc）")
                return node.next
            }
            val completed = withTimeoutOrNull(30_000) { done.await() } ?: false

            if (completed && outFile.length() > 0) {
                context.putVariable(outputVar, path)
                context.putVariable("tts_file", path)
                context.putVariable("say_success", true)
                context.log("SayToFile OK: $path")
            } else {
                context.putVariable("say_success", false)
                context.putVariable("say_error", if (completed) "输出文件为空" else "TTS 合成失败或超时")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            context.putVariable("say_success", false)
            context.putVariable("say_error", e.message ?: "Unknown error")
        } finally {
            runCatching { tts.shutdown() }
        }
        return node.next
    }

    companion object {
        private const val TAG = "HabitatSayToFile"
    }
}
