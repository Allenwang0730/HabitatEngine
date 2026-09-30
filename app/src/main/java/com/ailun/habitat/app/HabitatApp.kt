package com.ailun.habitat.app

import android.app.Application
import android.os.Build
import com.ailun.habitat.app.auth.AuthStateManager
import com.ailun.habitat.app.network.HabitatApiClient
import com.ailun.habitat.app.ui.ProfilePrefs

/**
 * 进程级初始化：每个进程只执行一次（旋转屏幕/切深色/从通知重进 Activity 都不会重复执行）。
 * 开机、升级（BootReceiver）或被系统拉起时也会先走这里，从而恢复已启用工作流的触发器。
 */
class HabitatApp : Application() {

    override fun onCreate() {
        super.onCreate()
        if (!isMainProcess()) return

        try { AuthStateManager.init(this) } catch (e: Exception) { HabitatLogger.e(TAG, "auth init failed", e) }
        try { HabitatApiClient.init(ProfilePrefs.getServerUrl(this)) } catch (e: Exception) { HabitatLogger.e(TAG, "api init failed", e) }
        // 清理上一进程遗留的"运行中"启用标记 + 恢复带触发器工作流的注册
        try { TriggerManager.restoreOnProcessStart(this) } catch (e: Exception) { HabitatLogger.e(TAG, "trigger restore failed", e) }
    }

    private fun isMainProcess(): Boolean {
        val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) getProcessName() else return true
        return name == null || name == packageName
    }

    companion object {
        private const val TAG = "HabitatApp"
    }
}
