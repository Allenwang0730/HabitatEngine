package com.ailun.habitat.handlers

import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode
import com.ailun.habitat.paramDouble
import com.ailun.habitat.paramLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID

/**
 * [ACTION_TEXT_TO_SPEECH]：将文本转换为语音播放。
 *
 * params：
 * - `text`（必填）：要朗读的文本
 * - `language`（可选）："zh" / "en" / "ja" 等，默认使用设备默认语言
 * - `pitch`（可选）：音调，1.0 为正常，默认 1.0
 * - `speed`（可选）：语速，1.0 为正常，默认 1.0
 * - `timeout_ms`（可选）：初始化+朗读总超时，默认 120000
 * 输出：tts_success、tts_error
 */
class NodeTtsHandler : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        val params = node.params ?: return node.next
        context.putVariable("tts_success", false)

        val rawText = params["text"]?.toString()?.trim() ?: run {
            Log.w(TAG, "No text specified")
            return node.next
        }

        val text = context.interpolate(rawText)
        if (text.isEmpty()) {
            Log.w(TAG, "Text is empty after interpolation")
            return node.next
        }

        val languageCode = params["language"]?.toString()?.let { context.interpolate(it) }?.trim()?.lowercase()
        val pitch = paramDouble(node, context, "pitch", 1.0).toFloat().coerceIn(0.1f, 4.0f)
        val speed = paramDouble(node, context, "speed", 1.0).toFloat().coerceIn(0.1f, 4.0f)
        val timeoutMs = paramLong(node, context, "timeout_ms", DEFAULT_TIMEOUT_MS).coerceAtLeast(1000L)

        val initDone = CompletableDeferred<Int>()
        // 回调可能重复/乱序到达：CompletableDeferred.complete 只生效一次，替代原先非 volatile 的 isCompleted 标志
        val spoken = CompletableDeferred<Boolean>()
        var tts: TextToSpeech? = null

        try {
            val result = withTimeoutOrNull(timeoutMs) {
                tts = TextToSpeech(context.appContext) { status -> initDone.complete(status) }
                val status = initDone.await()
                val engine = tts ?: return@withTimeoutOrNull false
                if (status != TextToSpeech.SUCCESS) {
                    Log.e(TAG, "TTS initialization failed with status: $status")
                    context.putVariable("tts_error", "TTS 初始化失败（status=$status）")
                    return@withTimeoutOrNull false
                }

                val langResult = engine.setLanguage(localeOf(languageCode))
                if (langResult == TextToSpeech.LANG_MISSING_DATA ||
                    langResult == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    Log.w(TAG, "Language $languageCode not supported, using default")
                    engine.setLanguage(Locale.getDefault())
                }
                engine.setPitch(pitch)
                engine.setSpeechRate(speed)

                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(uttId: String?) {
                        Log.d(TAG, "TTS started: $uttId")
                    }

                    override fun onDone(uttId: String?) {
                        spoken.complete(true)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(uttId: String?) {
                        spoken.complete(false)
                    }

                    override fun onError(uttId: String?, errorCode: Int) {
                        Log.e(TAG, "TTS error: $uttId, code=$errorCode")
                        spoken.complete(false)
                    }

                    override fun onStop(uttId: String?, interrupted: Boolean) {
                        spoken.complete(!interrupted)
                    }
                })

                val speakResult = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
                if (speakResult != TextToSpeech.SUCCESS) {
                    Log.e(TAG, "TTS speak() failed with result: $speakResult")
                    context.putVariable("tts_error", "speak() 失败（result=$speakResult）")
                    return@withTimeoutOrNull false
                }
                spoken.await()
            }

            if (result == null) context.putVariable("tts_error", "TTS 超时（${timeoutMs}ms）")
            context.putVariable("tts_success", result == true)
            Log.i(TAG, "TTS result: $result")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "TTS failed: ${e.message}", e)
            context.putVariable("tts_error", e.message ?: "Unknown error")
        } finally {
            runCatching { tts?.stop() }
            runCatching { tts?.shutdown() }
        }

        return node.next
    }

    @Suppress("DEPRECATION")
    private fun localeOf(code: String?): Locale = when (code) {
        "zh" -> Locale.CHINESE
        "en" -> Locale.ENGLISH
        "ja" -> Locale.JAPANESE
        "ko" -> Locale.KOREAN
        "fr" -> Locale.FRENCH
        "de" -> Locale.GERMAN
        "es" -> Locale("es")
        "pt" -> Locale("pt")
        "ru" -> Locale("ru")
        "it" -> Locale.ITALIAN
        else -> Locale.getDefault()
    }

    companion object {
        private const val TAG = "HabitatTTS"
        private const val DEFAULT_TIMEOUT_MS = 120_000L
    }
}
