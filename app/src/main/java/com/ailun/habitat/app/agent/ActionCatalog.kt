package com.ailun.habitat.app.agent

/**
 * 从当前屏幕生成候选动作。支付类按钮默认不进入候选，除非目标原文里写了同一个词。
 */
object Catalogs {
    val sensitiveWords = listOf("转账", "红包", "付款", "支付", "确认支付", "确认转账")
    private const val MAX_TAPS = 24
    private const val MAX_APPS = 8
    private const val MAX_TEXTS = 6

    fun textCandidates(goal: String): List<String> {
        val quoted = Regex("[\"「『']([^\"」』']{1,40})[\"」』']")
            .findAll(goal)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
        if (quoted.isNotEmpty()) return quoted.take(MAX_TEXTS)
        return Regex("(?:输入|填写|键入)\\s+(\\S{1,40})")
            .findAll(goal)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_TEXTS)
            .toList()
    }

    fun build(
        goal: String,
        observation: ScreenObservation,
        apps: List<AppEntry>,
    ): ActionCatalog {
        val actions = mutableListOf<CatalogAction>()
        val operations = linkedMapOf<String, String>()
        val tapTargets = linkedMapOf<String, String>()
        val textValues = linkedMapOf<String, String>()
        val appTargets = linkedMapOf<String, String>()

        val taps = observation.elements
            .filter { it.clickable && it.text.isNotBlank() && allowedTap(goal, it.text) }
            .take(MAX_TAPS)
        if (taps.isNotEmpty()) {
            operations["TAP"] = "点击一个当前可见控件，用来打开、聚焦或选择。"
            taps.forEach { el ->
                tapTargets[el.index] = el.text
                actions.add(
                    CatalogAction(
                        id = "tap_${el.index}",
                        operation = "TAP",
                        target = el.index,
                        label = "点击「${el.text}」",
                        tool = "ACTION_CLICK",
                        params = mapOf("target" to "${el.centerX},${el.centerY}"),
                        centerX = el.centerX,
                        centerY = el.centerY,
                        matchText = el.text,
                    ),
                )
            }
        }

        val focused = observation.elements.firstOrNull { it.index == observation.focusedIndex && it.editable }
        val texts = if (focused != null) textCandidates(goal) else emptyList()
        if (texts.isNotEmpty()) {
            operations["TYPE_TEXT"] = "把当前焦点输入框替换成目标里给出的原文，不要编造。"
            texts.forEachIndexed { i, value ->
                val key = (i + 1).toString()
                textValues[key] = value
                actions.add(
                    CatalogAction(
                        id = "type_$key",
                        operation = "TYPE_TEXT",
                        target = key,
                        label = "输入「$value」",
                        tool = "ACTION_INPUT_TEXT",
                        params = mapOf("text" to value, "mode" to "a11y"),
                    ),
                )
            }
        }

        val named = apps.filter { app ->
            app.packageName != observation.packageName &&
                app.label.length >= 2 &&
                goal.contains(app.label, ignoreCase = true)
        }.distinctBy { it.packageName }.take(MAX_APPS)
        if (named.isNotEmpty()) {
            operations["OPEN_APP"] = "直接打开目标里点名的已安装应用，不必从桌面翻找。"
            named.forEachIndexed { i, app ->
                val key = (i + 1).toString()
                appTargets[key] = "${app.label} (${app.packageName})"
                actions.add(
                    CatalogAction(
                        id = "open_${app.packageName}",
                        operation = "OPEN_APP",
                        target = key,
                        label = "打开 ${app.label}",
                        tool = "ACTION_LAUNCH_APP",
                        params = mapOf("package_name" to app.packageName),
                    ),
                )
            }
        }

        operations["SCROLL_DOWN"] = "向下滚动，露出更下面的内容。"
        operations["SCROLL_UP"] = "向上滚动，露出更上面的内容。"
        actions.add(scrollAction(observation, down = true))
        actions.add(scrollAction(observation, down = false))

        operations["BACK"] = "返回上一屏。"
        operations["HOME"] = "回到桌面。"
        operations["WAIT"] = "屏幕还在加载时短暂等待。"
        operations["DONE"] = "目标已经在屏幕上完成。"
        operations["BLOCKED"] = "给出的操作都无法让目标前进一步。"
        actions.add(nav("BACK", "back", "返回"))
        actions.add(nav("HOME", "home", "回到桌面"))
        actions.add(CatalogAction(id = "wait", operation = "WAIT", label = "等待"))
        actions.add(CatalogAction(id = "done", operation = "DONE", label = "完成"))
        actions.add(CatalogAction(id = "blocked", operation = "BLOCKED", label = "无法继续"))

        return ActionCatalog(actions, operations, tapTargets, textValues, appTargets)
    }

    fun allowedTap(goal: String, label: String): Boolean {
        val hit = sensitiveWords.firstOrNull { label.contains(it) } ?: return true
        return goal.contains(hit)
    }

    private fun scrollAction(observation: ScreenObservation, down: Boolean): CatalogAction {
        val w = observation.screenWidth.coerceAtLeast(200)
        val h = observation.screenHeight.coerceAtLeast(200)
        val x = w / 2
        val y1 = if (down) (h * 0.72).toInt() else (h * 0.28).toInt()
        val y2 = if (down) (h * 0.28).toInt() else (h * 0.72).toInt()
        val op = if (down) "SCROLL_DOWN" else "SCROLL_UP"
        return CatalogAction(
            id = if (down) "scroll_down" else "scroll_up",
            operation = op,
            label = if (down) "向下滚动" else "向上滚动",
            tool = "ACTION_SWIPE",
            params = mapOf("x1" to x, "y1" to y1, "x2" to x, "y2" to y2, "duration" to 400),
        )
    }

    private fun nav(operation: String, action: String, label: String) = CatalogAction(
        id = operation.lowercase(),
        operation = operation,
        label = label,
        tool = "ACTION_GLOBAL_NAV",
        params = mapOf("action" to action),
    )
}
