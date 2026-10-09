package com.ailun.habitat.app.agent

/**
 * 本次任务的导航栈 + 页面图。代码维护，模型只读。
 *
 * - 栈：点进新页面压栈，返回到栈里已有的页面就把上面的弹掉。模型因此知道"我在 设置 > 系统管理与升级，第 2 层"。
 * - 页面图：每个页面上试过哪些入口、结果如何（进了哪一页 / 无变化 / 失败 / 进去过又退回来了），以及这一页是否已经滚到底。
 *   真机上模型误进子菜单后反复进出、或在已经到底的列表里一直往下滑，都是因为没有这份记忆。
 *
 * 页面身份 = 包名 + Activity + 页面标题（vivo 设置这类单 Activity 多 Fragment 的应用靠标题区分）。
 */
class NavigationState {
    data class Page(val key: String, val title: String)

    private val stack = ArrayList<Page>()
    private val tried = linkedMapOf<String, LinkedHashMap<String, String>>()
    private val titles = hashMapOf<String, String>()
    private val bottom = hashSetOf<String>()

    val depth: Int get() = stack.size
    val current: Page? get() = stack.lastOrNull()

    fun enter(observation: ScreenObservation, activity: String?) {
        stack.clear()
        stack.add(pageOf(observation, activity))
    }

    /** 记一次动作的结果并更新栈。[after] 是动作之后的读屏。 */
    fun record(after: ScreenObservation, activity: String?, operation: String, label: String, changed: Boolean, ok: Boolean) {
        val before = stack.lastOrNull() ?: run { enter(after, activity); return }
        val now = pageOf(after, activity)
        if (operation !in UNTRACKED_OPS) {
            val result = when {
                !ok -> RESULT_FAILED
                now.key == before.key -> if (changed) RESULT_SAME_PAGE else RESULT_NO_CHANGE
                else -> "→ ${now.title}"
            }
            triedAt(before.key)[label.take(MAX_LABEL)] = result
        }
        if (operation == "SCROLL_DOWN" && ok && !changed) bottom += before.key
        if (now.key == before.key) return
        when {
            // 滚动时折叠标题栏会让标题变化，不算换页
            operation.startsWith("SCROLL_") -> stack[stack.size - 1] = now.also { titles[it.key] = it.title }
            operation == "HOME" -> {
                stack.clear()
                stack.add(now)
            }
            else -> {
                val existing = stack.indexOfFirst { it.key == now.key }
                if (existing >= 0) {
                    while (stack.size > existing + 1) {
                        val popped = stack.removeAt(stack.size - 1)
                        val parent = stack[stack.size - 1]
                        val into = "→ ${popped.title}"
                        triedAt(parent.key).replaceAll { _, r -> if (r == into) "$into$RETURNED" else r }
                    }
                } else {
                    stack.add(now)
                    if (stack.size > MAX_DEPTH) stack.removeAt(0)
                }
            }
        }
    }

    fun snapshot(): NavSnapshot {
        val top = stack.lastOrNull()
        return NavSnapshot(
            path = stack.joinToString(" > ") { it.title },
            depth = stack.size,
            parent = stack.getOrNull(stack.size - 2)?.title,
            triedHere = top?.let { p -> tried[p.key]?.entries?.map { "${it.key} ${it.value}" }?.takeLast(MAX_TRIED) }.orEmpty(),
            atBottom = top != null && top.key in bottom,
        )
    }

    /** 本次任务里证明走不通的入口，重新规划时告诉规划器"这些地方找过了"。 */
    fun explored(): List<String> = tried.flatMap { (key, entries) ->
        entries.filterValues { it.endsWith(RETURNED) || it == RESULT_NO_CHANGE || it == RESULT_FAILED }
            .map { (label, result) -> "${titles[key] ?: "?"}：$label $result" }
    }.takeLast(MAX_EXPLORED)

    private fun triedAt(key: String) = tried.getOrPut(key) { linkedMapOf() }

    private fun pageOf(observation: ScreenObservation, activity: String?): Page {
        val title = MobilePriors.pageTitle(observation)
        val act = MobilePriors.shortActivity(activity)
        val key = "${observation.packageName}|${act.orEmpty()}|${title.orEmpty()}"
        val name = title ?: act ?: observation.packageName.substringAfterLast('.').ifEmpty { "?" }
        titles[key] = name
        return Page(key, name)
    }

    companion object {
        const val RESULT_NO_CHANGE = "无变化"
        const val RESULT_FAILED = "失败"
        const val RESULT_SAME_PAGE = "本页内容变了"
        const val RETURNED = "（进去过，已退回）"
        private val UNTRACKED_OPS = setOf("BACK", "HOME", "WAIT", "SCROLL_UP")
        private const val MAX_DEPTH = 8
        private const val MAX_TRIED = 8
        private const val MAX_EXPLORED = 12
        private const val MAX_LABEL = 30
    }
}

/** 给决策器看的导航摘要。 */
data class NavSnapshot(
    val path: String,
    val depth: Int,
    val parent: String?,
    val triedHere: List<String>,
    val atBottom: Boolean,
)
