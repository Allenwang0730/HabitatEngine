package com.ailun.habitat.handlers

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode

/**
 * [ACTION_CLIPBOARD]：读取或写入系统剪贴板。
 *
 * params：
 * - `action`（必填）："get" 或 "set"
 * - `text`（set 时必填）：要写入剪贴板的文本
 * - `output_var`（get 时可选）：存储剪贴板内容的变量名，默认 clipboard_content
 */
class NodeClipboardHandler : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        val params = node.params ?: return node.next

        val action = params["action"]?.toString()?.trim()?.lowercase() ?: run {
            Log.w(TAG, "No action specified")
            context.putVariable("clipboard_success", false)
            return node.next
        }

        val clipboardManager = context.appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboardManager == null) {
            Log.e(TAG, "ClipboardManager not available")
            context.putVariable("clipboard_success", false)
            return node.next
        }

        try {
            when (action) {
                "get" -> {
                    val outputVar = params["output_var"]?.toString()?.trim()?.ifEmpty { null }
                        ?: "clipboard_content"

                    val clip = clipboardManager.primaryClip
                    val text = if (clip != null && clip.itemCount > 0) {
                        clip.getItemAt(0)?.text?.toString() ?: ""
                    } else {
                        ""
                    }

                    context.putVariable(outputVar, text)
                    context.putVariable("clipboard_content", text)
                    context.putVariable("clipboard_success", true)

                    Log.i(TAG, "Clipboard read: ${text.take(100)}${if (text.length > 100) "..." else ""}")
                }

                "set" -> {
                    val text = context.interpolate(
                        params["text"]?.toString() ?: ""
                    )
                    val clip = ClipData.newPlainText("habitat_clipboard", text)
                    clipboardManager.setPrimaryClip(clip)

                    context.putVariable("clipboard_success", true)

                    Log.i(TAG, "Clipboard set: ${text.take(100)}${if (text.length > 100) "..." else ""}")
                }

                else -> {
                    Log.w(TAG, "Unknown clipboard action: $action")
                    context.putVariable("clipboard_success", false)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Clipboard operation failed: ${e.message}", e)
            context.putVariable("clipboard_success", false)
            context.putVariable("clipboard_error", e.message ?: "Unknown error")
        }

        return node.next
    }

    companion object {
        private const val TAG = "HabitatClipboard"
    }
}
