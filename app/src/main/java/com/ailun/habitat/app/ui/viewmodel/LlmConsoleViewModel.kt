package com.ailun.habitat.app.ui.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ailun.habitat.ai.ILLMService
import com.ailun.habitat.app.ai.LLMServiceProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * LLM 控制台状态：模型路径（可编辑、记住）、SAF 选择后复制到应用私有目录（IO 线程 + 进度）、加载与推理。
 * 私有目录下的模型无需「所有文件访问」权限。
 */
class LlmConsoleViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx get() = getApplication<Application>()
    private val prefs get() = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var modelPath by mutableStateOf(prefs.getString(KEY_PATH, null) ?: DEFAULT_PATH)
    var copyProgress by mutableStateOf<Float?>(null)
        private set
    var copyingName by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var ready by mutableStateOf(false)
        private set
    var chatting by mutableStateOf(false)
        private set
    var response by mutableStateOf("")
        private set
    var message by mutableStateOf<String?>(null)

    private var service: ILLMService? = null
    private var copyJob: Job? = null

    private val modelsDir: File get() = File(ctx.filesDir, "models").apply { mkdirs() }

    /** 从系统设置返回后递增，使权限相关的派生值重新读取。 */
    var resumeTick by mutableStateOf(0)

    val isPrivatePath: Boolean get() = modelPath.startsWith(ctx.filesDir.absolutePath)

    val needsStoragePermission: Boolean
        get() {
            resumeTick
            return !isPrivatePath && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()
        }

    init {
        if (!needsStoragePermission) {
            val s = LLMServiceProvider.get(ctx)
            service = s
            ready = s.isReady()
            if (prefs.getString(KEY_PATH, null) == null) s.getModelPath()?.let { modelPath = it }
        }
    }

    fun savePath() {
        prefs.edit().putString(KEY_PATH, modelPath.trim()).apply()
    }

    /** 把 SAF 选中的文件复制进私有目录。 */
    fun importModel(uri: Uri) {
        if (copyJob?.isActive == true) return
        copyJob = viewModelScope.launch {
            val (name, size) = withContext(Dispatchers.IO) { queryMeta(uri) }
            copyingName = name
            copyProgress = 0f
            try {
                val dest = withContext(Dispatchers.IO) {
                    val target = File(modelsDir, name)
                    val tmp = File(modelsDir, "$name.part")
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        tmp.outputStream().use { output ->
                            val buf = ByteArray(1 shl 20)
                            var copied = 0L
                            var lastReported = 0f
                            while (true) {
                                ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                                copied += n
                                if (size > 0) {
                                    val p = (copied.toFloat() / size).coerceIn(0f, 1f)
                                    if (p - lastReported >= 0.005f) {
                                        lastReported = p
                                        withContext(Dispatchers.Main) { copyProgress = p }
                                    }
                                }
                            }
                        }
                    } ?: throw IllegalStateException("无法读取所选文件")
                    if (target.exists()) target.delete()
                    if (!tmp.renameTo(target)) throw IllegalStateException("写入失败")
                    target
                }
                modelPath = dest.absolutePath
                savePath()
                message = "已导入 ${dest.name}"
            } catch (e: Exception) {
                withContext(Dispatchers.IO) { File(modelsDir, "$name.part").delete() }
                message = if (e is kotlinx.coroutines.CancellationException) "已取消导入" else "导入失败：${e.message}"
                if (e is kotlinx.coroutines.CancellationException) throw e
            } finally {
                copyProgress = null
                copyingName = null
            }
        }
    }

    fun cancelImport() { copyJob?.cancel() }

    fun loadModel() {
        savePath()
        val s = service ?: LLMServiceProvider.get(ctx).also { service = it }
        loading = true
        viewModelScope.launch {
            val result = s.loadModel(modelPath.trim())
            loading = false
            ready = s.isReady()
            message = if (result.isSuccess) "模型加载成功" else "加载失败：${result.exceptionOrNull()?.message}"
        }
    }

    fun send(prompt: String) {
        val s = service ?: return
        if (prompt.isBlank() || chatting) return
        chatting = true
        viewModelScope.launch {
            response = try { s.chat(prompt) } catch (e: Exception) { "出错：${e.message}" }
            chatting = false
        }
    }

    private fun queryMeta(uri: Uri): Pair<String, Long> {
        var name = "model_${System.currentTimeMillis()}.litertlm"
        var size = -1L
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.takeIf { it.isNotBlank() }?.let { name = it.replace('/', '_') }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        return name to size
    }

    companion object {
        private const val PREFS = "habitat_prefs"
        private const val KEY_PATH = "llm_model_path"
        const val DEFAULT_PATH = "/storage/emulated/0/Download/llm/gemma4_2b_v09_obfus_fix_all_modalities_thinking.litertlm"
    }
}
