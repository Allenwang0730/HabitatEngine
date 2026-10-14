package com.ailun.habitat.app

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import rikka.shizuku.Shizuku
import java.io.DataOutputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Habitat Shell 管理器，负责 Shizuku/Root 双通道 Shell 执行。
 */
object HabitatShellManager {
    private const val TAG = "HabitatShell"
    private const val BIND_TIMEOUT_MS = 3000L
    private const val ROOT_CHECK_TIMEOUT_MS = 3000L
    private const val MAX_RETRY = 3

    enum class ShellMode { AUTO, SHIZUKU, ROOT }

    @Volatile private var shellService: IHabitatShizukuService? = null
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun isShizukuActive(): Boolean = try {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) { false }

    /** 检测 su 是否可用；su 可能弹授权框卡住，限时 [ROOT_CHECK_TIMEOUT_MS] 后强杀。阻塞调用，勿在主线程。 */
    fun isRootAvailable(): Boolean {
        var p: Process? = null
        return try {
            p = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(p.outputStream)
            os.writeBytes("exit\n"); os.flush()
            p.waitFor(ROOT_CHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS) && p.exitValue() == 0
        } catch (_: Exception) {
            false
        } finally {
            p?.let { if (it.isAlive) it.destroyForcibly() }
        }
    }

    fun proactiveConnect(context: Context) {
        if (shellService?.asBinder()?.isBinderAlive == true) return
        scope.launch { if (isShizukuActive()) getService(context) }
    }

    suspend fun execShellCommand(context: Context, command: String, mode: ShellMode = ShellMode.AUTO): String =
        withContext(Dispatchers.IO) {
            val rootPreferred = context.getSharedPreferences("habitat_prefs", Context.MODE_PRIVATE)
                .getString("default_shell_mode", "shizuku") == "root"
            val finalMode = if (mode == ShellMode.AUTO) {
                if (rootPreferred) ShellMode.ROOT else ShellMode.SHIZUKU
            } else mode

            if (finalMode == ShellMode.ROOT) execRoot(command)
            else execShizuku(context, command, allowRootFallback = rootPreferred)
        }

    private fun execRoot(command: String): String = try {
        ShellProcessRunner.run(arrayOf("su", "-c", command), ShellProcessRunner.DEFAULT_TIMEOUT_MS).trim()
    } catch (e: Exception) { "Error: ${e.message}" }

    /** Shizuku 不可用时只有用户在引导页选了 root 模式才回退 su，不静默提权。 */
    private suspend fun execShizuku(context: Context, command: String, allowRootFallback: Boolean): String {
        val svc = getService(context)
        if (svc == null) {
            if (allowRootFallback) {
                Log.w(TAG, "Shizuku 不可用，按设置回退 su")
                return execRoot(command)
            }
            Log.w(TAG, "Shizuku 不可用，未启用 root 模式，不回退 su")
            return "Error: Shizuku 不可用（未授权或未运行）"
        }
        return try { svc.exec(command) } catch (e: Exception) { "Error: ${e.message}" }
    }

    private suspend fun getService(context: Context): IHabitatShizukuService? {
        if (shellService?.asBinder()?.isBinderAlive == true) return shellService
        mutex.withLock {
            if (shellService?.asBinder()?.isBinderAlive == true) return shellService
            for (attempt in 1..MAX_RETRY) {
                try {
                    shellService = connect(context)
                    return shellService
                } catch (_: Exception) {
                    if (attempt < MAX_RETRY) delay(500L * attempt)
                }
            }
            return null
        }
    }

    private suspend fun connect(context: Context): IHabitatShizukuService =
        withTimeout(BIND_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val args = Shizuku.UserServiceArgs(
                    ComponentName(context, HabitatShizukuService::class.java)
                ).daemon(false).processNameSuffix(":habitat_shizuku").version(1)

                val conn = object : ServiceConnection {
                    override fun onServiceConnected(name: android.content.ComponentName?, service: IBinder?) {
                        if (cont.isActive) cont.resume(IHabitatShizukuService.Stub.asInterface(service))
                    }
                    override fun onServiceDisconnected(name: android.content.ComponentName?) {
                        shellService = null
                    }
                }
                cont.invokeOnCancellation { Shizuku.unbindUserService(args, conn, true) }
                scope.launch(Dispatchers.Main) {
                    try {
                        Shizuku.bindUserService(args, conn)
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }
                }
            }
        }
}
