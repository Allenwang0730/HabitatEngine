package com.ailun.habitat.handlers

import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode
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
            context.variables["say_success"] = false
            context.variables["say_error"] = "缺少 text"
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

        // TTS 初始化（异步）+ 合成到文件（同步等待合成完成或超时）
        val ttsReady = CompletableDeferred<TextToSpeech?>()
        val tts = TextToSpeech(context.appContext) { status ->
            ttsReady.complete(if (status == TextToSpeech.SUCCESS) null else null)
        }
        val ready = withTimeoutOrNull(5000) { ttsReady.await() }
        val ttsService = try {
            if (ready != null) {
                @Suppress("DEPRECATION")
                tts.setLanguage(locale)
                tts
            } else tts
        } catch (e: Exception) {
            tts
        }

        val done = CompletableDeferred<Boolean>()
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { done.complete(true) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { done.complete(false) }
            override fun onError(utteranceId: String?, errorCode: Int) { done.complete(false) }
        })

        try {
            val result = withContext(Dispatchers.IO) {
                tts.synthesizeToFile(text, null, File(path), "habitat_tts")
                true
            }
            val completed = withTimeoutOrNull(30_000) { done.await() } ?: false
            tts.shutdown()

            if (completed && File(path).length() > 0) {
                context.variables[outputVar] = path
                context.variables["tts_file"] = path
                context.variables["say_success"] = true
                context.log("SayToFile OK: $path")
            } else {
                context.variables["say_success"] = false
                context.variables["say_error"] = "TTS 合成失败（可能无 TTS 引擎）"
            }
        } catch (e: Exception) {
            context.variables["say_success"] = false
            context.variables["say_error"] = e.message ?: "Unknown error"
            runCatching { tts.shutdown() }
        } finally {
            runCatching { tts.shutdown() }
        }
        return node.next
    }

    companion object {
        private const val TAG = "HabitatSayToFile"
    }
}
