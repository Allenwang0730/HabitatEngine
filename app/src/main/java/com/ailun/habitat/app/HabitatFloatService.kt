package com.ailun.habitat.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.ailun.habitat.HabitatExecutionService
import com.ailun.habitat.HabitatWorkflow
import com.ailun.habitat.NodeHandlerFactory
import com.ailun.habitat.R
import com.ailun.habitat.app.bridge.AppAccessibilityProvider
import com.ailun.habitat.app.bridge.ShizukuShellExecutor
import com.ailun.habitat.app.bridge.applyAppHandlers
import com.ailun.habitat.app.HabitatLogger
import kotlinx.coroutines.*

/**
 * Habitat 悬浮球服务 - 简化的前台服务，只负责保活
 *
 * 悬浮窗的创建和管理逻辑已移至 FloatWindowManager 单例
 */
class HabitatFloatService : Service() {

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        HabitatLogger.d(TAG, "HabitatFloatService onCreate")
        ensureNotificationChannel()
        startForegroundSafely()

        val manager = FloatWindowManager.getInstance(application)
        manager.onWorkflowSelected = { workflow ->
            runMountedWorkflow(workflow)
        }
        manager.onWorkflowStop = { workflow ->
            // 工作流停止已在manager中处理
        }
        manager.onDismiss = {
            // 面板关闭时不需要额外处理
        }
        manager.showFloatWindow()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        HabitatLogger.d(TAG, "HabitatFloatService onStartCommand")
        return START_STICKY
    }

    override fun onDestroy() {
        HabitatLogger.d(TAG, "HabitatFloatService onDestroy")
        isRunning = false
        FloatWindowManager.getInstanceOrNull()?.destroy()
        ioScope.cancel()
        mainScope.cancel()
        super.onDestroy()
    }

    // ---------- 通知相关 ----------

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.habitat_float_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            nm.createNotificationChannel(channel)
        }
    }

    private fun startForegroundSafely() {
        val notification = createNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            HabitatLogger.w(TAG, "startForeground failed")
        }
    }

    private fun createNotification(): Notification {
        // 使用 packageManager 动态获取启动 Intent，避免硬编码 MainActivity
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent() // fallback: empty intent
        val pending = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_workflows)
            .setContentTitle(getString(R.string.habitat_float_notification_title))
            .setContentText(getString(R.string.habitat_float_notification_text))
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    // ---------- 工作流执行 ----------

    private fun runMountedWorkflow(wf: HabitatWorkflow) {
        val factory = NodeHandlerFactory(AppAccessibilityProvider, ShizukuShellExecutor(applicationContext)).apply {
            applyAppHandlers(applicationContext)
        }
        val ok = HabitatExecutionService.start(wf.id, wf.jsonContent, applicationContext, factory) { log ->
            HabitatLogger.habitat(log)
        }
        if (ok) {
            HabitatLogger.d(TAG, "启动工作流: ${wf.name}")
        } else {
            HabitatLogger.d(TAG, "工作流已在运行: ${wf.name}")
        }
    }

    companion object {
        private const val TAG = "HabitatFloatService"
        private const val NOTIFICATION_ID = 31042
        private const val CHANNEL_ID = "habitat_float_service"

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            isRunning = true
            ContextCompat.startForegroundService(context, Intent(context, HabitatFloatService::class.java))
        }

        fun stop(context: Context) {
            isRunning = false
            context.stopService(Intent(context, HabitatFloatService::class.java))
        }
    }
}
