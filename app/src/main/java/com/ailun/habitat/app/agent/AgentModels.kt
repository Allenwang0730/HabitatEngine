package com.ailun.habitat.app.agent

/** 一次读屏得到的可点击/可输入元素。坐标是屏幕像素，Y 向下为正。 */
data class ScreenElement(
    val index: String,
    val text: String,
    val centerX: Int,
    val centerY: Int,
    val clickable: Boolean,
    val editable: Boolean,
    val focused: Boolean,
    val viewId: String = "",
    /** 密码框：文字已替换成占位，不向决策器暴露原文，也不作为输入目标。 */
    val password: Boolean = false,
)

data class ScreenObservation(
    val packageName: String,
    val screenWidth: Int,
    val screenHeight: Int,
    val elements: List<ScreenElement>,
    val visibleText: String,
    val source: String,
    val focusedIndex: String? = null,
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
)

data class ActionCatalog(
    val actions: List<CatalogAction>,
    val operations: Map<String, String>,
    val tapTargets: Map<String, String>,
    val textValues: Map<String, String>,
    val apps: Map<String, String>,
    /** 非空表示本屏被收紧（验证页/权限弹窗），只剩导航类动作。 */
    val guard: String? = null,
) {
    fun resolve(operation: String, target: String?): CatalogAction? = when (operation) {
        "TAP" -> actions.find { it.operation == "TAP" && it.target == target }
        "TYPE_TEXT" -> actions.find { it.operation == "TYPE_TEXT" && it.target == target }
        "OPEN_APP" -> actions.find { it.operation == "OPEN_APP" && it.target == target }
        "SCROLL_DOWN", "SCROLL_UP", "BACK", "HOME", "WAIT", "DONE", "BLOCKED" ->
            actions.find { it.operation == operation }
        else -> null
    }
}

data class Decision(
    val status: String,
    val operation: String,
    val target: String? = null,
    val action: CatalogAction? = null,
    val confidence: Double = 1.0,
    val detail: String = "",
)

data class StepNote(
    val actionId: String,
    val operation: String,
    val label: String,
    /** 执行后屏幕是否有变化；null 表示未知。告诉决策器上一步是否起效。 */
    val changed: Boolean? = null,
    /** 同一动作的判重签名（操作 + 控件文字 + 前台包），不用序号，序号换屏就变。 */
    val signature: String = actionId,
)
