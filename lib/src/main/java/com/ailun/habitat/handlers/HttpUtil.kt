package com.ailun.habitat.handlers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.runInterruptible
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.ProtocolException

/** 网络类节点共用：IO 线程执行、协程取消时 disconnect 打断阻塞读、响应体大小上限。 */
internal object HttpUtil {

    const val DEFAULT_MAX_BODY_BYTES = 5 * 1024 * 1024

    /** 在 IO 线程执行 [block]；协程被取消（如节点超时）时 disconnect 连接，打断阻塞中的 socket 读写。 */
    suspend fun <T> withConnection(conn: HttpURLConnection, block: (HttpURLConnection) -> T): T {
        val handle = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause != null) runCatching { conn.disconnect() }
        }
        try {
            return runInterruptible(Dispatchers.IO) { block(conn) }
        } finally {
            handle?.dispose()
            runCatching { conn.disconnect() }
        }
    }

    /** 读取至多 [maxBytes] 字节，返回 (文本, 是否截断)。 */
    fun readLimited(input: InputStream?, maxBytes: Int = DEFAULT_MAX_BODY_BYTES): Pair<String, Boolean> {
        if (input == null) return "" to false
        input.use { ins ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var truncated = false
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                val room = maxBytes - out.size()
                if (n > room) {
                    out.write(buf, 0, room.coerceAtLeast(0))
                    truncated = true
                    break
                }
                out.write(buf, 0, n)
            }
            return out.toString(Charsets.UTF_8.name()) to truncated
        }
    }

    /**
     * 设置请求方法。HttpURLConnection 不支持 PATCH 等方法（抛 ProtocolException）：
     * 退化为 POST + `X-HTTP-Method-Override: <method>`（需服务端支持该约定）。
     * 不用反射改 method 字段：Android 上属隐藏 API 且 OkHttp 实现会忽略。
     * @return 实际生效方式说明（用于日志）
     */
    fun setMethod(conn: HttpURLConnection, method: String): String {
        try {
            conn.requestMethod = method
            return method
        } catch (_: ProtocolException) {
        }
        conn.requestMethod = "POST"
        conn.setRequestProperty("X-HTTP-Method-Override", method)
        return "POST+X-HTTP-Method-Override:$method"
    }
}
