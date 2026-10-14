package com.ailun.habitat.handlers

import android.content.Intent
import android.net.Uri
import android.util.Log
import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode

/**
 * [ACTION_CALL_PHONE]：打开拨号界面（不直接拨出，无需 CALL_PHONE 权限）。
 *
 * params：`phone_number`（必填）
 */
class NodeCallHandler : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        val rawNumber = node.params?.get("phone_number")?.toString()?.trim().orEmpty()
        if (rawNumber.isEmpty()) {
            Log.e(TAG, "Call failed: 'phone_number' parameter is empty")
            context.putVariable("call_success", false)
            return node.next
        }

        val phoneNumber = context.interpolate(rawNumber)

        try {
            val intent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:$phoneNumber")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.appContext.startActivity(intent)
            Log.i(TAG, "Successfully opened dialer for: $phoneNumber")
            context.putVariable("call_success", true)
        } catch (e: Exception) {
            Log.e(TAG, "Call error for '$phoneNumber': ${e.message}", e)
            context.putVariable("call_success", false)
        }

        return node.next
    }

    companion object {
        private const val TAG = "HabitatCall"
    }
}
