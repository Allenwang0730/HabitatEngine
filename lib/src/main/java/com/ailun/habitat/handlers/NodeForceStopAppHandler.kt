package com.ailun.habitat.handlers

import android.util.Log
import com.ailun.habitat.INodeHandler
import com.ailun.habitat.ShellQuote
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode
import com.ailun.habitat.api.IShellExecutor
import com.ailun.habitat.paramBool
import kotlinx.coroutines.CancellationException

/**
 * [ACTION_FORCE_STOP_APP]：强制停止指定应用（经 Shizuku shell 执行 `am force-stop`）。
 *
 * params：`package_name`（必填，须为合法包名）、`as_root`（可选，默认 false；为 true 时以 root 执行）
 */
class NodeForceStopAppHandler(
    private val shellExecutor: IShellExecutor? = null,
) : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        context.putVariable("force_stop_success", false)
        val packageName = node.params?.get("package_name")?.toString()
            ?.let { context.interpolate(it) }?.trim().orEmpty()
        if (packageName.isEmpty()) {
            Log.e(TAG, "Force stop failed: 'package_name' parameter is empty")
            return node.next
        }
        if (!ShellQuote.isPackageName(packageName)) {
            context.log("ForceStop FAILED: 非法包名 '$packageName'")
            return node.next
        }
        if (shellExecutor == null) {
            context.log("ForceStop FAILED: 无 shell 执行器（需 Shizuku）")
            return node.next
        }
        val asRoot = paramBool(node, context, "as_root", false)

        var success = false
        try {
            val output = shellExecutor.exec("am force-stop ${ShellQuote.quote(packageName)}", asRoot = asRoot)
            success = !output.contains("Error", ignoreCase = true) &&
                    !output.contains("SecurityException", ignoreCase = true)
            if (!success) Log.w(TAG, "force-stop output: ${output.take(200)}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Shell force-stop failed: ${e.message}")
        }

        context.putVariable("force_stop_success", success)
        if (success) {
            Log.i(TAG, "Successfully force-stopped app: $packageName")
        } else {
            Log.e(TAG, "Failed to force-stop app: $packageName")
        }

        return node.next
    }

    companion object {
        private const val TAG = "HabitatForceStopApp"
    }
}
