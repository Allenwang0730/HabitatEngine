package com.ailun.habitat.handlers

import android.content.Context
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode
import kotlinx.coroutines.delay

/**
 * [ACTION_WAIT_CALL_END]：**阻塞等待当前通话结束**（挂断）。
 *
 * 用轮询 `TelephonyManager.callState` 实现（每 1 秒查询一次通话状态），
 * 不依赖回调/Looper（那些在 vivo 等机型上不可靠）。状态从"通话中"（OFFHOOK/RINGING）
 * 变回"空闲"（IDLE）即认为挂断。
 *
 * params：`timeout_ms`（可选，默认 600000）
 * 输出：`call_ended`（Boolean）
 * 注意：需要 `READ_PHONE_STATE` 权限。
 */
class NodeWaitCallEndHandler : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        val timeoutMs = (node.params?.get("timeout_ms") as? Number)?.toLong() ?: 600_000L
        val ended = waitForCallEnd(context.appContext, timeoutMs)
        context.variables["call_ended"] = ended
        Log.i(TAG, "WaitCallEnd finished: call_ended=$ended")
        return node.next
    }

    @Suppress("DEPRECATION")
    private suspend fun waitForCallEnd(context: Context, timeoutMs: Long): Boolean {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val deadline = System.currentTimeMillis() + timeoutMs
        var wasInCall = false

        while (System.currentTimeMillis() < deadline) {
            val state = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) tm.callState else tm.getCallState()
            }.getOrDefault(TelephonyManager.CALL_STATE_IDLE)
            Log.i(TAG, "poll callState=$state wasInCall=$wasInCall")

            if (state == TelephonyManager.CALL_STATE_OFFHOOK || state == TelephonyManager.CALL_STATE_RINGING) {
                wasInCall = true
            } else if (state == TelephonyManager.CALL_STATE_IDLE && wasInCall) {
                return true
            }
            delay(1000)
        }
        return false
    }

    companion object {
        private const val TAG = "HabitatWaitCallEnd"
    }
}
