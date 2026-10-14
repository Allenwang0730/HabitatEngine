package com.ailun.habitat.handlers

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.ailun.habitat.INodeHandler
import com.ailun.habitat.ShellQuote
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode
import com.ailun.habitat.api.IShellExecutor

/**
 * [ACTION_SCREEN_WAKE]：控制屏幕唤醒/睡眠/解锁。
 *
 * params：`action`（必填："wake"/"sleep"/"wake_and_unlock"），`password`（可选，用于解锁，仅允许字母和数字）
 */
class NodeScreenWakeHandler(
    private val shellExecutor: IShellExecutor? = null,
) : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        context.putVariable("screen_wake_success", false)
        val action = node.params?.get("action")?.toString()?.let { context.interpolate(it) }
            ?.trim()?.lowercase().orEmpty()
        if (action.isEmpty()) {
            Log.e(TAG, "Screen wake failed: 'action' parameter is empty")
            context.putVariable("screen_wake_success", false)
            return node.next
        }

        val password = node.params?.get("password")?.toString()?.let { context.interpolate(it) }?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (password != null && !PASSWORD_REGEX.matches(password)) {
            context.log("ScreenWake FAILED: 解锁密码仅允许字母和数字")
            return node.next
        }

        var success = false

        when (action) {
            "wake" -> {
                success = wakeScreen(context)
            }
            "sleep" -> {
                success = sleepScreen(context)
            }
            "wake_and_unlock" -> {
                success = wakeAndUnlock(context, password)
            }
            else -> {
                Log.e(TAG, "Screen wake failed: unknown action '$action'")
                context.putVariable("screen_wake_success", false)
                return node.next
            }
        }

        context.putVariable("screen_wake_success", success)
        if (success) {
            Log.i(TAG, "Screen action '$action' succeeded")
        } else {
            Log.e(TAG, "Screen action '$action' failed")
        }

        return node.next
    }

    private suspend fun wakeScreen(context: WorkflowContext): Boolean {
        // Try shell keyevents first
        if (shellExecutor != null) {
            try {
                // Try KEYCODE_WAKEUP (224) first
                var output = shellExecutor.exec("input keyevent 224", asRoot = false)
                if (!output.contains("Error", ignoreCase = true)) {
                    Log.i(TAG, "Screen woken via KEYCODE_WAKEUP (224)")
                    return true
                }
                // Fallback: power key (26)
                output = shellExecutor.exec("input keyevent 26", asRoot = false)
                if (!output.contains("Error", ignoreCase = true)) {
                    Log.i(TAG, "Screen woken via KEYCODE_POWER (26)")
                    return true
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Shell wake keyevent failed: ${e.message}")
            }
        }

        // Alternative: PowerManager wakeLock
        try {
            val powerManager = context.appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (powerManager != null) {
                @Suppress("DEPRECATION")
                val wakeLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                            PowerManager.ACQUIRE_CAUSES_WAKEUP or
                            PowerManager.ON_AFTER_RELEASE,
                    "$TAG::wakeLock"
                )
                wakeLock.acquire(1000L)
                wakeLock.release()
                Log.i(TAG, "Screen woken via PowerManager wakeLock")
                return true
            }
        } catch (e: Exception) {
            Log.w(TAG, "PowerManager wakeLock failed: ${e.message}")
        }

        return false
    }

    private suspend fun sleepScreen(context: WorkflowContext): Boolean {
        if (shellExecutor != null) {
            try {
                // KEYCODE_SLEEP(223)：仅息屏；POWER(26) 是切换键，屏幕已灭时会误亮屏
                val output = shellExecutor.exec("input keyevent 223", asRoot = false)
                if (!output.contains("Error", ignoreCase = true)) {
                    Log.i(TAG, "Screen put to sleep via KEYCODE_SLEEP (223)")
                    return true
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Shell sleep keyevent failed: ${e.message}")
            }
        }
        return false
    }

    private suspend fun wakeAndUnlock(context: WorkflowContext, password: String?): Boolean {
        // Step 1: wake the screen
        if (!wakeScreen(context)) {
            Log.e(TAG, "wake_and_unlock: failed to wake screen")
            return false
        }

        if (shellExecutor == null) {
            Log.w(TAG, "wake_and_unlock: no shell executor, cannot perform unlock")
            return true // screen is at least awake
        }

        try {
            // Step 2: unlock via KEYCODE_MENU (82) - swipes up on lock screen
            shellExecutor.exec("input keyevent 82", asRoot = false)

            // Step 3: if password is provided, enter it
            if (password != null) {
                // Wait briefly for the unlock screen to appear
                kotlinx.coroutines.delay(500)
                shellExecutor.exec("input text ${ShellQuote.quote(password)}", asRoot = false)
                kotlinx.coroutines.delay(200)
                // Press Enter to confirm
                shellExecutor.exec("input keyevent 66", asRoot = false)
            }

            Log.i(TAG, "Screen woken and unlocked successfully")
            return true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Shell unlock sequence failed: ${e.message}")
            return false
        }
    }

    companion object {
        private const val TAG = "HabitatScreenWake"
        private val PASSWORD_REGEX = Regex("^[A-Za-z0-9]{1,64}$")
    }
}
