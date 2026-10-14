package com.ailun.habitat.app

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.ailun.habitat.app.bridge.AppAccessibilityProvider
import com.ailun.habitat.app.triggers.KeyEventTriggerSource

/**
 * Habitat 无障碍服务。处理手势分发、屏幕读取、按键拦截等辅助功能。
 */
class HabitatAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        AppAccessibilityProvider.onServiceConnected(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        val className = event.className?.toString() ?: return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            AppAccessibilityProvider.onWindowChanged(packageName, className)
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        return KeyEventTriggerSource.onKeyEvent(event.keyCode, event.action)
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        AppAccessibilityProvider.onServiceDestroyed()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        AppAccessibilityProvider.onServiceDestroyed()
        super.onDestroy()
    }
}
