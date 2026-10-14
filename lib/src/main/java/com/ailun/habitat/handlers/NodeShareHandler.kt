package com.ailun.habitat.handlers

import android.content.Intent
import android.util.Log
import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode

/**
 * [ACTION_SHARE]：通过系统分享面板分享内容。
 *
 * params：
 * - `text`（可选）：要分享的文本内容
 * - `title`（可选）：分享对话框标题，默认 "分享"
 * - `type`（可选）：MIME 类型，默认 "text/plain"
 * - `subject`（可选）：邮件主题等（用于 ACTION_SEND extra）
 */
class NodeShareHandler : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        val params = node.params ?: return node.next

        val rawText = params["text"]?.toString()?.trim() ?: run {
            Log.w(TAG, "No text specified for sharing")
            context.putVariable("share_success", false)
            return node.next
        }

        val text = context.interpolate(rawText)
        val title = params["title"]?.toString()?.trim()?.let { context.interpolate(it) }
            ?: "分享"
        val mimeType = params["type"]?.toString()?.trim() ?: "text/plain"
        val subject = params["subject"]?.toString()?.trim()?.let { context.interpolate(it) }

        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_TEXT, text)
                if (subject != null) {
                    putExtra(Intent.EXTRA_SUBJECT, subject)
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val chooserIntent = Intent.createChooser(intent, title).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.appContext.startActivity(chooserIntent)

            context.putVariable("share_success", true)
            Log.i(TAG, "Share intent launched: type=$mimeType, text=${text.take(50)}...")
        } catch (e: Exception) {
            Log.e(TAG, "Share failed: ${e.message}", e)
            context.putVariable("share_success", false)
            context.putVariable("share_error", e.message ?: "Unknown error")
        }

        return node.next
    }

    companion object {
        private const val TAG = "HabitatShare"
    }
}
