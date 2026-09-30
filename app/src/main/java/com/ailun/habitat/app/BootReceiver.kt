package com.ailun.habitat.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.ailun.habitat.WorkflowRepository

/**
 * 开机 / 应用升级后拉起进程。触发器恢复已在 [HabitatApp.onCreate] 完成，
 * 这里只负责在有挂载悬浮窗的工作流时重新启动悬浮窗前台服务。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        HabitatLogger.habitat("BootReceiver: $action")

        try {
            val mounted = WorkflowRepository.getFloatMountedWorkflowIds(context)
            if (mounted.isNotEmpty() && Settings.canDrawOverlays(context)) {
                // BOOT_COMPLETED / MY_PACKAGE_REPLACED 属于后台启动前台服务的豁免场景，但仍可能被 ROM 拒绝
                HabitatFloatService.start(context)
            }
        } catch (e: Exception) {
            HabitatLogger.e("BootReceiver", "start float service failed", e)
        }
    }
}
