package com.ailun.habitat.handlers

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode
import com.ailun.habitat.paramLong
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
        val timeoutMs = paramLong(node, context, "timeout_ms", 600_000L).coerceAtLeast(0L)
        // 无 READ_PHONE_STATE 时 callState 抛 SecurityException，不能当作 IDLE（会误判"已挂断/未接通"）
        if (context.appContext.checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            context.putVariable("call_ended", false)
            context.putVariable("call_error", "缺少 READ_PHONE_STATE 权限")
            context.log("WaitCallEnd FAILED: 缺少 READ_PHONE_STATE 权限")
            return node.next
        }
        val ended = waitForCallEnd(context.appContext, timeoutMs)
        context.putVariable("call_ended", ended)
        Log.i(TAG, "WaitCallEnd finished: call_ended=$ended")
        return node.next
    }

    @Suppress("DEPRECATION")
    private suspend fun waitForCallEnd(context: Context, timeoutMs: Long): Boolean {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val deadline = System.currentTimeMillis() + timeoutMs
        var wasInCall = false

        // 起始态守卫：进入若已是 IDLE（通话未开始/已结束），短观察 3s 仍 IDLE 即视为"通话已结束"，
        // 立即返回 true，避免无通话时死等 timeoutMs（600s）导致假"已接听 600s"（BUG-2）。
        var startState = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) tm.callState else tm.getCallState()
        }.getOrDefault(TelephonyManager.CALL_STATE_IDLE)
        if (startState == TelephonyManager.CALL_STATE_IDLE) {
            val latchEnd = System.currentTimeMillis() + 3000
            while (System.currentTimeMillis() < latchEnd) {
                val s = runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) tm.callState else tm.getCallState()
                }.getOrDefault(TelephonyManager.CALL_STATE_IDLE)
                if (s != TelephonyManager.CALL_STATE_IDLE) break // 又进入通话，走主循环
                delay(1000)
            }
            val after = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) tm.callState else tm.getCallState()
            }.getOrDefault(TelephonyManager.CALL_STATE_IDLE)
            if (after == TelephonyManager.CALL_STATE_IDLE) {
                return true // 3s 仍 IDLE → 无通话可等，视为已结束
            }
        }

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
