package com.ailun.habitat.handlers

import android.content.Context
import java.io.File

/**
 * 文件类节点（FILE_OPERATION / ZIP / CRYPTO / SQLITE）的路径守卫。
 *
 * 规则（均基于 canonicalPath，跟随符号链接，/sdcard → /storage/emulated/0）：
 * - 本应用私有目录（dataDir、filesDir、cacheDir、externalFilesDir/externalCacheDir 所在的 Android/data/<本包>）：全部允许
 * - 共享存储 /storage/<卷>/...：允许；但卷根本身（/storage/emulated/0）不可写/删；
 *   一级公共目录本身（DCIM、Download、Pictures…）不可删；其它应用的 Android/data、Android/obb 拒绝
 * - 其它（"/"、/system、/vendor、/proc、/dev、/data 下非本应用目录、/mnt、/apex 等）：拒绝
 */
object FilePathGuard {

    enum class Access { READ, WRITE, DELETE }

    private val PUBLIC_TOP_DIRS = setOf(
        "alarms", "android", "audiobooks", "dcim", "documents", "download", "movies", "music",
        "notifications", "pictures", "podcasts", "recordings", "ringtones", "screenshots",
    )

    /** 允许 → null；拒绝 → 原因。 */
    fun check(context: Context, path: String, access: Access): String? {
        if (path.isBlank()) return "路径为空"
        val canonical = try {
            File(path).canonicalPath
        } catch (e: Exception) {
            return "路径无法解析: ${e.message}"
        }
        if (isAppPrivate(context, canonical)) return null

        val volumeRoot = storageVolumeRoot(canonical) ?: return "拒绝访问受保护路径: $canonical"
        if (canonical == volumeRoot) {
            return if (access == Access.READ) null else "拒绝修改存储根目录: $canonical"
        }
        val rel = canonical.removePrefix("$volumeRoot/")
        val segments = rel.split('/')
        if (segments.size >= 2 && segments[0].equals("Android", true) &&
            (segments[1].equals("data", true) || segments[1].equals("obb", true))
        ) {
            val own = segments.size >= 3 && segments[2] == context.packageName
            if (!own) return "拒绝访问其它应用的私有目录: $canonical"
        }
        if (access == Access.DELETE && segments.size == 1 && segments[0].lowercase() in PUBLIC_TOP_DIRS) {
            return "拒绝删除公共目录: $canonical"
        }
        return null
    }

    /** 存储根或一级目录（如 /storage/emulated/0、/storage/emulated/0/DCIM）——删除目录时禁止。 */
    fun isStorageRootOrTopLevel(path: String): Boolean {
        val canonical = runCatching { File(path).canonicalPath }.getOrNull() ?: return true
        val root = storageVolumeRoot(canonical) ?: return false
        if (canonical == root) return true
        return !canonical.removePrefix("$root/").contains('/')
    }

    private fun isAppPrivate(context: Context, canonical: String): Boolean {
        val roots = buildList {
            runCatching { add(context.applicationInfo.dataDir) }
            runCatching { add(context.filesDir.parentFile?.path) }
            runCatching { add(context.cacheDir.path) }
            runCatching { add(context.getExternalFilesDir(null)?.parentFile?.path) }
            runCatching { add(context.externalCacheDir?.parentFile?.path) }
        }.filterNotNull().mapNotNull { runCatching { File(it).canonicalPath }.getOrNull() }
        return roots.any { canonical == it || canonical.startsWith("$it/") }
    }

    /** /storage/emulated/<n>/... → /storage/emulated/<n>；/storage/XXXX-XXXX/... → /storage/XXXX-XXXX；其它 → null。 */
    private fun storageVolumeRoot(canonical: String): String? {
        // 符号链接未完全解析时的主存储别名
        for (alias in listOf("/sdcard", "/mnt/sdcard", "/storage/self/primary")) {
            if (canonical == alias || canonical.startsWith("$alias/")) return alias
        }
        if (!canonical.startsWith("/storage/")) return null
        val parts = canonical.removePrefix("/storage/").split('/')
        return when {
            parts[0] == "emulated" -> if (parts.size >= 2 && parts[1].all { it.isDigit() } && parts[1].isNotEmpty())
                "/storage/emulated/${parts[1]}" else null
            parts[0] == "self" || parts[0].isEmpty() -> null
            else -> "/storage/${parts[0]}"
        }
    }
}
