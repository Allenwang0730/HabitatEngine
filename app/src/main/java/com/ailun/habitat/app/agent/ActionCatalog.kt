package com.ailun.habitat.app.agent

/**
 * 从当前屏幕生成候选动作。
 *
 * 三道闸：
 * - 敏感按钮（支付/删除/卸载/授权…）默认不进候选；本机发起且目标原文写了同一个词才放行。
 *   远程发起（allowGoalUnlock=false）的目标一律不能解锁。
 * - 支付密码、指纹/人脸验证等页面：不给任何点击和输入，只剩返回/桌面/等待/完成/无法继续。
 * - 系统权限弹窗、安装器：只有本机发起且目标里明确写了"授权/允许/权限/安装"才给点击。
 */
object Catalogs {
    val sensitiveWords = listOf(
        "转账", "红包", "付款", "支付", "确认支付", "确认转账", "收款",
        "购买", "下单", "提交订单", "立即支付", "充值", "提现",
        "删除", "清空", "卸载", "恢复出厂", "重置", "注销", "退出登录",
        "允许", "授权", "始终允许", "仅在使用中允许", "同意",
    )
    /** 出现这些文字说明是验证/支付凭据页，模型不能碰任何控件。 */
    val lockedScreenWords = listOf("支付密码", "请输入6位", "验证指纹", "指纹支付", "面容支付", "人脸验证", "刷脸", "银行卡号")
    val guardedPackages = setOf(
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
    )
    private val guardedUnlockWords = listOf("授权", "允许", "权限", "安装")

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
        allowGoalUnlock: Boolean = true,
    ): ActionCatalog {
        val actions = mutableListOf<CatalogAction>()
        val operations = linkedMapOf<String, String>()
        val tapTargets = linkedMapOf<String, String>()
        val textValues = linkedMapOf<String, String>()
        val appTargets = linkedMapOf<String, String>()

        val guard = guardOf(goal, observation, allowGoalUnlock)
        val taps = if (guard != null) emptyList() else observation.elements
            .filter { it.clickable && it.text.isNotBlank() && allowedTap(goal, it.text, allowGoalUnlock) }
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

        val focused = observation.elements.firstOrNull {
            it.index == observation.focusedIndex && it.editable && !it.password
        }
        val texts = if (focused != null && guard == null) textCandidates(goal) else emptyList()
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

        return ActionCatalog(actions, operations, tapTargets, textValues, appTargets, guard)
    }

    fun allowedTap(goal: String, label: String, allowGoalUnlock: Boolean = true): Boolean {
        val hit = sensitiveWords.firstOrNull { label.contains(it) } ?: return true
        return allowGoalUnlock && goal.contains(hit)
    }

    /** 当前屏幕需要整体收紧时返回原因，否则 null。 */
    fun guardOf(goal: String, observation: ScreenObservation, allowGoalUnlock: Boolean): String? {
        lockedScreenWords.firstOrNull { observation.visibleText.contains(it) }?.let {
            return "验证/支付凭据页（$it），不点击也不输入"
        }
        if (observation.packageName in guardedPackages) {
            val unlocked = allowGoalUnlock && guardedUnlockWords.any { goal.contains(it) }
            if (!unlocked) return "系统权限/安装界面，目标未要求授权，不点击"
        }
        return null
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
