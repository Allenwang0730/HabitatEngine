package com.ailun.habitat.api

/**
 * 通知来源接口：由宿主以 NotificationListenerService 实现（lib 不依赖 app）。
 * NotificationManager.getActiveNotifications() 只返回本应用通知，读取全局通知必须经此注入。
 */
interface INotificationSource {
    /**
     * 返回当前活动通知，每条至少包含：
     * "package"(String)、"title"(String)、"text"(String)、"post_time"(Long)。
     * 通知使用权未授予 / 监听服务未连接时抛 [SecurityException] 或 [IllegalStateException]。
     */
    fun activeNotifications(): List<Map<String, Any?>>
}
