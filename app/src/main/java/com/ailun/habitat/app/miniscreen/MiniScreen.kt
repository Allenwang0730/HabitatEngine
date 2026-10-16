package com.ailun.habitat.app.miniscreen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.ailun.habitat.OverlayGuard
import com.ailun.habitat.app.HabitatLogger
import com.ailun.habitat.app.ServiceLifecycleOwner
import com.ailun.habitat.app.bridge.AppAccessibilityProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * 悬浮小屏：状态（[MiniScreenModel]）+ 窗口。
 *
 * 窗口优先挂在无障碍服务上（TYPE_ACCESSIBILITY_OVERLAY：不需要悬浮窗权限，系统视为可信窗口），
 * 无障碍没开时退回 TYPE_APPLICATION_OVERLAY。派发手势/截图前经 [OverlayGuard] 让开。
 * 所有窗口和位图操作都在主线程。
 */
object MiniScreen {
    private const val TAG = "MiniScreen"
    private const val PREFS = "mini_screen"

    private val _state = MutableStateFlow(MiniScreenState())
    val state: StateFlow<MiniScreenState> = _state.asStateFlow()

    /** 图片 id → 位图（只在主线程读写；Compose 订阅它重组）。 */
    internal val images = mutableStateMapOf<String, Bitmap>()

    data class Result(val ok: Boolean, val shown: Boolean, val error: String? = null)

    private class Window(
        val view: ComposeView,
        val wm: WindowManager,
        val params: WindowManager.LayoutParams,
        val owner: ServiceLifecycleOwner,
        val viaAccessibility: Boolean,
    )

    private lateinit var app: Context
    private var window: Window? = null
    private val main = Handler(Looper.getMainLooper())
    private var hideTask: Runnable? = null
    private var dragStartX = 0
    private var dragStartY = 0

    /** 执行一条命令。[image] 是 [MiniCommand.Write.image] 对应的位图。 */
    suspend fun apply(context: Context, command: MiniCommand, image: Bitmap? = null, autoHideMs: Long = 0): Result =
        withContext(Dispatchers.Main) { applyOnMain(context, command, image, autoHideMs) }

    private fun applyOnMain(context: Context, command: MiniCommand, image: Bitmap?, autoHideMs: Long): Result {
        ensureInit(context)
        val imageId = (command as? MiniCommand.Write)?.image?.imageId
        if (image != null && imageId != null) images[imageId] = image
        val before = _state.value
        val result = MiniScreenModel.reduce(before, command, System.currentTimeMillis())
        result.droppedImages.forEach { images.remove(it) }
        _state.value = result.state
        val state = result.state

        if (!state.visible) {
            cancelHide()
            detach()
            return Result(ok = true, shown = false)
        }
        val error = attach(positionChanged = state.position != before.position || !before.visible)
        if (error != null) {
            _state.value = state.copy(visible = false)
            return Result(ok = false, shown = false, error = error)
        }
        if (command is MiniCommand.Write || command is MiniCommand.Progress) scheduleHide(autoHideMs)
        return Result(ok = true, shown = true)
    }

    /** 给 MCP / 评测读回当前内容。 */
    fun statusMap(): Map<String, Any?> {
        val s = _state.value
        return mapOf(
            "visible" to s.visible,
            "collapsed" to s.collapsed,
            "title" to s.title,
            "source" to s.source,
            "text" to s.blocks.filterIsInstance<MiniBlock.Text>().joinToString("\n") { it.text },
            "images" to s.blocks.count { it is MiniBlock.Image },
            "progress" to s.progress,
        )
    }

    // ── 用户操作（来自小屏界面，主线程）──

    internal fun onClose() {
        applyOnMain(app, MiniCommand.Dismiss, null, 0)
    }

    internal fun onToggleCollapsed() {
        _state.value = MiniScreenModel.reduce(_state.value, MiniCommand.ToggleCollapsed, System.currentTimeMillis()).state
        window?.let { w ->
            w.params.width = panelWidth()
            clampInto(w)
            update(w)
        }
    }

    internal fun onDragStart() {
        val w = window ?: return
        dragStartX = w.params.x
        dragStartY = w.params.y
    }

    internal fun onDrag(dx: Int, dy: Int) {
        val w = window ?: return
        w.params.x = dragStartX + dx
        w.params.y = dragStartY + dy
        clampInto(w)
        update(w)
    }

    internal fun onDragEnd() {
        val w = window ?: return
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("x", w.params.x).putInt("y", w.params.y).apply()
    }

    // ── 窗口 ──

    /** 无障碍服务 [service] 销毁：挂在它上面的窗口随之失效，立刻放掉（别让静态引用拖住服务）。 */
    fun onAccessibilityGone(service: Any?) {
        main.post {
            val w = window ?: return@post
            if (w.viaAccessibility && (service == null || w.view.context === service)) {
                remove(w)
                _state.value = _state.value.copy(visible = false)
            }
        }
    }

    private fun ensureInit(context: Context) {
        if (!::app.isInitialized) {
            app = context.applicationContext
            OverlayGuard.register(guard)
        }
    }

    private fun attach(positionChanged: Boolean): String? {
        window?.let { w ->
            // 无障碍服务断了，挂在它上面的窗口已失效：重建
            if (w.viaAccessibility && AppAccessibilityProvider.getService() !== w.view.context) remove(w)
            else {
                if (positionChanged && !userPlaced()) placeDefault(w)
                w.params.width = panelWidth()
                update(w)
                return null
            }
        }
        val service = AppAccessibilityProvider.getService()
        val host: Context
        val type: Int
        when {
            service != null -> {
                host = service
                type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            }
            Settings.canDrawOverlays(app) -> {
                host = app
                type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            }
            else -> return "没有显示权限：请开启 Habitat 无障碍服务，或允许 Habitat 显示在其他应用上层"
        }
        val wm = host.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            panelWidth(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }

        val owner = ServiceLifecycleOwner().also { it.onCreate() }
        val view = ComposeView(host).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setContent { MiniScreenContent(maxHeightPx = (screenHeight() * 0.4f).toInt()) }
        }
        val w = Window(view, wm, params, owner, viaAccessibility = service != null)
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (userPlaced()) {
            params.x = prefs.getInt("x", 0)
            params.y = prefs.getInt("y", 0)
        } else {
            placeDefault(w)
        }
        clampInto(w)
        return try {
            wm.addView(view, params)
            window = w
            null
        } catch (e: Exception) {
            HabitatLogger.e(TAG, "小屏添加失败", e)
            owner.onDestroy()
            "小屏添加失败：${e.message}"
        }
    }

    private fun detach() {
        window?.let(::remove)
    }

    private fun remove(w: Window) {
        if (window === w) window = null
        try { w.wm.removeView(w.view) } catch (_: Exception) {}
        w.owner.onDestroy()
    }

    private fun update(w: Window) {
        try { w.wm.updateViewLayout(w.view, w.params) } catch (_: Exception) {}
    }

    private fun scheduleHide(ms: Long) {
        cancelHide()
        if (ms <= 0) return
        val task = Runnable { applyOnMain(app, MiniCommand.Hide, null, 0) }
        hideTask = task
        main.postDelayed(task, ms)
    }

    private fun cancelHide() {
        hideTask?.let(main::removeCallbacks)
        hideTask = null
    }

    private fun userPlaced(): Boolean = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains("y")

    private fun placeDefault(w: Window) {
        val width = panelWidth().takeIf { it > 0 } ?: dp(160)
        w.params.x = (screenWidth() - width) / 2
        val estimate = w.view.height.takeIf { it > 0 } ?: (screenHeight() * 0.25f).toInt()
        w.params.y = when (_state.value.position) {
            MiniPosition.TOP -> statusBarHeight() + dp(8)
            MiniPosition.CENTER -> (screenHeight() - estimate) / 2
            MiniPosition.BOTTOM -> screenHeight() - estimate - dp(96)
        }
    }

    private fun clampInto(w: Window) {
        val width = if (w.params.width > 0) w.params.width else w.view.width.coerceAtLeast(dp(120))
        val height = w.view.height.coerceAtLeast(dp(48))
        w.params.x = w.params.x.coerceIn(0, (screenWidth() - width).coerceAtLeast(0))
        w.params.y = w.params.y.coerceIn(statusBarHeight(), (screenHeight() - height).coerceAtLeast(statusBarHeight()))
    }

    /** 展开时约 72% 屏宽（最宽 420dp）；折叠成胶囊时按内容宽。 */
    private fun panelWidth(): Int =
        if (_state.value.collapsed) WindowManager.LayoutParams.WRAP_CONTENT
        else (screenWidth() * 0.72f).toInt().coerceAtMost(dp(420))

    private fun screenWidth(): Int = app.resources.displayMetrics.widthPixels
    private fun screenHeight(): Int = app.resources.displayMetrics.heightPixels
    private fun dp(v: Int): Int = (app.resources.displayMetrics.density * v).toInt()

    private fun statusBarHeight(): Int {
        val id = app.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) app.resources.getDimensionPixelSize(id) else dp(24)
    }

    // ── 手势/截图避让 ──

    private val guard = object : OverlayGuard.Hook {
        override fun yieldFor(area: OverlayGuard.Box): (() -> Unit)? {
            val w = window ?: return null
            if (!_state.value.visible || w.view.height <= 0) return null
            val p = w.params
            val panel = OverlayGuard.Box(p.x, p.y, p.x + w.view.width, p.y + w.view.height)
            if (!panel.intersects(area.inflate(dp(8)))) return null
            val y = MiniScreenModel.dodge(p.y, w.view.height, area.top, area.bottom, screenHeight(), statusBarHeight(), dp(8))
            if (y != null) {
                // 挪开后就留在新位置（不保存为用户位置），避免连续点击时来回闪
                p.y = y
                update(w)
                return {}
            }
            return hideTemporarily(w)
        }

        override fun hideForCapture(): (() -> Unit)? {
            val w = window ?: return null
            if (!_state.value.visible) return null
            return hideTemporarily(w)
        }
    }

    /** 透明 + 不接收触摸（窗口 alpha 也置 0：Android 12+ 的遮挡判定看窗口 alpha）。 */
    private fun hideTemporarily(w: Window): () -> Unit {
        val p = w.params
        val alpha = p.alpha
        val flags = p.flags
        p.alpha = 0f
        p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        w.view.alpha = 0f
        update(w)
        return {
            if (window === w) {
                p.alpha = alpha
                p.flags = flags
                w.view.alpha = 1f
                update(w)
            }
        }
    }
}
