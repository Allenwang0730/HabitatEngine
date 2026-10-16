package com.ailun.habitat.app.miniscreen

/**
 * 悬浮小屏的状态与状态变化（纯 Kotlin，可在 JVM 单测）。
 *
 * - 内容块：文字或图片（图片只存 id，位图由 [MiniScreen] 在主线程持有）。
 * - 同一时刻只显示一个来源的内容：另一个来源追加时等同替换，标签永远对应正文。
 * - 用户点了关闭：同一次运行（[MiniScreenState.runKey]）后续的写入只更新内容、不再弹出；
 *   换一次运行或过了 [DISMISS_TTL_MS] 恢复。
 */
enum class MiniPosition { TOP, CENTER, BOTTOM;
    companion object {
        fun parse(value: String?): MiniPosition = when (value?.trim()?.lowercase()) {
            "center", "middle", "中", "中间" -> CENTER
            "bottom", "下", "底部" -> BOTTOM
            else -> TOP
        }
    }
}

sealed class MiniBlock {
    abstract val id: Long

    data class Text(override val id: Long, val text: String) : MiniBlock()
    data class Image(override val id: Long, val imageId: String, val width: Int, val height: Int) : MiniBlock()
}

data class MiniScreenState(
    val visible: Boolean = false,
    val collapsed: Boolean = false,
    val title: String = "",
    /** 显示用的来源：`workflow:<名>` / `agent` / `mcp`。 */
    val source: String = "",
    val blocks: List<MiniBlock> = emptyList(),
    /** 0–100；null 不显示进度条。 */
    val progress: Int? = null,
    val position: MiniPosition = MiniPosition.TOP,
    val runKey: String = "",
    /** 被用户关闭的那次运行；null 表示没关过。 */
    val dismissedRunKey: String? = null,
    val dismissedAt: Long = 0,
    val updatedAt: Long = 0,
    /** 递增的块 id 序号。 */
    val nextId: Long = 1,
) {
    val isEmpty: Boolean get() = title.isEmpty() && blocks.isEmpty() && progress == null
}

sealed class MiniCommand {
    /** [append]=false 替换正文；true 在末尾追加（来源不同时仍按替换处理）。 */
    data class Write(
        val append: Boolean,
        val source: String,
        val runKey: String,
        val title: String? = null,
        val text: String? = null,
        val image: MiniImage? = null,
        val progress: Int? = null,
        val position: MiniPosition? = null,
    ) : MiniCommand()

    /** 只更新进度（和标题）。 */
    data class Progress(val source: String, val runKey: String, val progress: Int, val title: String? = null) : MiniCommand()

    /** 隐藏但保留内容：之后再写会带着原内容重新出现。 */
    object Hide : MiniCommand()

    /** 隐藏并清空。 */
    object Clear : MiniCommand()

    /** 用户点了关闭。 */
    object Dismiss : MiniCommand()

    object ToggleCollapsed : MiniCommand()
}

data class MiniImage(val imageId: String, val width: Int, val height: Int)

/** [reduce] 的结果：新状态 + 被挤掉的图片（调用方回收位图）。 */
data class MiniReduceResult(val state: MiniScreenState, val droppedImages: List<String>, val suppressed: Boolean)

object MiniScreenModel {
    const val MAX_TEXT = 4000
    const val MAX_TITLE = 60
    const val MAX_BLOCKS = 20
    const val DISMISS_TTL_MS = 10 * 60_000L

    fun reduce(state: MiniScreenState, command: MiniCommand, now: Long): MiniReduceResult = when (command) {
        is MiniCommand.Write -> write(state, command, now)
        is MiniCommand.Progress -> {
            val sameSource = state.source == command.source && !state.isEmpty
            val base = if (sameSource) state else state.copy(blocks = emptyList(), title = "")
            val dropped = if (sameSource) emptyList() else imagesOf(state.blocks)
            val next = base.copy(
                source = command.source,
                runKey = command.runKey,
                title = command.title?.let(::cleanTitle) ?: base.title,
                progress = command.progress.coerceIn(0, 100),
                updatedAt = now,
            )
            show(next, dropped, now)
        }
        MiniCommand.Hide -> MiniReduceResult(state.copy(visible = false, updatedAt = now), emptyList(), false)
        MiniCommand.Clear -> MiniReduceResult(
            MiniScreenState(nextId = state.nextId, position = state.position, dismissedRunKey = state.dismissedRunKey, dismissedAt = state.dismissedAt, updatedAt = now),
            imagesOf(state.blocks),
            false,
        )
        MiniCommand.Dismiss -> MiniReduceResult(
            state.copy(visible = false, dismissedRunKey = state.runKey, dismissedAt = now, updatedAt = now),
            emptyList(),
            false,
        )
        MiniCommand.ToggleCollapsed -> MiniReduceResult(state.copy(collapsed = !state.collapsed), emptyList(), false)
    }

    private fun write(state: MiniScreenState, cmd: MiniCommand.Write, now: Long): MiniReduceResult {
        val append = cmd.append && state.source == cmd.source && !state.isEmpty
        var nextId = state.nextId
        val added = buildList {
            cmd.text?.takeIf { it.isNotEmpty() }?.let { add(MiniBlock.Text(nextId++, clip(it, MAX_TEXT))) }
            cmd.image?.let { add(MiniBlock.Image(nextId++, it.imageId, it.width, it.height)) }
        }
        val combined = if (append) state.blocks + added else added
        val kept = combined.takeLast(MAX_BLOCKS)
        val keptIds = kept.map { it.id }.toSet()
        val dropped = imagesOf((state.blocks + added).filter { it.id !in keptIds })
        val next = state.copy(
            source = cmd.source,
            runKey = cmd.runKey,
            title = cmd.title?.let(::cleanTitle) ?: if (append) state.title else "",
            blocks = kept,
            progress = cmd.progress?.coerceIn(0, 100) ?: if (append) state.progress else null,
            position = cmd.position ?: state.position,
            nextId = nextId,
            updatedAt = now,
        )
        return show(next, dropped, now)
    }

    /** 被用户关闭过的同一次运行：只更新内容，不弹出。 */
    private fun show(next: MiniScreenState, dropped: List<String>, now: Long): MiniReduceResult {
        val dismissed = next.dismissedRunKey
        val suppressed = dismissed != null && dismissed == next.runKey && now - next.dismissedAt < DISMISS_TTL_MS
        val state = if (suppressed) next.copy(visible = false)
        else next.copy(visible = true, dismissedRunKey = null, dismissedAt = 0)
        return MiniReduceResult(state, dropped, suppressed)
    }

    private fun imagesOf(blocks: List<MiniBlock>): List<String> = blocks.filterIsInstance<MiniBlock.Image>().map { it.imageId }

    fun cleanTitle(title: String): String = clip(title.replace(Regex("\\s+"), " ").trim(), MAX_TITLE)

    fun clip(text: String, max: Int): String = if (text.length <= max) text else text.take(max - 1) + "…"

    /** 来源的显示名。 */
    fun sourceLabel(source: String): String = when {
        source == "agent" -> "Agent"
        source == "agent:remote" -> "Agent · 远程"
        source == "mcp" -> "MCP"
        source.startsWith("workflow:") -> source.removePrefix("workflow:").ifBlank { "工作流" }
        else -> "Habitat"
    }

    /** 远程写入（MCP、远程发起的 Agent）：界面用警示色标出，不能和本机内容混淆。 */
    fun isRemote(source: String): Boolean = source == "mcp" || source.endsWith(":remote")

    /**
     * 把 [panel] 从 [area] 挪开：返回新的左上角 y（只上下挪，x 不变）；放不下返回 null。
     * 候选：状态栏下方、屏幕底部；取离原位置最近且不与 [area] 相交的。
     */
    fun dodge(panelTop: Int, panelHeight: Int, areaTop: Int, areaBottom: Int, screenHeight: Int, topInset: Int, margin: Int): Int? {
        val candidates = listOf(topInset + margin, screenHeight - panelHeight - margin)
            .filter { it >= topInset && it + panelHeight <= screenHeight }
            .filter { y -> y + panelHeight <= areaTop - margin || y >= areaBottom + margin }
        return candidates.minByOrNull { kotlin.math.abs(it - panelTop) }
    }
}
