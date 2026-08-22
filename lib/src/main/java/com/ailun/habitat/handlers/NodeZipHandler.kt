package com.ailun.habitat.handlers

import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode
import com.google.gson.Gson
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * [ACTION_ZIP]：ZIP 压缩/解压/列表（零权限，纯 JVM）。
 * params：
 * - `action`（必填）："zip"（压缩）/ "unzip"（解压）/ "list"（列表）
 * - `source`（必填）：zip=要压缩的文件或目录；unzip/list=zip 文件路径
 * - `dest`（可选）：zip=输出 .zip 路径（默认 source+".zip"）；unzip=输出目录（默认 zip 同名目录）
 * - `output_var`（可选）：结果变量名，默认 "zip_result"
 * - `delete_source`（可选）：成功后删除源
 * 输出：zip_result（list=JSON 数组；zip/unzip=目标路径）、zip_count(Int)、zip_success、zip_error
 */
class NodeZipHandler : INodeHandler {

    private val gson = Gson()

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        val action = node.params?.get("action")?.toString()?.trim()?.lowercase() ?: "zip"
        val rawSource = node.params?.get("source")?.toString()?.trim().orEmpty()
        if (rawSource.isEmpty()) {
            context.variables["zip_success"] = false
            context.variables["zip_error"] = "缺少 'source' 参数"
            return node.next
        }
        val source = context.interpolate(rawSource)
        val outputVar = node.params?.get("output_var")?.toString()?.trim()?.ifEmpty { null }
            ?: "zip_result"
        val deleteSource = node.params?.get("delete_source")?.toString()?.equals("true", true) == true

        try {
            when (action) {
                "list" -> {
                    val file = File(source)
                    if (!file.exists()) throw IllegalArgumentException("zip 文件不存在: $source")
                    val names = ZipFile(file).use { zip ->
                        zip.entries().asSequence().map { it.name }.toList()
                    }
                    context.variables[outputVar] = gson.toJson(names)
                    context.variables["zip_count"] = names.size
                    context.variables["zip_success"] = true
                    context.log("Zip list ${names.size} entries: $source")
                }

                "unzip" -> {
                    val file = File(source)
                    if (!file.exists()) throw IllegalArgumentException("zip 文件不存在: $source")
                    val dest = node.params?.get("dest")?.toString()?.let { context.interpolate(it) }
                        ?: File(file.parentFile, file.nameWithoutExtension).absolutePath
                    val destDir = File(dest).apply { mkdirs() }
                    var count = 0
                    ZipFile(file).use { zip ->
                        for (entry in zip.entries()) {
                            val outFile = File(destDir, entry.name).normalize()
                            if (!outFile.absolutePath.startsWith(destDir.absolutePath)) continue // zip-slip 防护
                            if (entry.isDirectory) {
                                outFile.mkdirs()
                            } else {
                                outFile.parentFile?.mkdirs()
                                zip.getInputStream(entry).use { input ->
                                    outFile.outputStream().use { output -> input.copyTo(output) }
                                }
                                count++
                            }
                        }
                    }
                    context.variables[outputVar] = destDir.absolutePath
                    context.variables["zip_count"] = count
                    context.variables["zip_success"] = true
                    if (deleteSource) file.delete()
                    context.log("Zip unzip $count files → $dest")
                }

                else -> { // zip 压缩
                    val src = File(source)
                    if (!src.exists()) throw IllegalArgumentException("源不存在: $source")
                    val dest = node.params?.get("dest")?.toString()?.let { context.interpolate(it) }
                        ?: "${source}.zip"
                    val zipFile = File(dest).apply { parentFile?.mkdirs() }
                    var count = 0
                    ZipOutputStream(BufferedOutputStream(zipFile.outputStream())).use { zos ->
                        val base = if (src.isDirectory) src.parentFile else src.parentFile
                        src.walkTopDown().forEach { f ->
                            if (f.isFile) {
                                val entryName = if (src.isDirectory) {
                                    src.toRelativeString(src.parentFile).let { root ->
                                        f.toRelativeString(src).let { rel ->
                                            val prefix = src.name
                                            if (rel.isEmpty()) "$prefix/" else "$prefix/$rel"
                                        }
                                    }
                                } else {
                                    src.name
                                }
                                zos.putNextEntry(ZipEntry(entryName))
                                f.inputStream().use { input -> input.copyTo(zos) }
                                zos.closeEntry()
                                count++
                            }
                        }
                    }
                    context.variables[outputVar] = zipFile.absolutePath
                    context.variables["zip_count"] = count
                    context.variables["zip_success"] = true
                    if (deleteSource && src.isFile) src.delete()
                    context.log("Zip $count files → $dest")
                }
            }
        } catch (e: Exception) {
            context.variables["zip_success"] = false
            context.variables["zip_error"] = e.message ?: "Unknown error"
            context.log("Zip FAILED: ${e.message}")
        }
        return node.next
    }
}
