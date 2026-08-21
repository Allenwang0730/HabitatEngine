package com.ailun.habitat.handlers

import android.util.Log
import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode
import java.io.File

/**
 * [ACTION_FILE_OPERATION]：文件操作（读、写、复制、删除、存在检查、列表）。
 *
 * params：
 * - `action`（必填）："read" / "write" / "copy" / "delete" / "exists" / "list"
 * - `path`（必填）：文件或目录路径
 * - `dest`（copy 时必填）：目标路径。path 为文件时是完整目标文件路径；
 *   path 为目录时视为目录，把其中**最新修改**的文件复制进去（保留原文件名）
 * - `content`（write 时必填）：要写入的内容
 * - `output_var`（可选）：存储结果的变量名
 * - `append`（write 时可选）：true 表示追加，默认 false 覆盖
 */
class NodeFileOperationHandler : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        val params = node.params ?: return node.next

        val action = params["action"]?.toString()?.trim()?.lowercase() ?: run {
            Log.w(TAG, "No action specified")
            context.variables["file_success"] = false
            return node.next
        }

        val rawPath = params["path"]?.toString()?.trim() ?: run {
            Log.w(TAG, "No path specified")
            context.variables["file_success"] = false
            return node.next
        }

        val path = context.interpolate(rawPath)
        val outputVar = params["output_var"]?.toString()?.trim()?.ifEmpty { null }

        try {
            when (action) {
                "read" -> {
                    val file = File(path)
                    if (!file.exists() || !file.isFile) {
                        Log.w(TAG, "File does not exist or is not a file: $path")
                        context.variables["file_success"] = false
                        context.variables["file_error"] = "File does not exist or is not a file"
                        return node.next
                    }
                    val content = file.readText(Charsets.UTF_8)
                    val resultKey = outputVar ?: "file_result"
                    context.variables["file_result"] = content
                    context.variables[resultKey] = content
                    context.variables["file_success"] = true
                    Log.i(TAG, "File read: $path (${content.length} chars)")
                }

                "write" -> {
                    val rawContent = params["content"]?.toString() ?: ""
                    val content = context.interpolate(rawContent)
                    val append = params["append"]?.toString()?.equals("true", true) == true

                    val file = File(path)
                    // Create parent directories if needed
                    file.parentFile?.let { parent ->
                        if (!parent.exists()) {
                            parent.mkdirs()
                        }
                    }

                    if (append) {
                        file.appendText(content, Charsets.UTF_8)
                    } else {
                        file.writeText(content, Charsets.UTF_8)
                    }

                    context.variables["file_result"] = path
                    context.variables["file_success"] = true
                    Log.i(TAG, "File written: $path (${content.length} chars, append=$append)")
                }

                "delete" -> {
                    val file = File(path)
                    if (!file.exists()) {
                        Log.w(TAG, "File does not exist: $path")
                        context.variables["file_success"] = false
                        context.variables["file_error"] = "File does not exist"
                        return node.next
                    }

                    val deleted = if (file.isDirectory) {
                        file.deleteRecursively()
                    } else {
                        file.delete()
                    }

                    context.variables["file_result"] = deleted
                    context.variables["file_success"] = deleted
                    Log.i(TAG, "File delete: $path -> $deleted")
                }

                "exists" -> {
                    val file = File(path)
                    val exists = file.exists()
                    val isDir = file.isDirectory
                    val isFile = file.isFile

                    context.variables["file_result"] = exists
                    context.variables["file_exists"] = exists
                    context.variables["file_is_directory"] = isDir
                    context.variables["file_is_file"] = isFile
                    context.variables["file_success"] = true
                    Log.i(TAG, "File exists check: $path -> exists=$exists, isDir=$isDir")
                }

                "list" -> {
                    val file = File(path)
                    if (!file.exists() || !file.isDirectory) {
                        Log.w(TAG, "Directory does not exist: $path")
                        context.variables["file_success"] = false
                        context.variables["file_error"] = "Directory does not exist or is not a directory"
                        return node.next
                    }

                    val children = file.listFiles()
                    val names = if (children != null) {
                        children.map { child ->
                            val prefix = if (child.isDirectory) "[D]" else "[F]"
                            "$prefix ${child.name}"
                        }.sorted()
                    } else {
                        emptyList()
                    }

                    // Store both comma-separated and JSON array formats
                    val jsonArray = names.joinToString(",") { "\"${it.replace("\"", "\\\"")}\"" }
                    context.variables["file_result"] = "[$jsonArray]"
                    context.variables["file_list"] = names
                    context.variables["file_count"] = names.size
                    context.variables["file_success"] = true

                    if (outputVar != null) {
                        context.variables[outputVar] = "[$jsonArray]"
                    }

                    Log.i(TAG, "File list: $path -> ${names.size} entries")
                }

                "copy" -> {
                    val rawDest = params["dest"]?.toString()?.trim()
                    if (rawDest.isNullOrEmpty()) {
                        Log.w(TAG, "Copy failed: 'dest' parameter is empty")
                        context.variables["file_success"] = false
                        context.variables["file_error"] = "No 'dest' path specified"
                        return node.next
                    }
                    val destPath = context.interpolate(rawDest)
                    val source = File(path)

                    if (!source.exists()) {
                        Log.w(TAG, "Copy failed: source does not exist: $path")
                        context.variables["file_success"] = false
                        context.variables["file_error"] = "Source does not exist"
                        return node.next
                    }

                    // 源为目录 → 复制其中最新修改的文件（刚生成的录音）；源为文件 → 直接复制
                    val sourceFile: File
                    val targetFile: File
                    if (source.isDirectory) {
                        val newest = source.listFiles()
                            ?.filter { it.isFile }
                            ?.maxByOrNull { it.lastModified() }
                        if (newest == null) {
                            Log.w(TAG, "Copy failed: no file in directory: $path")
                            context.variables["file_success"] = false
                            context.variables["file_error"] = "Directory contains no files"
                            return node.next
                        }
                        sourceFile = newest
                        targetFile = File(destPath, newest.name)
                    } else {
                        sourceFile = source
                        targetFile = File(destPath)
                    }

                    targetFile.parentFile?.let { parent ->
                        if (!parent.exists()) parent.mkdirs()
                    }

                    // 二进制流复制，录音等音频文件不会被损坏
                    sourceFile.copyTo(targetFile, overwrite = true)

                    context.variables["file_result"] = targetFile.absolutePath
                    context.variables["file_copied_from"] = sourceFile.absolutePath
                    context.variables["file_success"] = true
                    Log.i(TAG, "File copied: ${sourceFile.absolutePath} -> ${targetFile.absolutePath}")
                }

                else -> {
                    Log.w(TAG, "Unknown file action: $action")
                    context.variables["file_success"] = false
                    context.variables["file_error"] = "Unknown action: $action"
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "File operation permission denied: ${e.message}", e)
            context.variables["file_success"] = false
            context.variables["file_error"] = "Permission denied: ${e.message}"
        } catch (e: Exception) {
            Log.e(TAG, "File operation failed: ${e.message}", e)
            context.variables["file_success"] = false
            context.variables["file_error"] = e.message ?: "Unknown error"
        }

        return node.next
    }

    companion object {
        private const val TAG = "HabitatFileOp"
    }
}
