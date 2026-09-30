package com.ailun.habitat.handlers

import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode
import com.ailun.habitat.handlers.FilePathGuard.Access
import com.ailun.habitat.paramBool
import com.ailun.habitat.paramLong
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * - `max_unzip_bytes`（可选）：解压总大小上限，默认 1GB，超出即中止并报错
 * 输出：zip_result（list=JSON 数组；zip/unzip=目标路径）、zip_count(Int)、zip_success、zip_error
 */
class NodeZipHandler : INodeHandler {

    private val gson = Gson()

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? =
        withContext(Dispatchers.IO) { handleIo(node, context) }

    private fun checkPath(context: WorkflowContext, path: String, access: Access) {
        FilePathGuard.check(context.appContext, path, access)?.let { throw SecurityException(it) }
    }

    private fun handleIo(node: WorkflowNode, context: WorkflowContext): String? {
        context.putVariable("zip_success", false)
        val action = node.params?.get("action")?.toString()?.trim()?.lowercase() ?: "zip"
        val rawSource = node.params?.get("source")?.toString()?.trim().orEmpty()
        if (rawSource.isEmpty()) {
            context.putVariable("zip_success", false)
            context.putVariable("zip_error", "缺少 'source' 参数")
            return node.next
        }
        val source = context.interpolate(rawSource)
        val outputVar = node.params?.get("output_var")?.toString()?.trim()?.ifEmpty { null }
            ?: "zip_result"
        val deleteSource = paramBool(node, context, "delete_source", false)

        try {
            when (action) {
                "list" -> {
                    checkPath(context, source, Access.READ)
                    val file = File(source)
                    if (!file.exists()) throw IllegalArgumentException("zip 文件不存在: $source")
                    val names = ZipFile(file).use { zip ->
                        zip.entries().asSequence().map { it.name }.toList()
                    }
                    context.putVariable(outputVar, gson.toJson(names))
                    context.putVariable("zip_count", names.size)
                    context.putVariable("zip_success", true)
                    context.log("Zip list ${names.size} entries: $source")
                }

                "unzip" -> {
                    val file = File(source)
                    if (!file.exists()) throw IllegalArgumentException("zip 文件不存在: $source")
                    val dest = node.params?.get("dest")?.toString()?.let { context.interpolate(it) }
                        ?: File(file.parentFile, file.nameWithoutExtension).absolutePath
                    checkPath(context, source, Access.READ)
                    checkPath(context, dest, Access.WRITE)
                    val maxBytes = paramLong(node, context, "max_unzip_bytes", DEFAULT_MAX_UNZIP_BYTES)
                    val destDir = File(dest).apply { mkdirs() }
                    val destCanonical = destDir.canonicalPath
                    var count = 0
                    var total = 0L
                    ZipFile(file).use { zip ->
                        for (entry in zip.entries()) {
                            val outFile = File(destDir, entry.name)
                            val outCanonical = outFile.canonicalPath
                            // zip-slip 防护：必须落在 destDir 内（带分隔符比较，防 /out 与 /out_evil 前缀混淆）
                            if (outCanonical != destCanonical && !outCanonical.startsWith(destCanonical + File.separator)) {
                                context.log("Zip 跳过越界条目: ${entry.name}")
                                continue
                            }
                            if (entry.isDirectory) {
                                outFile.mkdirs()
                            } else {
                                outFile.parentFile?.mkdirs()
                                zip.getInputStream(entry).use { input ->
                                    outFile.outputStream().use { output ->
                                        val buf = ByteArray(8192)
                                        while (true) {
                                            val n = input.read(buf)
                                            if (n < 0) break
                                            total += n
                                            if (total > maxBytes) {
                                                throw IllegalStateException("解压总大小超过上限 $maxBytes 字节")
                                            }
                                            output.write(buf, 0, n)
                                        }
                                    }
                                }
                                count++
                            }
                        }
                    }
                    context.putVariable(outputVar, destDir.absolutePath)
                    context.putVariable("zip_count", count)
                    context.putVariable("zip_success", true)
                    if (deleteSource && FilePathGuard.check(context.appContext, source, Access.DELETE) == null) file.delete()
                    context.log("Zip unzip $count files → $dest")
                }

                else -> { // zip 压缩
                    val src = File(source)
                    if (!src.exists()) throw IllegalArgumentException("源不存在: $source")
                    val dest = node.params?.get("dest")?.toString()?.let { context.interpolate(it) }
                        ?: "${source}.zip"
                    checkPath(context, source, Access.READ)
                    checkPath(context, dest, Access.WRITE)
                    val zipFile = File(dest).apply { parentFile?.mkdirs() }
                    val zipCanonical = zipFile.canonicalPath
                    var count = 0
                    ZipOutputStream(BufferedOutputStream(zipFile.outputStream())).use { zos ->
                        src.walkTopDown().forEach { f ->
                            // dest 位于 source 目录内时跳过输出文件自身，避免把自己打包进去
                            if (f.isFile && f.canonicalPath != zipCanonical) {
                                val entryName = if (src.isDirectory) {
                                    val rel = f.toRelativeString(src).replace(File.separatorChar, '/')
                                    if (rel.isEmpty()) "${src.name}/" else "${src.name}/$rel"
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
                    context.putVariable(outputVar, zipFile.absolutePath)
                    context.putVariable("zip_count", count)
                    context.putVariable("zip_success", true)
                    if (deleteSource && src.isFile && FilePathGuard.check(context.appContext, source, Access.DELETE) == null) src.delete()
                    context.log("Zip $count files → $dest")
                }
            }
        } catch (e: Exception) {
            context.putVariable("zip_success", false)
            context.putVariable("zip_error", e.message ?: "Unknown error")
            context.log("Zip FAILED: ${e.message}")
        }
        return node.next
    }

    companion object {
        private const val DEFAULT_MAX_UNZIP_BYTES = 1L shl 30
    }
}
