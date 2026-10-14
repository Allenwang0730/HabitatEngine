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
 * [ACTION_WAIT_CALL_ANSWER]：**阻塞等待当前通话被接听**（进入 OFFHOOK）。
 *
 * 与 [NodeWaitCallEndHandler] 对称：去电/来电时等对方接听后，再继续做"点录音"等操作。
 * 实现：轮询 `TelephonyManager.callState`，一旦变成 OFFHOOK（通话中）即返回。
 *
 * params：`timeout_ms`（可选，默认 120000）
 * 输出：`call_answered`（Boolean）
 * 注意：需要 `READ_PHONE_STATE` 权限。
 */
class NodeWaitCallAnswerHandler : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        val timeoutMs = paramLong(node, context, "timeout_ms", 120_000L).coerceAtLeast(0L)
        // 无 READ_PHONE_STATE 时 callState 抛 SecurityException，不能当作 IDLE（会误判"已挂断/未接通"）
        if (context.appContext.checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            context.putVariable("call_answered", false)
            context.putVariable("call_error", "缺少 READ_PHONE_STATE 权限")
            context.log("WaitCallAnswer FAILED: 缺少 READ_PHONE_STATE 权限")
            return node.next
        }
        val answered = waitForAnswer(context.appContext, timeoutMs)
        context.putVariable("call_answered", answered)
        Log.i(TAG, "WaitCallAnswer finished: answered=$answered")
        return node.next
    }

    @Suppress("DEPRECATION")
    private suspend fun waitForAnswer(context: Context, timeoutMs: Long): Boolean {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val deadline = System.currentTimeMillis() + timeoutMs
        // 起始态守卫：若进入后一段时间内从未出现 RINGING/OFFHOOK（即通话未接通/已结束），
        // 立即返回 false，避免无通话时死等 timeoutMs 后再去误点"录音"（BUG-2）。
        var sawActivity = false
        val startIdleLatch = System.currentTimeMillis() + 5000

        while (System.currentTimeMillis() < deadline) {
            val state = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) tm.callState else tm.getCallState()
            }.getOrDefault(TelephonyManager.CALL_STATE_IDLE)
            if (state == TelephonyManager.CALL_STATE_OFFHOOK) {
                return true
            }
            if (state == TelephonyManager.CALL_STATE_RINGING) sawActivity = true
            if (!sawActivity && System.currentTimeMillis() >= startIdleLatch) {
                return false // 5s 内从未见到通话 → 无通话可等
            }
            delay(1000)
        }
        return false
    }

    companion object {
        private const val TAG = "HabitatWaitCallAnswer"
    }
}
