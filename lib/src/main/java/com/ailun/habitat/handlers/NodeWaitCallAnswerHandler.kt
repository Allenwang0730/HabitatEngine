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
        val timeoutMs = (node.params?.get("timeout_ms") as? Number)?.toLong() ?: 120_000L
        val answered = waitForAnswer(context.appContext, timeoutMs)
        context.variables["call_answered"] = answered
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
