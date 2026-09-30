package com.ailun.habitat.app.ai

import android.content.Context
import android.util.Log
import com.ailun.habitat.ai.ILLMService
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class LiteRtLLMServiceImpl(private val context: Context) : ILLMService {

    /** 串行化 load/chat/close：防止 chat 进行中另一线程 loadModel 关闭引擎（native use-after-close） */
    private val mutex = Mutex()

    @Volatile
    private var engine: Engine? = null
    @Volatile
    private var currentModelPath: String? = null

    override suspend fun loadModel(modelPath: String): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val modelFile = File(modelPath)
                if (!modelFile.exists()) {
                    return@withLock Result.failure(IllegalArgumentException("Model file not found: $modelPath"))
                }

                engine?.close()
                engine = null
                currentModelPath = null

                val config = EngineConfig(
                    modelPath = modelPath,
                    backend = Backend.CPU()
                )
                engine = Engine(config).also { it.initialize() }
                currentModelPath = modelPath
                Log.i(TAG, "Model loaded: $modelPath")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load model: ${e.message}", e)
                Result.failure(e)
            }
        }
    }

    override suspend fun chat(prompt: String): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val eng = engine ?: return@withLock "Error: Model not loaded"
            try {
                // Conversation 持有 native 资源，每次对话用完即 close
                eng.createConversation().use { conversation ->
                    val response = conversation.sendMessage(Message.user(prompt))
                    response.contents.contents
                        .filterIsInstance<Content.Text>()
                        .joinToString("") { it.text }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Chat failed: ${e.message}", e)
                "Error: ${e.message}"
            }
        }
    }

    /** 释放引擎（等待进行中的对话结束）。 */
    suspend fun close() = withContext(Dispatchers.IO) {
        mutex.withLock {
            try { engine?.close() } catch (e: Exception) { Log.w(TAG, "close failed: ${e.message}") }
            engine = null
            currentModelPath = null
        }
    }

    override fun getModelPath(): String? = currentModelPath

    override fun isReady(): Boolean = engine != null

    companion object {
        private const val TAG = "LiteRtLLM"
    }
}
