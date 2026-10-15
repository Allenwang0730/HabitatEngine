package com.ailun.habitat.workspace

import android.content.Context
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * 本地工作区：节点、Agent、脚本写入的结果（文字 / JSON / 图片 / 运行记录），只存本机。
 *
 * 目录：`<root>/index.json` + `<root>/entries/<id>.<ext>`。名字只是显示名，落盘一律用 id，不存在路径穿越。
 * 写盘先写临时文件再原子替换；所有读写在一把锁里串行（调用方自己放到 IO 线程）。
 * 超过总量上限时从最早的未置顶条目删起。
 */
class WorkspaceStore(
    private val root: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxTextBytes: Long = MAX_TEXT_BYTES,
    private val maxImageBytes: Long = MAX_IMAGE_BYTES,
    private val maxTotalBytes: Long = MAX_TOTAL_BYTES,
) {
    enum class Kind(val ext: String, val mime: String) {
        TEXT("txt", "text/plain"),
        JSON("json", "application/json"),
        IMAGE("png", "image/png"),
        /** 运行记录（Agent / 工作流），内容是 JSON，界面按时间线展示。 */
        RECORD("json", "application/json");

        companion object {
            fun parse(value: String?): Kind? = when (value?.trim()?.lowercase()) {
                null, "", "text", "txt", "markdown", "md" -> TEXT
                "json" -> JSON
                "image", "img", "png", "jpg", "jpeg" -> IMAGE
                else -> null
            }
        }
    }

    data class Entry(
        val id: String,
        val name: String,
        val kind: Kind,
        val mime: String,
        val size: Long,
        val createdAt: Long,
        val updatedAt: Long,
        /** `workflow:<名>` / `agent` / `mcp` / `user`。 */
        val source: String,
        val tags: List<String> = emptyList(),
        val pinned: Boolean = false,
    )

    class WorkspaceException(message: String) : Exception(message)

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val lock = Any()
    private val entriesDir = File(root, "entries")
    private val indexFile = File(root, "index.json")

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    /** 按更新时间倒序（置顶在前）。首次访问前调用过任一读写方法才有值；界面可先调 [list]。 */
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    private var loaded = false
    private val index = LinkedHashMap<String, Entry>()

    // ── 写 ──

    /**
     * 新建一条；[overwrite] 为 true 且已有同名同类条目时覆盖它（保留 id、创建时间、置顶）。
     * 返回写入后的条目。
     */
    fun write(
        name: String,
        kind: Kind,
        bytes: ByteArray,
        source: String,
        tags: List<String> = emptyList(),
        overwrite: Boolean = true,
        mime: String? = null,
    ): Entry = synchronized(lock) {
        ensureLoaded()
        checkSize(kind, bytes.size.toLong())
        val now = clock()
        val clean = cleanName(name, now)
        val existing = if (overwrite) index.values.firstOrNull { it.name == clean && it.kind == kind } else null
        val finalName = if (existing == null && !overwrite) uniqueName(clean, kind) else clean
        val id = existing?.id ?: newId()
        writeAtomic(fileOf(id, kind), bytes)
        val entry = Entry(
            id = id,
            name = finalName,
            kind = kind,
            mime = mime ?: kind.mime,
            size = bytes.size.toLong(),
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            source = source,
            tags = cleanTags(tags).ifEmpty { existing?.tags.orEmpty() },
            pinned = existing?.pinned ?: false,
        )
        index[id] = entry
        evict(keep = id)
        saveIndex()
        entry
    }

    fun writeText(name: String, text: String, source: String, kind: Kind = Kind.TEXT, tags: List<String> = emptyList(), overwrite: Boolean = true): Entry {
        require(kind != Kind.IMAGE) { "图片请用 write()" }
        if (kind == Kind.JSON || kind == Kind.RECORD) {
            if (runCatching { JsonParser.parseString(text) }.isFailure) throw WorkspaceException("内容不是合法 JSON")
        }
        return write(name, kind, text.toByteArray(Charsets.UTF_8), source, tags, overwrite)
    }

    /** 文本追加到同名条目末尾（不存在则新建）。JSON/图片不能追加。 */
    fun append(name: String, text: String, source: String, tags: List<String> = emptyList()): Entry = synchronized(lock) {
        ensureLoaded()
        val clean = cleanName(name, clock())
        val existing = index.values.firstOrNull { it.name == clean && it.kind == Kind.TEXT }
            ?: return@synchronized write(clean, Kind.TEXT, text.toByteArray(Charsets.UTF_8), source, tags)
        val file = fileOf(existing.id, Kind.TEXT)
        val old = if (file.isFile) file.readBytes() else ByteArray(0)
        val sep = if (old.isEmpty() || old.last() == '\n'.code.toByte()) "" else "\n"
        val bytes = old + (sep + text).toByteArray(Charsets.UTF_8)
        checkSize(Kind.TEXT, bytes.size.toLong())
        writeAtomic(file, bytes)
        val entry = existing.copy(
            size = bytes.size.toLong(),
            updatedAt = clock(),
            source = source,
            tags = (existing.tags + cleanTags(tags)).distinct(),
        )
        index[entry.id] = entry
        evict(keep = entry.id)
        saveIndex()
        entry
    }

    // ── 读 ──

    fun list(kind: Kind? = null, tag: String? = null, source: String? = null, limit: Int = Int.MAX_VALUE): List<Entry> = synchronized(lock) {
        ensureLoaded()
        sorted().asSequence()
            .filter { kind == null || it.kind == kind }
            .filter { tag == null || tag in it.tags }
            .filter { source == null || it.source == source || (source.endsWith(":") && it.source.startsWith(source)) }
            .take(limit.coerceAtLeast(0))
            .toList()
    }

    fun get(id: String): Entry? = synchronized(lock) {
        ensureLoaded()
        index[id]
    }

    /** 按 id 或名字找（名字重复时取最近更新的）。 */
    fun find(idOrName: String): Entry? = synchronized(lock) {
        ensureLoaded()
        index[idOrName] ?: sorted().firstOrNull { it.name == idOrName.trim() }
    }

    fun readBytes(id: String): ByteArray? = synchronized(lock) {
        ensureLoaded()
        val entry = index[id] ?: return null
        fileOf(entry.id, entry.kind).takeIf { it.isFile }?.readBytes()
    }

    fun readText(id: String): String? = readBytes(id)?.toString(Charsets.UTF_8)

    /** 列表预览：只读开头 [maxBytes] 字节（截断处的半个字符去掉）。 */
    fun readPreview(id: String, maxBytes: Int = 512): String? = synchronized(lock) {
        ensureLoaded()
        val entry = index[id] ?: return null
        if (entry.kind == Kind.IMAGE) return null
        val file = fileOf(entry.id, entry.kind).takeIf { it.isFile } ?: return null
        val buf = ByteArray(maxBytes)
        val n = file.inputStream().use { it.read(buf) }.coerceAtLeast(0)
        String(buf, 0, n, Charsets.UTF_8).trimEnd('�')
    }

    /** 条目文件的路径（分享 / 交给其它节点读）。 */
    fun fileFor(id: String): File? = synchronized(lock) {
        ensureLoaded()
        index[id]?.let { fileOf(it.id, it.kind) }?.takeIf { it.isFile }
    }

    fun totalBytes(): Long = synchronized(lock) {
        ensureLoaded()
        index.values.sumOf { it.size }
    }

    // ── 管理 ──

    fun delete(id: String): Boolean = synchronized(lock) {
        ensureLoaded()
        val entry = index.remove(id) ?: return false
        fileOf(entry.id, entry.kind).delete()
        saveIndex()
        true
    }

    fun rename(id: String, name: String): Entry? = update(id) { it.copy(name = cleanName(name, clock()), updatedAt = clock()) }

    fun setPinned(id: String, pinned: Boolean): Entry? = update(id) { it.copy(pinned = pinned) }

    private fun update(id: String, change: (Entry) -> Entry): Entry? = synchronized(lock) {
        ensureLoaded()
        val entry = index[id] ?: return null
        val next = change(entry)
        index[id] = next
        saveIndex()
        next
    }

    // ── 内部 ──

    private fun sorted(): List<Entry> =
        index.values.sortedWith(compareByDescending<Entry> { it.pinned }.thenByDescending { it.updatedAt })

    private fun fileOf(id: String, kind: Kind) = File(entriesDir, "$id.${kind.ext}")

    private fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

    private fun checkSize(kind: Kind, size: Long) {
        val max = if (kind == Kind.IMAGE) maxImageBytes else maxTextBytes
        if (size > max) throw WorkspaceException("内容太大：${size / 1024}KB，上限 ${max / 1024}KB")
    }

    private fun uniqueName(name: String, kind: Kind): String {
        if (index.values.none { it.name == name && it.kind == kind }) return name
        var i = 2
        while (index.values.any { it.name == "$name ($i)" && it.kind == kind }) i++
        return "$name ($i)"
    }

    /** 总量超限：删最早的未置顶条目（不删 [keep]）。 */
    private fun evict(keep: String) {
        var total = index.values.sumOf { it.size }
        if (total <= maxTotalBytes) return
        val victims = index.values.filter { !it.pinned && it.id != keep }.sortedBy { it.updatedAt }
        for (v in victims) {
            if (total <= maxTotalBytes) break
            index.remove(v.id)
            fileOf(v.id, v.kind).delete()
            total -= v.size
        }
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        entriesDir.mkdirs()
        val parsed = if (indexFile.isFile) runCatching { parseIndex(indexFile.readText()) }.getOrNull() else emptyList()
        if (parsed == null) {
            // 索引损坏：备份后从条目文件重建（名字丢失，用文件 id 代替）
            runCatching { indexFile.copyTo(File(root, "index.json.bak"), overwrite = true) }
        }
        val known = parsed.orEmpty().filter { fileOf(it.id, it.kind).isFile }
        known.forEach { index[it.id] = it }
        if (parsed == null) {
            entriesDir.listFiles().orEmpty().filter { it.isFile && it.nameWithoutExtension !in index }.forEach { f ->
                val kind = when (f.extension) {
                    "png" -> Kind.IMAGE
                    "json" -> Kind.JSON
                    else -> Kind.TEXT
                }
                index[f.nameWithoutExtension] = Entry(
                    f.nameWithoutExtension, "恢复的条目 ${f.nameWithoutExtension.take(6)}", kind, kind.mime,
                    f.length(), f.lastModified(), f.lastModified(), "user",
                )
            }
        }
        if (parsed == null || known.size != parsed.size) saveIndex() else publish()
    }

    /** 宽松解析：缺字段、未知类型的条目跳过；整体不是数组返回 null。 */
    private fun parseIndex(raw: String): List<Entry>? {
        if (raw.isBlank()) return emptyList()
        val type = object : TypeToken<List<Map<String, Any?>>>() {}.type
        val items: List<Map<String, Any?>> = gson.fromJson(raw, type) ?: return null
        return items.mapNotNull { m ->
            val id = m["id"] as? String ?: return@mapNotNull null
            if (!ID_PATTERN.matches(id)) return@mapNotNull null
            val kind = runCatching { Kind.valueOf(m["kind"] as String) }.getOrNull() ?: return@mapNotNull null
            Entry(
                id = id,
                name = (m["name"] as? String)?.takeIf { it.isNotBlank() } ?: id,
                kind = kind,
                mime = (m["mime"] as? String) ?: kind.mime,
                size = (m["size"] as? Number)?.toLong() ?: 0,
                createdAt = (m["createdAt"] as? Number)?.toLong() ?: 0,
                updatedAt = (m["updatedAt"] as? Number)?.toLong() ?: 0,
                source = (m["source"] as? String) ?: "user",
                tags = (m["tags"] as? List<*>)?.mapNotNull { it as? String }.orEmpty(),
                pinned = m["pinned"] == true,
            )
        }
    }

    private fun saveIndex() {
        writeAtomic(indexFile, gson.toJson(index.values.toList()).toByteArray(Charsets.UTF_8))
        publish()
    }

    private fun publish() {
        _entries.value = sorted()
    }

    private fun writeAtomic(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, ".${target.name}.tmp")
        tmp.writeBytes(bytes)
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        const val MAX_TEXT_BYTES = 1L * 1024 * 1024
        const val MAX_IMAGE_BYTES = 8L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 200L * 1024 * 1024
        const val MAX_NAME = 80

        private val ID_PATTERN = Regex("[a-z0-9]{1,32}")
        private val NAME_JUNK = Regex("[\\u0000-\\u001f\\u007f/\\\\]")

        /** 显示名（落盘不用它）：去掉控制字符和斜杠，空白合并，最长 [MAX_NAME]；清完为空时用时间命名。 */
        fun cleanName(name: String, now: Long): String {
            val cleaned = name.replace(NAME_JUNK, " ").replace(Regex("\\s+"), " ").trim().take(MAX_NAME)
            return cleaned.ifEmpty { "未命名 ${java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date(now))}" }
        }

        fun cleanTags(tags: List<String>): List<String> =
            tags.map { it.replace(NAME_JUNK, "").trim().take(24) }.filter { it.isNotEmpty() }.distinct().take(10)

        /** "a, b，c" → [a, b, c] */
        fun parseTags(value: String?): List<String> = cleanTags(value.orEmpty().split(',', '，', ' '))
    }
}

/** 应用级单例：`filesDir/workspace`。 */
object Workspace {
    @Volatile private var store: WorkspaceStore? = null

    fun get(context: Context): WorkspaceStore =
        store ?: synchronized(this) {
            store ?: WorkspaceStore(File(context.applicationContext.filesDir, "workspace")).also { store = it }
        }
}
