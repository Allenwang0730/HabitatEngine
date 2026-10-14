package com.ailun.habitat.app

import android.content.Context
import android.os.IBinder
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Habitat 的 Shizuku 用户服务，运行在 Shizuku 进程中，拥有 Shell 级权限。
 */
class HabitatShizukuService(private val context: Context) : IHabitatShizukuService.Stub() {

    override fun destroy() {
        System.exit(0)
    }

    override fun exec(command: String?): String {
        if (command.isNullOrBlank()) return "Error: Empty command"
        return try {
            ShellProcessRunner.run(arrayOf("sh", "-c", command), ShellProcessRunner.DEFAULT_TIMEOUT_MS)
        } catch (e: SecurityException) {
            "Error: Permission denied - ${e.message}"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    override fun exit() {
        try { Thread.sleep(100) } catch (_: InterruptedException) {}
        System.exit(0)
    }

    override fun asBinder(): IBinder = super.asBinder()
}

/**
 * 进程执行：stdout/stderr 各自独立线程读取（避免管道写满互相死锁），限时等待，超时强杀。
 * 失败统一以 `Error:` 开头（NodeShellHandler 据此判断 shell_success）。
 */
internal object ShellProcessRunner {
    const val DEFAULT_TIMEOUT_MS = 120_000L
    /** 限制输出长度，避免超出 Binder 事务上限（约 1MB）。 */
    private const val MAX_OUTPUT_CHARS = 256 * 1024

    fun run(cmd: Array<String>, timeoutMs: Long): String {
        val process = ProcessBuilder(*cmd).redirectErrorStream(false).start()
        return try {
            runCatching { process.outputStream.close() }
            val out = StringBuilder()
            val err = StringBuilder()
            val outReader = drain(process.inputStream, out)
            val errReader = drain(process.errorStream, err)
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                outReader.join(500)
                errReader.join(500)
                return "Error: timeout after ${timeoutMs}ms"
            }
            outReader.join(2000)
            errReader.join(2000)
            val exitCode = process.exitValue()
            val stdout = synchronized(out) { out.toString() }
            val stderr = synchronized(err) { err.toString() }.trim()
            when {
                exitCode == 0 -> stdout.ifEmpty { "OK" }
                stderr.isNotEmpty() -> "Error: (code $exitCode) $stderr"
                else -> "Error: (code $exitCode)"
            }
        } catch (e: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            "Error: interrupted"
        }
    }

    private fun drain(stream: InputStream, sink: StringBuilder): Thread = Thread {
        runCatching {
            stream.bufferedReader().use { r ->
                val buf = CharArray(4096)
                while (true) {
                    val n = r.read(buf)
                    if (n < 0) break
                    synchronized(sink) { if (sink.length < MAX_OUTPUT_CHARS) sink.append(buf, 0, n) }
                }
            }
        }
    }.apply { isDaemon = true; start() }
}
