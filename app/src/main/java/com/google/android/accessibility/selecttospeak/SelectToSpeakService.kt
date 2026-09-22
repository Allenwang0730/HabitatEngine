package com.google.android.accessibility.selecttospeak

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.ailun.habitat.app.bridge.AppAccessibilityProvider

/**
 * 微信 8.0.52+ 对普通无障碍服务隐藏节点。类名必须保持为系统 SelectToSpeakService，微信才返回气泡树。
 * 手势仍优先走 HabitatAccessibilityService；本服务只负责读屏，主服务未连接时才兼做手势宿主。
 */
class SelectToSpeakService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        AppAccessibilityProvider.onReaderConnected(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            AppAccessibilityProvider.onWindowChanged(
                event.packageName?.toString(),
                event.className?.toString(),
            )
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        AppAccessibilityProvider.onReaderDestroyed(this)
        super.onDestroy()
    }
}
