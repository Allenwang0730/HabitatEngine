package com.ailun.habitat.app.bridge

import android.app.Notification
import com.ailun.habitat.api.INotificationSource
import com.ailun.habitat.app.HabitatNotificationService

/** 通过通知监听服务读取全局活动通知（NotificationManager 只能读本应用的）。 */
object AppNotificationSource : INotificationSource {
    override fun activeNotifications(): List<Map<String, Any?>> {
        val service = HabitatNotificationService.serviceInstance
            ?: throw IllegalStateException("通知监听服务未连接")
        return service.activeNotifications.orEmpty().map { sbn ->
            val extras = sbn.notification.extras
            mapOf(
                "package" to sbn.packageName,
                "title" to (extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""),
                "text" to (extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""),
                "post_time" to sbn.postTime,
            )
        }
    }
}
