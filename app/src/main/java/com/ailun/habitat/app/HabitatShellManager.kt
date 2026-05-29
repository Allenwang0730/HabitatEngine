package com.ailun.habitat.app

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import rikka.shizuku.Shizuku
import java.io.DataOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Habitat Shell 管理器，负责 Shizuku/Root 双通道 Shell 执行。
 */
object HabitatShellManager {
    private const val TAG = "HabitatShell"
    private const val BIND_TIMEOUT_MS = 3000L
    private const val MAX_RETRY = 3

    enum class ShellMode { AUTO, SHIZUKU, ROOT }

    @Volatile private var shellService: IHabitatShizukuService? = null
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun isShizukuActive(): Boolean = try {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) { false }

    fun isRootAvailable(): Boolean = try {
        val p = Runtime.getRuntime().exec("su")
        val os = DataOutputStream(p.outputStream)
        os.writeBytes("exit\n"); os.flush()
        p.waitFor() == 0
    } catch (_: Exception) { false }

    fun proactiveConnect(context: Context) {
        if (shellService?.asBinder()?.isBinderAlive == true) return
        scope.launch { if (isShizukuActive()) getService(context) }
    }

    suspend fun execShellCommand(context: Context, command: String, mode: ShellMode = ShellMode.AUTO): String =
        withContext(Dispatchers.IO) {
            val finalMode = if (mode == ShellMode.AUTO) {
                val prefs = context.getSharedPreferences("habitat_prefs", Context.MODE_PRIVATE)
                if (prefs.getString("default_shell_mode", "shizuku") == "root") ShellMode.ROOT else ShellMode.SHIZUKU
            } else mode

            if (finalMode == ShellMode.ROOT) execRoot(command)
            else execShizuku(context, command)
        }

    private fun execRoot(command: String): String = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        val out = p.inputStream.bufferedReader().readText().trim()
        val err = p.errorStream.bufferedReader().readText().trim()
        p.waitFor()
        out.ifEmpty { err }.ifEmpty { "OK" }
    } catch (e: Exception) { "Error: ${e.message}" }

    private suspend fun execShizuku(context: Context, command: String): String {
        val svc = getService(context) ?: return try {
            execRoot(command)
        } catch (_: Exception) {
            "Error: Shell service unavailable"
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
                        cont.resume(IHabitatShizukuService.Stub.asInterface(service))
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
                        cont.resumeWithException(e)
                    }
                }
            }
        }
}
