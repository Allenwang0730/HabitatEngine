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
import com.ailun.habitat.R
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

    private var foregroundFailed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        HabitatLogger.d(TAG, "HabitatFloatService onCreate")
        ensureNotificationChannel()
        if (!startForegroundSafely()) {
            // Android 12+ 后台启动被拒（ForegroundServiceStartNotAllowedException）等：不能以非前台状态继续运行，否则稍后被系统杀并崩溃
            foregroundFailed = true
            stopSelf()
            return
        }
        isRunning = true  // 真实进入前台后才标记在运行（避免 optimism 虚假状态）

        // 执行与日志由 FloatWindowManager 自己处理（记录悬浮窗启动的 run id），服务只负责保活
        FloatWindowManager.getInstance(application).showFloatWindow()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        HabitatLogger.d(TAG, "HabitatFloatService onStartCommand")
        return if (foregroundFailed) START_NOT_STICKY else START_STICKY
    }

    override fun onDestroy() {
        HabitatLogger.d(TAG, "HabitatFloatService onDestroy")
        isRunning = false
        FloatWindowManager.getInstanceOrNull()?.let {
            it.onWorkflowSelected = null
            it.onWorkflowStop = null
            it.onDismiss = null
            if (!foregroundFailed) it.destroy()
        }
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

    private fun startForegroundSafely(): Boolean {
        val notification = createNotification()
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (t: Throwable) {
            HabitatLogger.e(TAG, "startForeground failed", t)
            false
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

    companion object {
        private const val TAG = "HabitatFloatService"
        private const val NOTIFICATION_ID = 31042
        private const val CHANNEL_ID = "habitat_float_service"

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context): Boolean {
            // 不乐观置 isRunning——由 onCreate 真实置 true（FGS 启动失败时避免虚假状态）
            return try {
                ContextCompat.startForegroundService(context, Intent(context, HabitatFloatService::class.java))
                true
            } catch (e: Exception) {
                // Android 12+ 后台调用 startForegroundService 可能直接抛 ForegroundServiceStartNotAllowedException
                HabitatLogger.e(TAG, "startForegroundService failed", e)
                false
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HabitatFloatService::class.java))
        }
    }
}
