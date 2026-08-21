package com.ailun.habitat.handlers

import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.util.Log
import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode

/**
 * [ACTION_READ_CALL_LOG]：读最近一条通话记录（来电/去电），取号码。
 *
 * params：
 * - `direction`（可选）："incoming"（默认）/ "outgoing" / "all"
 * - `output_var`（可选）：号码写入的变量名，默认 `call_log_number`
 *
 * 输出：`call_log_number`、`call_log_found`（Boolean）、`call_log_type`、`call_log_date`
 * 注意：需要 `READ_CALL_LOG` 权限。通话记录通常在通话**结束后**才写入，响铃时不一定能查到。
 */
class NodeReadCallLogHandler : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        if (context.appContext.checkSelfPermission(android.Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "ReadCallLog failed: READ_CALL_LOG permission not granted")
            context.variables["call_log_found"] = false
            return node.next
        }

        val direction = node.params?.get("direction")?.toString()?.trim()?.lowercase() ?: "incoming"
        val outputVar = node.params?.get("output_var")?.toString()?.trim()?.ifEmpty { null }
            ?: "call_log_number"

        val typeFilter = when (direction) {
            "incoming" -> CallLog.Calls.INCOMING_TYPE
            "outgoing" -> CallLog.Calls.OUTGOING_TYPE
            else -> null // all
        }

        val (number, type, date) = queryLatest(context.appContext, typeFilter)
        context.variables["call_log_number"] = number
        context.variables["call_log_found"] = number != null
        context.variables["call_log_type"] = type
        context.variables["call_log_date"] = date
        if (outputVar != "call_log_number") {
            context.variables[outputVar] = number
        }
        Log.i(TAG, "ReadCallLog: number=$number type=$type")
        return node.next
    }

    private fun queryLatest(context: Context, typeFilter: Int?): Triple<String?, Int?, Long?> {
        val selection = typeFilter?.let { "${CallLog.Calls.TYPE} = ?" }
        val selectionArgs = typeFilter?.let { arrayOf(it.toString()) }
        return try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE),
                selection,
                selectionArgs,
                "${CallLog.Calls.DATE} DESC",
            )?.use { c ->
                if (c.moveToFirst()) {
                    Triple(
                        c.getString(c.getColumnIndexOrThrow(CallLog.Calls.NUMBER)),
                        c.getInt(c.getColumnIndexOrThrow(CallLog.Calls.TYPE)),
                        c.getLong(c.getColumnIndexOrThrow(CallLog.Calls.DATE)),
                    )
                } else Triple(null, null, null)
            } ?: Triple(null, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "ReadCallLog query error: ${e.message}")
            Triple(null, null, null)
        }
    }

    companion object {
        private const val TAG = "HabitatReadCallLog"
    }
}
