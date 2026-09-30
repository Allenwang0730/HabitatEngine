package com.ailun.habitat.app.bridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.ailun.habitat.api.IAccessibilityProvider
import java.lang.ref.WeakReference

/** [IAccessibilityProvider] 实现，通过 WeakReference 持有无障碍服务实例。 */
object AppAccessibilityProvider : IAccessibilityProvider {

    @Volatile
    private var serviceRef: WeakReference<AccessibilityService>? = null

    /** 伪装成 SelectToSpeak 的读屏服务。微信混淆节点只对这个类名开放。 */
    @Volatile
    private var readerRef: WeakReference<AccessibilityService>? = null

    @Volatile
    private var cachedPackageName: String? = null

    @Volatile
    private var cachedActivityName: String? = null

    /** 由宿主无障碍服务在 onServiceConnected 中调用。 */
    fun onServiceConnected(service: AccessibilityService) {
        serviceRef = WeakReference(service)
    }

    /** 由宿主无障碍服务在 onDestroy 中调用。 */
    fun onServiceDestroyed() {
        serviceRef = null
    }

    /** 微信读屏服务连上。主服务还在时不抢走手势宿主。 */
    fun onReaderConnected(service: AccessibilityService) {
        readerRef = WeakReference(service)
        if (serviceRef?.get() == null) serviceRef = WeakReference(service)
    }

    fun onReaderDestroyed(service: AccessibilityService) {
        if (readerRef?.get() === service) readerRef = null
        if (serviceRef?.get() === service) serviceRef = null
    }

    fun getScreenReader(): AccessibilityService? = readerRef?.get()

    /** 由宿主在窗口变化时调用。 */
    fun onWindowChanged(packageName: String?, className: String?) {
        cachedPackageName = packageName
        cachedActivityName = className
    }

    override fun getService(): AccessibilityService? = serviceRef?.get() ?: readerRef?.get()

    override val foregroundPackage: String?
        get() {
            // Try live service first
            val service = serviceRef?.get()
            if (service != null) {
                try {
                    val root = service.rootInActiveWindow
                    if (root != null) {
                        val pkg = root.packageName?.toString()
                        runCatching { root.recycle() }
                        return pkg
                    }
                } catch (_: Exception) {}
            }
            return cachedPackageName
        }

    override val foregroundActivity: String?
        get() = cachedActivityName
}
