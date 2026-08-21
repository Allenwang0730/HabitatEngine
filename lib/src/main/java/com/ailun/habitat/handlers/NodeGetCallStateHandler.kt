package com.ailun.habitat.handlers

import android.content.Context
import android.telephony.TelephonyManager
import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode

/**
 * [ACTION_GET_CALL_STATE]：读取当前电话状态（需 READ_PHONE_STATE）。
 * 输出：call_state(Int: 0=idle 1=ringing 2=offhook)、call_state_name(String)、call_success
 *
 * 实现要点：同步查询 TelephonyManager.callState（无 Looper 依赖）。
 */
class NodeGetCallStateHandler : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        try {
            val tm = context.appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            val state = tm?.callState ?: TelephonyManager.CALL_STATE_IDLE
            val name = when (state) {
                TelephonyManager.CALL_STATE_IDLE -> "idle"
                TelephonyManager.CALL_STATE_RINGING -> "ringing"
                TelephonyManager.CALL_STATE_OFFHOOK -> "offhook"
                else -> "unknown"
            }
            context.variables["call_state"] = state
            context.variables["call_state_name"] = name
            context.variables["call_success"] = true
            context.log("CallState=$state($name)")
        } catch (e: SecurityException) {
            context.variables["call_success"] = false
            context.variables["call_error"] = "缺少 READ_PHONE_STATE 权限: ${e.message}"
            context.log("CallState FAILED (READ_PHONE_STATE missing)")
        } catch (e: Exception) {
            context.variables["call_success"] = false
            context.variables["call_error"] = e.message ?: "Unknown error"
            context.log("CallState FAILED: ${e.message}")
        }
        return node.next
    }
}
