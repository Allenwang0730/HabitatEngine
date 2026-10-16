package com.ailun.habitat.app.ui.workspace

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.core.content.FileProvider
import com.ailun.habitat.app.miniscreen.MiniCommand
import com.ailun.habitat.app.miniscreen.MiniImage
import com.ailun.habitat.app.miniscreen.MiniScreen
import com.ailun.habitat.workspace.Workspace
import com.ailun.habitat.workspace.WorkspaceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

/** 工作区页面用到的格式化和动作（分享、上小屏、缩略图）。 */
internal object WorkspaceActions {

    fun sourceLabel(source: String): String = when {
        source == "user" -> "手动"
        source == "agent" -> "Agent"
        source == "agent:remote" -> "Agent · 远程"
        source == "mcp" -> "MCP"
        source.startsWith("workflow:") -> source.removePrefix("workflow:").ifBlank { "工作流" }
        else -> source.ifBlank { "未知" }
    }

    fun isRemote(source: String): Boolean = source == "mcp" || source.endsWith(":remote")

    fun kindLabel(entry: WorkspaceStore.Entry): String = when (entry.kind) {
        WorkspaceStore.Kind.TEXT -> "文本"
        WorkspaceStore.Kind.JSON -> "JSON"
        WorkspaceStore.Kind.IMAGE -> "图片"
        WorkspaceStore.Kind.RECORD -> if ("workflow" in entry.tags) "工作流记录" else "Agent 记录"
    }

    fun size(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    }

    fun time(ms: Long): String = SimpleDateFormat("HH:mm", Locale.ROOT).format(Date(ms))

    fun fullTime(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(ms))

    fun duration(ms: Long): String = when {
        ms < 1000 -> "${ms}ms"
        ms < 60_000 -> "%.1f 秒".format(ms / 1000.0)
        else -> "${ms / 60_000} 分 ${(ms % 60_000) / 1000} 秒"
    }

    /** 分组标题：今天 / 昨天 / 10月12日 / 2025年3月1日。 */
    fun dayLabel(ms: Long, now: Long = System.currentTimeMillis()): String {
        val day = Calendar.getInstance().apply { timeInMillis = ms }
        val today = Calendar.getInstance().apply { timeInMillis = now }
        fun sameDay(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        if (sameDay(day, today)) return "今天"
        today.add(Calendar.DAY_OF_YEAR, -1)
        if (sameDay(day, today)) return "昨天"
        val pattern = if (day.get(Calendar.YEAR) == Calendar.getInstance().get(Calendar.YEAR)) "M月d日" else "yyyy年M月d日"
        return SimpleDateFormat(pattern, Locale.CHINA).format(Date(ms))
    }

    /** 记录条目的状态（从标签里取，列表不必解析整份 JSON）。 */
    fun recordStatus(entry: WorkspaceStore.Entry): String? =
        entry.tags.firstOrNull { it in setOf("done", "completed", "blocked", "error", "failed", "stopped") }

    suspend fun thumbnail(context: Context, entry: WorkspaceStore.Entry, maxPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        val file = Workspace.get(context).fileFor(entry.id) ?: return@withContext null
        decodeSampled(file, maxPx)
    }

    fun decodeSampled(file: File, maxPx: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxPx && bounds.outHeight / (sample * 2) >= maxPx) sample *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    fun share(context: Context, entry: WorkspaceStore.Entry, text: String?) {
        val intent = Intent(Intent.ACTION_SEND)
        if (entry.kind == WorkspaceStore.Kind.IMAGE) {
            val file = Workspace.get(context).fileFor(entry.id) ?: return toast(context, "文件不存在")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            intent.type = entry.mime
            intent.putExtra(Intent.EXTRA_STREAM, uri)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            intent.type = "text/plain"
            intent.putExtra(Intent.EXTRA_SUBJECT, entry.name)
            intent.putExtra(Intent.EXTRA_TEXT, (text ?: "").take(100_000))
        }
        context.startActivity(Intent.createChooser(intent, "分享「${entry.name}」").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** 把条目显示到悬浮小屏（来源标「工作区」）。 */
    suspend fun showOnMiniScreen(context: Context, entry: WorkspaceStore.Entry, text: String?) {
        val source = "workflow:工作区"
        val runKey = "workspace-" + entry.id
        val result = if (entry.kind == WorkspaceStore.Kind.IMAGE) {
            val file = Workspace.get(context).fileFor(entry.id)
            val bitmap = file?.let { withContext(Dispatchers.IO) { decodeSampled(it, 1080) } }
                ?: return toast(context, "图片无法读取")
            MiniScreen.apply(
                context,
                MiniCommand.Write(false, source, runKey, entry.name, null, MiniImage(UUID.randomUUID().toString(), bitmap.width, bitmap.height)),
                bitmap,
            )
        } else {
            MiniScreen.apply(context, MiniCommand.Write(false, source, runKey, entry.name, text.orEmpty()))
        }
        toast(context, if (result.ok) "已显示到小屏" else result.error ?: "小屏没能显示")
    }

    private fun toast(context: Context, text: String) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }
}
