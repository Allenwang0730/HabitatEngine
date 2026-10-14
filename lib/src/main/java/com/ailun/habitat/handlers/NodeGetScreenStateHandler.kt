package com.ailun.habitat.handlers

import android.content.Context
import android.app.KeyguardManager
import android.os.PowerManager
import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode

/**
 * [ACTION_GET_SCREEN_STATE]：读取屏幕开关与锁定状态（零权限）。
 * 输出：screen_on(Boolean)、screen_locked(Boolean)、screen_state_success
 */
class NodeGetScreenStateHandler : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        try {
            val pm = context.appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val screenOn = pm?.isInteractive ?: false

            val km = context.appContext.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            val locked = km?.isKeyguardLocked ?: false

            context.putVariable("screen_on", screenOn)
            context.putVariable("screen_locked", locked)
            context.putVariable("screen_state_success", true)
            context.log("ScreenState on=$screenOn locked=$locked")
        } catch (e: Exception) {
            context.putVariable("screen_state_success", false)
            context.putVariable("screen_state_error", e.message ?: "Unknown error")
        }
        return node.next
    }
}
