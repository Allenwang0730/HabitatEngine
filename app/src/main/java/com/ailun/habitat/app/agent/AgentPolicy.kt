package com.ailun.habitat.app.agent

import com.google.gson.JsonObject

/** 不联网的决策：目标原文里出现的控件优先，否则打开点名的应用，再否则最多向下滚两次。 */
fun decideLocal(goal: String, catalog: ActionCatalog, history: List<StepNote>): Decision {
    val scrolled = history.count { it.operation == "SCROLL_DOWN" }
    val best = catalog.actions
        .filter { it.operation == "TAP" }
        .maxByOrNull { score(goal, it.matchText.orEmpty()) }
    val bestScore = best?.let { score(goal, it.matchText.orEmpty()) } ?: 0
    if (best != null && bestScore >= 2) {
        return actionDecision(best, "本地匹配「${best.matchText}」")
    }
    val app = catalog.actions.firstOrNull { it.operation == "OPEN_APP" }
    if (app != null && history.none { it.actionId == app.id }) {
        return actionDecision(app, "本地打开点名应用")
    }
    if (scrolled < 2) {
        val scroll = catalog.resolve("SCROLL_DOWN", null)
        if (scroll != null) return actionDecision(scroll, "本地向下查找")
    }
    val blocked = catalog.resolve("BLOCKED", null)
    return Decision(
        status = "blocked",
        operation = "BLOCKED",
        action = blocked,
        detail = "本地策略找不到可推进的控件",
    )
}

fun score(goal: String, label: String): Int {
    val text = label.trim()
    if (text.length < 2) return 0
    return if (goal.contains(text, ignoreCase = true)) text.length else 0
}

data class ParsedChoice(val choice: String, val confidence: Double)

/** TypeSafe choice 答案：choice 必须在候选里；若带了概率，被选中的项必须是最大项。 */
fun parseChoice(answer: JsonObject?, allowed: Set<String>): ParsedChoice? {
    if (answer == null || allowed.isEmpty()) return null
    val type = answer.get("type")?.asString
    if (type != null && type != "choice") return null
    val choice = answer.get("choice")?.asString ?: return null
    if (choice !in allowed) return null
    val confidence = answer.get("confidence")?.let { runCatching { it.asDouble }.getOrNull() } ?: 1.0
    if (confidence < 0.0 || confidence > 1.0) return null
    val probs = answer.get("probabilities")?.takeIf { it.isJsonObject }?.asJsonObject
    if (probs != null) {
        val chosen = probs.get(choice)?.let { runCatching { it.asDouble }.getOrNull() } ?: return null
        val higher = probs.entrySet().any { (key, value) ->
            key != choice && (runCatching { value.asDouble }.getOrNull() ?: 0.0) > chosen + 1e-6
        }
        if (higher) return null
    }
    return ParsedChoice(choice, confidence)
}

fun decisionFromChoice(
    catalog: ActionCatalog,
    operation: String,
    target: String?,
    confidence: Double,
    detail: String,
): Decision {
    if (operation == "DONE") {
        return Decision("done", "DONE", action = catalog.resolve("DONE", null), confidence = confidence, detail = detail)
    }
    if (operation == "BLOCKED") {
        return Decision("blocked", "BLOCKED", action = catalog.resolve("BLOCKED", null), confidence = confidence, detail = detail)
    }
    val action = catalog.resolve(operation, target)
        ?: return Decision("error", operation, target, confidence = confidence, detail = "候选里没有 $operation/$target")
    val status = if (operation == "WAIT") "wait" else "action"
    return Decision(status, operation, target, action, confidence, detail)
}

fun actionDecision(action: CatalogAction, detail: String) = Decision(
    status = if (action.operation == "WAIT") "wait" else "action",
    operation = action.operation,
    target = action.target,
    action = action,
    detail = detail,
)

/** 从模型原文里抽出一个 JSON 对象，并核对它落在候选表内。 */
fun parseModelDecision(raw: String, catalog: ActionCatalog): Decision {
    val start = raw.indexOf('{')
    val end = raw.lastIndexOf('}')
    if (start < 0 || end <= start) {
        return Decision("error", "", detail = "决策没有返回 JSON")
    }
    val obj = runCatching {
        com.google.gson.JsonParser.parseString(raw.substring(start, end + 1)).asJsonObject
    }.getOrNull() ?: return Decision("error", "", detail = "决策 JSON 无法解析")
    val operation = obj.get("operation")?.asString?.trim().orEmpty()
    val target = obj.get("target")?.asString?.trim()?.takeIf { it.isNotEmpty() }
    if (operation !in catalog.operations) {
        return Decision("error", operation, target, detail = "操作不在候选中: $operation")
    }
    return decisionFromChoice(catalog, operation, target, 1.0, "DeepSeek 选择 $operation")
}
