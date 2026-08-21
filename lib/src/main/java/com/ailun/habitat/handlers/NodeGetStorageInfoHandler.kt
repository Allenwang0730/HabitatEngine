package com.ailun.habitat.handlers

import android.os.Environment
import android.os.StatFs
import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode

/**
 * [ACTION_GET_STORAGE_INFO]：读取存储空间（零权限）。
 * 输出：storage_total、storage_available、storage_used（字节）、
 *       storage_available_percent、storage_success
 */
class NodeGetStorageInfoHandler : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        try {
            val stat = StatFs(Environment.getDataDirectory().absolutePath)
            val total = stat.totalBytes
            val available = stat.availableBytes
            val used = total - available

            context.variables["storage_total"] = total
            context.variables["storage_available"] = available
            context.variables["storage_used"] = used
            context.variables["storage_available_percent"] =
                if (total > 0) (available * 100 / total) else 0
            context.variables["storage_success"] = true
            context.log("Storage total=${total / 1024 / 1024}MB available=${available / 1024 / 1024}MB")
        } catch (e: Exception) {
            context.variables["storage_success"] = false
            context.variables["storage_error"] = e.message ?: "Unknown error"
            context.log("StorageInfo FAILED: ${e.message}")
        }
        return node.next
    }
}
