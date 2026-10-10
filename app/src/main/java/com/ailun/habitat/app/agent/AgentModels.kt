package com.ailun.habitat.app.agent

/** 一次读屏得到的可点击/可输入元素。坐标是屏幕像素，Y 向下为正。 */
data class ScreenElement(
    val index: String,
    val text: String,
    val centerX: Int,
    val centerY: Int,
    /** 真实 isClickable，或"有可点击祖先/后代"（容器上的点击由坐标命中）。 */
    val clickable: Boolean,
    val editable: Boolean,
    val focused: Boolean,
    /** resource-id 全名，如 com.android.settings:id/title。 */
    val viewId: String = "",
    /** 密码框：文字已替换成占位，不向决策器暴露原文，也不作为输入目标。 */
    val password: Boolean = false,
    /** 容器/列表行（可点但不是按钮、开关、checkbox 之类的控件），供候选排序用。 */
    val selectable: Boolean = false,
    /** 屏幕像素矩形，用于判断元素包含关系和点击落点。 */
    val bounds: ElementBounds? = null,
    /** text 是从后代拼接来的容器标签，不是节点自身文字。 */
    val aggregated: Boolean = false,
    /** 开关/复选框（或包着唯一一个开关的列表行）。 */
    val checkable: Boolean = false,
    /** [checkable] 时的当前状态；不可勾选为 null。 */
    val checked: Boolean? = null,
    /** 灰掉的控件：点了没用。 */
    val enabled: Boolean = true,
    /** 滑块/进度条：当前值在 [min,max] 中的位置，0~1；不是滑块为 null。 */
    val rangeFraction: Double? = null,
    /** 来自非活动的系统窗口（状态栏、灵动岛、导航栏）。 */
    val system: Boolean = false,
)

data class ElementBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2

    fun contains(other: ElementBounds): Boolean =
        other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom
}

/**
 * 一个无障碍窗口的元信息，供 debug/日志判断"哪个窗口是前台"。
 * [type] 用 AccessibilityWindowInfo.TYPE_* 常量。
 */
data class WindowInfo(
    val packageName: String,
    val rank: Int,
    val active: Boolean,
    val type: Int,
    val focused: Boolean,
    val nodeCount: Int,
    val text: String,
)

data class ScreenObservation(
    val packageName: String,
    val screenWidth: Int,
    val screenHeight: Int,
    val elements: List<ScreenElement>,
    val visibleText: String,
    val source: String,
    val focusedIndex: String? = null,
    /** 锁屏/息屏时为 true，此时 elements 为空，决策器必须放弃本步。 */
    val locked: Boolean = false,
    /** 本次读屏遍历到的窗口（已排掉本 App 自己的）。 */
    val windows: List<WindowInfo> = emptyList(),
) {
    companion object {
        fun empty(width: Int, height: Int) = ScreenObservation(
            packageName = "",
            screenWidth = width,
            screenHeight = height,
            elements = emptyList(),
            visibleText = "",
            source = "none",
        )

        /** 锁屏 / 息屏：没有任何可操作元素，且标记 locked 让上层别做决策。 */
        fun locked(width: Int, height: Int) = ScreenObservation(
            packageName = "",
            screenWidth = width,
            screenHeight = height,
            elements = emptyList(),
            visibleText = "",
            source = "locked",
            locked = true,
        )
    }
}

data class AppEntry(val label: String, val packageName: String)

/**
 * 代码拥有的一个可执行候选。决策模型只能选 operation/target，不能发明参数。
 * [tool] 为空表示 WAIT / DONE / BLOCKED，不调用节点。
 */
data class CatalogAction(
    val id: String,
    val operation: String,
    val target: String? = null,
    val label: String,
    val tool: String? = null,
    val params: Map<String, Any> = emptyMap(),
    val centerX: Int? = null,
    val centerY: Int? = null,
    val matchText: String? = null,
    /** 目标控件的 resource-id 全名，用于判重签名与"点这个还是点那个"的区分。 */
    val viewId: String = "",
    /**
     * 一次失败后可以改试的候选。同一操作换个目标再试一次；用完之后才认定这一步失败。
     * 只对 TAP 有意义：同一行文字在不同层级出现时，第一候选可能点不到。
     */
    val alternatives: List<CatalogAction> = emptyList(),
    /** 节点成功标志变量名（如 bluetooth_success）；为空时按 [tool] 推断。 */
    val successFlag: String? = null,
    /** 执行后要记成"已知事实"的输出变量（读电量、读时间这类查询工具）。 */
    val outputs: List<String> = emptyList(),
    /** 设备工具的身份（tool|args），用于记"本次失败过的工具"。 */
    val toolKey: String? = null,
)

data class ActionCatalog(
    val actions: List<CatalogAction>,
    val operations: Map<String, String>,
    val tapTargets: Map<String, String>,
    val textValues: Map<String, String>,
    val apps: Map<String, String>,
    val longPressTargets: Map<String, String> = emptyMap(),
    /** 非空表示本屏被收紧（验证页/权限弹窗），只剩导航类动作。 */
    val guard: String? = null,
    /** USE_TOOL 的候选：直接调设备工具（跳设置页、开关、调亮度…），比在界面里翻找快且稳。 */
    val toolTargets: Map<String, String> = emptyMap(),
    /** SET_SLIDER 的候选：`序号:max|min|mid` → 说明。 */
    val sliderTargets: Map<String, String> = emptyMap(),
) {
    fun resolve(operation: String, target: String?): CatalogAction? = when (operation) {
        "TAP" -> actions.find { it.operation == "TAP" && it.target == target }
        "TYPE_TEXT" -> actions.find { it.operation == "TYPE_TEXT" && it.target == target }
        "OPEN_APP" -> actions.find { it.operation == "OPEN_APP" && it.target == target }
        "LONG_PRESS" -> actions.find { it.operation == "LONG_PRESS" && it.target == target }
        "USE_TOOL" -> actions.find { it.operation == "USE_TOOL" && it.target == target }
        "SET_SLIDER" -> actions.find { it.operation == "SET_SLIDER" && it.target == target }
        "SCROLL_DOWN", "SCROLL_UP", "BACK", "HOME", "WAIT", "DONE", "BLOCKED",
        "PRESS_ENTER", "CLEAR_TEXT",
        -> actions.find { it.operation == operation }
        else -> null
    }

    /**
     * 去掉这次任务里已经证明走不通的动作（按 [signatureOf] 匹配）。
     * 操作下的候选全被去掉时，连操作本身一起去掉，模型就不会再选它。
     * DONE/BLOCKED/WAIT/BACK/HOME 永远保留：模型必须始终有路可退。
     */
    fun without(deadEnds: Set<String>, signatureOf: (CatalogAction) -> String): ActionCatalog {
        if (deadEnds.isEmpty()) return this
        val keep = actions.filter { it.operation in ALWAYS_KEEP || signatureOf(it) !in deadEnds }
        val keptTargets = { op: String, map: Map<String, String> ->
            val ids = keep.filter { it.operation == op }.mapNotNull { it.target }.toSet()
            map.filterKeys { it in ids }
        }
        val ops = operations.filterKeys { op -> op in ALWAYS_KEEP || keep.any { it.operation == op } }
        return copy(
            actions = keep,
            operations = ops,
            tapTargets = keptTargets("TAP", tapTargets),
            textValues = keptTargets("TYPE_TEXT", textValues),
            apps = keptTargets("OPEN_APP", apps),
            longPressTargets = keptTargets("LONG_PRESS", longPressTargets),
            toolTargets = keptTargets("USE_TOOL", toolTargets),
            sliderTargets = keptTargets("SET_SLIDER", sliderTargets),
        )
    }

    /**
     * 同一操作下的候选，按"贴合目标原文"排序，供失败后换一个再试。
     * 目标原文里没提到的候选也保留（排在后面），否则"屏幕上没写目标词"时无路可走。
     */
    fun candidatesFor(operation: String, goal: String, exclude: Set<String> = emptySet()): List<CatalogAction> =
        actions.filter { it.operation == operation && it.id !in exclude }
            .sortedByDescending { relevance(goal, it) }

    companion object {
        val ALWAYS_KEEP = setOf("DONE", "BLOCKED", "WAIT", "BACK", "HOME")
    }

    /** 目标原文与候选文字的重合度；容器行文字是拼接的，用 contains 双向匹配。 */
    private fun relevance(goal: String, action: CatalogAction): Int {
        val text = (action.matchText ?: action.label).trim()
        if (text.isEmpty()) return 0
        return when {
            goal.contains(text, ignoreCase = true) -> text.length * 2
            text.contains(goal.trim(), ignoreCase = true) -> 1
            else -> 0
        }
    }
}

data class Decision(
    val status: String,
    val operation: String,
    val target: String? = null,
    val action: CatalogAction? = null,
    val confidence: Double = 1.0,
    val detail: String = "",
    /** 执行器对"当前步骤已完成"的概率（Jev 的 step_status 题）；没问为 null。 */
    val stepDone: Double? = null,
    /** 决策器本身不可用（key 无效、地区被拒）：重规划也没用，整个任务立即结束。 */
    val fatal: Boolean = false,
)

data class StepNote(
    val actionId: String,
    val operation: String,
    val label: String,
    /** 执行后屏幕是否有变化；null 表示未知。告诉决策器上一步是否起效。 */
    val changed: Boolean? = null,
    /**
     * 同一动作的判重签名：操作 + 控件 viewId（拿不到才退回文字）+ 前台包。
     * 不用序号（序号换屏就变），也不只看文字（同一行文字在不同层级界面会误判）。
     */
    val signature: String = actionId,
    /** 这一步的失败原因（节点返回失败/异常）。非空时决策器应换招而不是重试同一个。 */
    val error: String = "",
    /** 这一步之后前台换成了别的应用（点进了外部 App）。紧接着又被拉回来，就说明这个入口是死路。 */
    val leftApp: Boolean = false,
    /** 属于规划里的第几步（0 起）；没有规划时恒为 0。 */
    val planStep: Int = 0,
)

/**
 * 规划器（System 2）给出的一步。两种：
 * - 工具步（[tool] 非空）：直接调设备工具（开设置页、开关蓝牙、调亮度…），不经过界面；
 *   失败时退回成界面步，按 [instruction]/[doneWhen] 让执行器去点。
 * - 界面步：交给执行器（Jev/DeepSeek/本地）在屏幕上一步步点，直到 [doneWhen] 在屏幕上成立。
 */
data class PlanStep(
    val instruction: String,
    val doneWhen: String,
    val tool: String? = null,
    val args: Map<String, String> = emptyMap(),
    /** 这一步要输入的原文。必须是目标原文的子串（规划器不能编造输入内容）。 */
    val text: String? = null,
)

data class Plan(val steps: List<PlanStep>, val summary: String = "")

/** 规划器对"整件事做完没有"的独立判断。 */
data class Review(val done: Boolean, val reason: String, val answer: String = "")

/**
 * 执行器每一轮看到的"当前在做哪一步"。没有规划器时整件事就是一步。
 * 控制权在代码手里：步骤推进、完成判定由循环决定，模型只回答"这一步现在该点哪"。
 */
data class StepContext(
    val goal: String,
    val instruction: String,
    val doneWhen: String,
    val index: Int,
    val total: Int,
    val completed: List<String> = emptyList(),
    val facts: Map<String, String> = emptyMap(),
    /** 导航栈与本页已试过的入口（[NavigationState.snapshot]）。 */
    val nav: NavSnapshot? = null,
) {
    val multiStep: Boolean get() = total > 1
}
