package com.ailun.habitat.handlers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.util.concurrent.TimeUnit

/** 无 Shizuku 时的本地进程兜底：IO 线程执行、合并 stderr、超时强杀、可被协程取消打断。 */
internal object LocalShell {

    private const val MAX_OUTPUT_CHARS = 1_000_000

    /** 返回合并后的输出；超时返回 "Error: timeout ..."，启动失败返回 "Error: ..."。 */
    suspend fun run(argv: Array<String>, timeoutMs: Long): String = runInterruptible(Dispatchers.IO) {
        var process: Process? = null
        try {
            val proc = ProcessBuilder(*argv).redirectErrorStream(true).start()
            process = proc
            // 独立线程读输出，避免管道写满导致子进程阻塞
            val sb = StringBuilder()
            val reader = Thread {
                runCatching {
                    proc.inputStream.bufferedReader().use { r ->
                        val buf = CharArray(4096)
                        while (true) {
                            val n = r.read(buf)
                            if (n < 0) break
                            synchronized(sb) { if (sb.length < MAX_OUTPUT_CHARS) sb.append(buf, 0, n) }
                        }
                    }
                }
            }.apply { isDaemon = true; start() }
            if (!proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                proc.destroyForcibly()
                reader.join(500)
                return@runInterruptible "Error: timeout after ${timeoutMs}ms"
            }
            reader.join(2000)
            synchronized(sb) { sb.toString() }
        } catch (e: InterruptedException) {
            process?.destroyForcibly()
            throw e
        } catch (e: Exception) {
            process?.destroyForcibly()
            "Error: ${e.message}"
        }
    }

    suspend fun sh(command: String, timeoutMs: Long): String = run(arrayOf("sh", "-c", command), timeoutMs)
}
