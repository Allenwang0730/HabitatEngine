package com.ailun.habitat.app

import android.app.Notification
import android.content.Intent
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.localbroadcastmanager.content.LocalBroadcastManager

/**
 * Habitat 系统通知拦截服务。
 *
 * 继承自 Android 原生的 [NotificationListenerService]，
 * 监听所有系统通知的发布事件，提取关键信息并通过本地广播
 * 发送出去，作为未来 Habitat 工作流触发的基石。
 */
class HabitatNotificationService : NotificationListenerService() {

    companion object {
        const val TAG = "HabitatNotifService"

        /** 本地广播 Action：收到新通知 */
        const val ACTION_HABITAT_NOTIFICATION_POSTED =
            "com.ailun.habitat.app.ACTION_NOTIFICATION_POSTED"

        /** 本地广播 Extra：包名 */
        const val EXTRA_PACKAGE_NAME = "package_name"
        /** 本地广播 Extra：通知标题 */
        const val EXTRA_TITLE = "title"
        /** 本地广播 Extra：通知正文 */
        const val EXTRA_TEXT = "text"
        /** 本地广播 Extra：展开长文本（BigTextStyle） */
        const val EXTRA_BIG_TEXT = "big_text"
        /** 本地广播 Extra：是否常驻/前台服务通知 */
        const val EXTRA_ONGOING = "ongoing"
        /** 本地广播 Extra：时间戳（毫秒） */
        const val EXTRA_TIMESTAMP = "timestamp"
        /** 本地广播 Extra：通知 ID */
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        /** 本地广播 Extra：通知渠道 ID */
        const val EXTRA_CHANNEL_ID = "channel_id"

        /** 当前服务的弱引用或空，供触发模块获取 */
        @Volatile
        var serviceInstance: HabitatNotificationService? = null
            private set

        /**
         * 从通知中提取结构化数据并广播。
         */
        fun broadcastNotificationPosted(
            sbn: StatusBarNotification,
            service: HabitatNotificationService,
        ) {
            val notification = sbn.notification ?: return
            val extras = notification.extras

            val packageName = sbn.packageName
            // 标题/正文可能是 SpannableString，getString 会返回 null
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
            val ongoing = (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0 ||
                (notification.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0
            val timestamp = sbn.postTime
            val notificationId = sbn.id
            val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                notification.channelId ?: ""
            } else ""

            val intent = Intent(ACTION_HABITAT_NOTIFICATION_POSTED).apply {
                putExtra(EXTRA_PACKAGE_NAME, packageName)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_TEXT, text)
                putExtra(EXTRA_BIG_TEXT, bigText)
                putExtra(EXTRA_ONGOING, ongoing)
                putExtra(EXTRA_TIMESTAMP, timestamp)
                putExtra(EXTRA_NOTIFICATION_ID, notificationId)
                putExtra(EXTRA_CHANNEL_ID, channelId)
            }

            LocalBroadcastManager.getInstance(service).sendBroadcast(intent)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        serviceInstance = this
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        serviceInstance = null
    }

    override fun onDestroy() {
        if (serviceInstance === this) serviceInstance = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn?.let { broadcastNotificationPosted(it, this) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        // （预留）未来可扩展通知移除事件的广播和处理
    }
}
