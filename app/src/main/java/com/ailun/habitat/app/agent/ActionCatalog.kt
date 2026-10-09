package com.ailun.habitat.app.agent

/**
 * 从当前屏幕生成候选动作。
 *
 * 三道闸：
 * - 敏感按钮（支付/删除/卸载/授权…）默认不进候选；本机发起且目标原文写了同一个词才放行。
 *   远程发起（allowGoalUnlock=false）的目标一律不能解锁。
 * - 支付密码、指纹/人脸验证等页面：不给任何点击和输入，只剩返回/桌面/等待/完成/无法继续。
 * - 系统权限弹窗、安装器：只有本机发起且目标里明确写了"授权/允许/权限/安装"才给点击。
 *
 * 候选排序（`orderedTaps`）：真按钮/开关 → 列表行/容器 → 纯文字节点。真机实测列表页
 * 19 个可点行里只有 2 个自带文字，把行排在文字节点之后就等于让模型看不见它们。
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

    /** 本 App 自己的包名：悬浮球/日志窗不能进候选，否则模型会点自己的悬浮窗。 */
    private const val SELF_PACKAGE = "com.ailun.habitat"

    private const val MAX_TAPS = 30
    private const val MAX_LONG_PRESS = 8
    private const val MAX_APPS = 8
    private const val MAX_TEXTS = 6
    private const val TEXT_PREVIEW = 40

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
        val longPressTargets = linkedMapOf<String, String>()

        val guard = guardOf(goal, observation, allowGoalUnlock)
        val taps = if (guard != null) emptyList() else orderedTaps(goal, observation, allowGoalUnlock)
        if (taps.isNotEmpty()) {
            operations["TAP"] = "点击一个当前可见控件，用来打开、聚焦或选择。列表行也可以直接点。"
            taps.forEach { el ->
                // 键用元素自己的序号：提示词里的「控件一览」也按这个编号，模型看到几号就选几号。
                // 之前按候选排序重新编号，两套编号对不上，真机实测模型照着一览选了 3 号，结果点到别的或报"候选里没有"。
                val key = el.index
                val type = typeOf(el)
                tapTargets[key] = describeTarget(el, type)
                actions.add(
                    CatalogAction(
                        id = "tap_${el.index}",
                        operation = "TAP",
                        target = key,
                        label = "点击「${el.text.take(TEXT_PREVIEW)}」($type)",
                        tool = "ACTION_CLICK",
                        params = mapOf("target" to "${el.centerX},${el.centerY}"),
                        centerX = el.centerX,
                        centerY = el.centerY,
                        matchText = el.text,
                        viewId = el.viewId,
                    ),
                )
            }
        }

        val focused = observation.elements.firstOrNull {
            it.index == observation.focusedIndex && it.editable && !it.password
        }
        val texts = if (focused != null && guard == null) textCandidates(goal) else emptyList()
        if (texts.isNotEmpty()) {
            operations["TYPE_TEXT"] = "把当前焦点输入框替换成目标里给出的原文，不要编造。" +
                "ACTION_SET_TEXT 是整框替换，不需要先清空。"
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

        operations["BACK"] = "返回上一屏。从二级页面回列表时用它，不要用 HOME。"
        operations["HOME"] = "回到桌面。只在需要离开当前应用时用。"
        operations["WAIT"] = "屏幕还在加载时短暂等待。不确定该做什么时选它，不要选 DONE。"
        operations["DONE"] = "目标已经在屏幕上完成。只有屏幕上确实出现完成状态才能选。"
        operations["BLOCKED"] = "给出的操作都无法让目标前进一步。"
        actions.add(nav("BACK", "back", "返回"))
        actions.add(nav("HOME", "home", "回到桌面"))
        actions.add(CatalogAction(id = "wait", operation = "WAIT", label = "等待"))
        actions.add(CatalogAction(id = "done", operation = "DONE", label = "完成"))
        actions.add(CatalogAction(id = "blocked", operation = "BLOCKED", label = "无法继续"))

        // 长按：少数行要长按才出菜单。候选目标与 TAP 相同（同一批控件）。
        // 只在确实有候选控件时出现，避免空页面里给模型一堆无效动作。
        if (guard == null && taps.isNotEmpty()) {
            operations["LONG_PRESS"] = "长按一个控件，用于唤出长按菜单（重命名、删除、更多选项）。"
            taps.take(MAX_LONG_PRESS).forEach { el ->
                val key = el.index
                val type = typeOf(el)
                longPressTargets[key] = describeTarget(el, type)
                actions.add(
                    CatalogAction(
                        id = "long_${el.index}",
                        operation = "LONG_PRESS",
                        target = key,
                        label = "长按「${el.text.take(TEXT_PREVIEW)}」",
                        tool = "ACTION_LONG_PRESS",
                        params = mapOf("target" to "${el.centerX},${el.centerY}", "duration" to 800),
                        centerX = el.centerX,
                        centerY = el.centerY,
                        matchText = el.text,
                        viewId = el.viewId,
                    ),
                )
            }
        }

        // 输入框有焦点时才给"提交"和"清空"。两者都靠 lib 已有节点实现，没有给 lib 加参数。
        if (focused != null && guard == null) {
            operations["PRESS_ENTER"] = "在焦点输入框里按回车，用于提交搜索或确认输入。提交后等待屏幕变化。"
            actions.add(
                CatalogAction(
                    id = "press_enter",
                    operation = "PRESS_ENTER",
                    label = "按回车提交",
                    // 无障碍 IME 回车，不依赖 Shizuku（真机上 input keyevent 在没开 Shizuku 时必失败）
                    tool = "ACTION_INPUT_TEXT",
                    params = mapOf("text" to "", "submit" to true, "mode" to "a11y"),
                ),
            )
            // 清空不是"删除键按 N 次"（次数猜不准、且每次动作会被判重挡住）：
            // ACTION_SET_TEXT 是整框替换，allow_empty 写入空串即清空。
            operations["CLEAR_TEXT"] = "清空当前焦点输入框里已有的内容。"
            actions.add(
                CatalogAction(
                    id = "clear_text",
                    operation = "CLEAR_TEXT",
                    label = "清空输入框",
                    tool = "ACTION_INPUT_TEXT",
                    params = mapOf("text" to "", "allow_empty" to true, "mode" to "a11y"),
                ),
            )
        }

        return ActionCatalog(actions, operations, tapTargets, textValues, appTargets, longPressTargets, guard)
    }

    /**
     * 进 TAP 候选的元素，按"像不像真正的可点控件"排序。
     *
     * 真机（设置页）19 个可点击节点里 17 个是没有自带文字的列表行容器，文字在后代
     * TextView 上。旧实现只收「clickable 且有文字」，等于把这些行全丢掉。
     *
     * 本 App 自己的悬浮球/日志窗：`ScreenObserver.select` 已尽量排掉，这里再兜一层——
     * 当前观测的前台包就是本 App 时，整屏不给点击（说明只读到了自己的窗口）。
     */
    fun orderedTaps(goal: String, observation: ScreenObservation, allowGoalUnlock: Boolean = true): List<ScreenElement> {
        if (observation.packageName == SELF_PACKAGE) return emptyList()
        return observation.elements
            .filter { (it.clickable || it.selectable || it.editable) && it.text.isNotBlank() }
            .filter { allowedTap(goal, it.text, allowGoalUnlock) }
            // 先按"控件可信度"分层，再在层内按屏幕从上到下（模型看屏幕的习惯顺序）
            .sortedWith(compareBy({ tierOf(it) }, { it.centerY }, { it.centerX }))
            .take(MAX_TAPS)
    }

    /** 0 = 真按钮/开关，1 = 列表行/容器，2 = 只有文字的节点。 */
    fun tierOf(el: ScreenElement): Int = when {
        el.editable -> 0
        el.clickable && !el.selectable -> 0
        el.selectable -> 1
        else -> 2
    }

    /**
     * 候选文字推断出的控件类型，写进候选表让模型知道"这是按钮还是行"。
     *
     * selectable 优先于 viewId：设置页的列表行 id 就叫 `id/title`，先看 id 会把整行
     * 报成"标签"，模型就不知道那行是可以点的。
     */
    fun typeOf(el: ScreenElement): String {
        val id = el.viewId.lowercase()
        return when {
            el.editable -> "输入框"
            id.contains("switch") || id.contains("toggle") -> "开关"
            id.contains("checkbox") -> "复选框"
            el.selectable -> "列表行"
            id.contains("tab") -> "标签页"
            id.endsWith("icon") || id.contains("button") || id.contains("btn") -> "按钮"
            else -> if (el.clickable) "按钮" else "标签"
        }
    }

    /** 给模型看的候选条目：`文字 ⟪id/title⟫`。viewId 让模型能区分同名控件。 */
    fun describeTarget(el: ScreenElement, type: String): String {
        val id = shortViewId(el.viewId)
        return if (id.isEmpty()) el.text else "${el.text} ⟪$id⟫"
    }

    /** `com.android.settings:id/title` → `id/title`，去掉包名前缀省 token。 */
    fun shortViewId(viewId: String): String {
        if (viewId.isEmpty()) return ""
        val slash = viewId.indexOf(":id/")
        return if (slash >= 0) "id/" + viewId.substring(slash + 4) else viewId
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
