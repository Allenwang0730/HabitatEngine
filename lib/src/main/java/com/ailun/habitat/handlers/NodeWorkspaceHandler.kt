package com.ailun.habitat.handlers

import android.util.Base64
import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode
import com.ailun.habitat.paramBool
import com.ailun.habitat.paramInt
import com.ailun.habitat.paramString
import com.ailun.habitat.workspace.Workspace
import com.ailun.habitat.workspace.WorkspaceStore
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [ACTION_WORKSPACE]：本地工作区读写（结果、笔记、图片），在 App 的「工作区」页查看。
 *
 * params：
 * - `action`：write（新建，同名同类则覆盖）/ append（文本追加）/ read / list / delete
 * - `name`：显示名（write/append 必填；read/delete 也可用 `id`）
 * - `content`：文字或 JSON（支持 `${var}`）；kind=image 时为 base64
 * - `kind`：text（默认）/ json / image
 * - `image_path`：kind=image 时也可给图片路径（经路径守卫）
 * - `tags`：逗号分隔
 * - `overwrite`：write 时同名是否覆盖，默认 true；false 则自动改名「名字 (2)」
 * - list：`kind`、`tag`、`limit`（默认 20）
 * - `output_var`：read 的内容 / list 的 JSON 数组写到这里（默认 workspace_content / workspace_list）
 *
 * 输出：`workspace_success`、`workspace_id`、`workspace_error`；read 另有 `workspace_kind`，图片给 `workspace_path`。
 * 条目来源取自 [WorkflowContext.origin]，不能由参数指定；运行记录（record）只能由 App 写入。
 */
class NodeWorkspaceHandler(
    private val storeOf: (WorkflowContext) -> WorkspaceStore = { Workspace.get(it.appContext) },
) : INodeHandler {

    private val gson = Gson()

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        context.putVariable("workspace_success", false)
        context.putVariable("workspace_error", null)
        val action = paramString(node, context, "action", "write")!!.trim().lowercase()
        val error = try {
            withContext(Dispatchers.IO) { run(action, node, context, storeOf(context)) }
        } catch (e: WorkspaceStore.WorkspaceException) {
            e.message
        } catch (e: IllegalArgumentException) {
            e.message
        }
        if (error != null) {
            context.putVariable("workspace_error", error)
            context.log("工作区：$error")
        } else {
            context.putVariable("workspace_success", true)
        }
        return node.next
    }

    /** 成功返回 null，失败返回原因。 */
    private fun run(action: String, node: WorkflowNode, ctx: WorkflowContext, store: WorkspaceStore): String? {
        val source = ctx.origin.ifEmpty { WorkflowContext.ORIGIN_WORKFLOW_PREFIX }
        val name = paramString(node, ctx, "name")?.trim().orEmpty()
        val tags = WorkspaceStore.parseTags(paramString(node, ctx, "tags"))
        when (action) {
            "write" -> {
                if (name.isEmpty()) return "write 需要 name"
                val kind = WorkspaceStore.Kind.parse(paramString(node, ctx, "kind")) ?: return "kind 只能是 text / json / image"
                val overwrite = paramBool(node, ctx, "overwrite", true)
                val entry = if (kind == WorkspaceStore.Kind.IMAGE) {
                    val bytes = imageBytes(node, ctx) ?: return ctx.getVariable("workspace_error") as? String ?: "没有图片内容（content 给 base64 或 image_path 给路径）"
                    val mime = sniffImage(bytes) ?: return "不是 PNG / JPEG / WEBP / GIF 图片"
                    store.write(name, kind, bytes, source, tags, overwrite, mime)
                } else {
                    val content = paramString(node, ctx, "content") ?: return "write 需要 content"
                    store.writeText(name, content, source, kind, tags, overwrite)
                }
                ctx.putVariable("workspace_id", entry.id)
                ctx.log("工作区：已写入「${entry.name}」（${entry.size} 字节）")
            }
            "append" -> {
                if (name.isEmpty()) return "append 需要 name"
                val content = paramString(node, ctx, "content") ?: return "append 需要 content"
                val entry = store.append(name, content, source, tags)
                ctx.putVariable("workspace_id", entry.id)
            }
            "read" -> {
                val entry = locate(node, ctx, store, name) ?: return "找不到条目：${name.ifEmpty { paramString(node, ctx, "id").orEmpty() }}"
                ctx.putVariable("workspace_id", entry.id)
                ctx.putVariable("workspace_kind", entry.kind.name.lowercase())
                val out = outputVar(node, ctx, "workspace_content")
                if (entry.kind == WorkspaceStore.Kind.IMAGE) {
                    val path = store.fileFor(entry.id)?.absolutePath ?: return "条目文件丢失"
                    ctx.putVariable("workspace_path", path)
                    ctx.putVariable(out, path)
                } else {
                    ctx.putVariable(out, store.readText(entry.id) ?: return "条目文件丢失")
                }
            }
            "list" -> {
                val kindParam = paramString(node, ctx, "kind")
                val kind = if (kindParam.isNullOrBlank()) null
                else if (kindParam.trim().lowercase() == "record") WorkspaceStore.Kind.RECORD
                else WorkspaceStore.Kind.parse(kindParam) ?: return "kind 只能是 text / json / image / record"
                val limit = paramInt(node, ctx, "limit", 20).coerceIn(1, 200)
                val tag = paramString(node, ctx, "tag")?.trim()?.takeIf { it.isNotEmpty() }
                val items = store.list(kind = kind, tag = tag, limit = limit).map {
                    mapOf(
                        "id" to it.id, "name" to it.name, "kind" to it.kind.name.lowercase(), "size" to it.size,
                        "updated_at" to it.updatedAt, "source" to it.source, "tags" to it.tags, "pinned" to it.pinned,
                    )
                }
                ctx.putVariable(outputVar(node, ctx, "workspace_list"), gson.toJson(items))
                ctx.putVariable("workspace_count", items.size)
            }
            "delete" -> {
                val entry = locate(node, ctx, store, name) ?: return "找不到条目：$name"
                store.delete(entry.id)
                ctx.putVariable("workspace_id", entry.id)
            }
            else -> return "未知 action: $action（write/append/read/list/delete）"
        }
        return null
    }

    private fun locate(node: WorkflowNode, ctx: WorkflowContext, store: WorkspaceStore, name: String): WorkspaceStore.Entry? {
        val id = paramString(node, ctx, "id")?.trim().orEmpty()
        return when {
            id.isNotEmpty() -> store.get(id)
            name.isNotEmpty() -> store.find(name)
            else -> null
        }
    }

    private fun outputVar(node: WorkflowNode, ctx: WorkflowContext, default: String): String =
        paramString(node, ctx, "output_var")?.trim()?.takeIf { it.isNotEmpty() } ?: default

    private fun imageBytes(node: WorkflowNode, ctx: WorkflowContext): ByteArray? {
        val path = paramString(node, ctx, "image_path")?.trim()?.takeIf { it.isNotEmpty() }
        if (path != null) {
            FilePathGuard.check(ctx.appContext, path, FilePathGuard.Access.READ)?.let {
                ctx.putVariable("workspace_error", it)
                return null
            }
            val file = File(path)
            if (!file.isFile) return null
            if (file.length() > WorkspaceStore.MAX_IMAGE_BYTES) throw WorkspaceStore.WorkspaceException("图片太大（上限 8MB）")
            return file.readBytes()
        }
        val content = paramString(node, ctx, "content")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val b64 = if (content.startsWith("data:")) content.substringAfter(',') else content
        if (b64.length > WorkspaceStore.MAX_IMAGE_BYTES * 4 / 3 + 4) throw WorkspaceStore.WorkspaceException("图片太大（上限 8MB）")
        return runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
    }

    companion object {
        /** 看文件头判断图片类型；不是图片返回 null。 */
        fun sniffImage(bytes: ByteArray): String? {
            fun at(i: Int) = if (i < bytes.size) bytes[i].toInt() and 0xFF else -1
            return when {
                at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 -> "image/png"
                at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> "image/jpeg"
                at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46 -> "image/gif"
                at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46 &&
                    at(8) == 0x57 && at(9) == 0x45 && at(10) == 0x42 && at(11) == 0x50 -> "image/webp"
                else -> null
            }
        }
    }
}
