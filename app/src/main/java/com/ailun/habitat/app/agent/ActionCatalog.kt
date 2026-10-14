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
        // 发消息是对外的动作：目标没说要发，就不能替你点「发送」
        "发送",
    )

    /**
     * 敏感词的同义说法：目标写了其中之一也算"点名"了这个敏感动作。
     * 「给我的电脑发消息说你好」里没有「发送」两个字，但显然是要发。
     */
    private val unlockAliases: Map<String, Regex> = mapOf(
        "发送" to Regex("发送|发(?:个|一个|一条|条)?(?:消息|信息|短信|微信)|发给|回复"),
    )

    /** 输入完之后要点的提交类按钮。 */
    val SUBMIT_WORDS = listOf("发送", "搜索", "确定", "完成", "提交", "前往")
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
    private const val MAX_SLIDERS = 3

    fun textCandidates(goal: String): List<String> = IntentParser.inputTexts(goal)

    /**
     * 本屏要往哪个输入框里写：有焦点的那个；没报告焦点但只有一个输入框时就是它。
     * QQ/微信的输入框点过之后常常不报 isFocused，旧逻辑只认焦点，于是一直不给 TYPE_TEXT，
     * 模型只能反复点输入框直到判兜圈。多个输入框又都没焦点时不猜，让模型先点一个。
     */
    fun inputTarget(observation: ScreenObservation): ScreenElement? {
        val editable = observation.elements.filter { it.editable && !it.password && !it.system }
        return editable.firstOrNull { it.index == observation.focusedIndex || it.focused }
            ?: editable.singleOrNull()
    }

    /** 打电话/发消息的联系人恰好和某个应用同名（「打电话给中国移动」）：那是人，不是要打开的应用。 */
    fun namedApps(goal: String, apps: List<AppEntry>, currentPackage: String = ""): List<AppEntry> {
        val contact = IntentParser.parse(goal).takeIf { it.kind != IntentKind.GENERIC }?.contact
        return apps.filter { app ->
            app.packageName != currentPackage &&
                app.label.length >= 2 &&
                goal.contains(app.label, ignoreCase = true) &&
                (contact == null || !(contact.contains(app.label, ignoreCase = true) || app.label.contains(contact, ignoreCase = true)))
        }.distinctBy { it.packageName }
    }

    /**
     * @param goal 用户目标原文。敏感按钮放行、权限弹窗放行、工具可用性都只看它。
     * @param step 当前规划步（有规划器时）。只影响候选说明和输入候选，不参与任何放行判断——
     *   规划器是不可信输出，它写进步骤说明的「确认支付」不能解锁支付按钮。
     */
    fun build(
        goal: String,
        observation: ScreenObservation,
        apps: List<AppEntry>,
        allowGoalUnlock: Boolean = true,
        step: StepContext? = null,
        stepText: String? = null,
        failedTools: Set<String> = emptySet(),
        /** 已经读到的事实（变量名）：对应的读数工具不再提供，值已经在 task.facts 里。 */
        knownFacts: Set<String> = emptySet(),
        /** 设备工具能用的、代码查好的实体（打电话的号码）。 */
        scope: ToolScope = ToolScope(),
    ): ActionCatalog {
        val actions = mutableListOf<CatalogAction>()
        val operations = linkedMapOf<String, String>()
        val tapTargets = linkedMapOf<String, String>()
        val textValues = linkedMapOf<String, String>()
        val appTargets = linkedMapOf<String, String>()
        val longPressTargets = linkedMapOf<String, String>()
        val toolTargets = linkedMapOf<String, String>()
        val sliderTargets = linkedMapOf<String, String>()

        val guard = guardOf(goal, observation, allowGoalUnlock)
        val input = if (guard == null) inputTarget(observation) else null
        // 规划器给的输入文字已经在 PlanPrompts.parsePlan 里校验过是目标原文子串；
        // 这一步指定了文字就只给它（搜会话这一步不该把消息正文也列出来）
        val stepTexts = stepText?.let { listOf(it) } ?: textCandidates(goal)
        // 框里已经是这段文字的不再提供：再输一次屏幕不变，会被判"原地动作无效"
        val texts = if (input != null) stepTexts.filter { !input.text.contains(it) }.distinct().take(MAX_TEXTS) else emptyList()
        val typed = input != null && stepTexts.any { it.isNotEmpty() && input.text.contains(it) }
        val taps = if (guard != null) {
            emptyList()
        } else {
            orderedTaps(goal, observation, allowGoalUnlock)
                // 有文字可输时，输入框本身不再进点击候选：该做的是 TYPE_TEXT，不是再点一次
                .filter { texts.isEmpty() || it.index != input?.index }
                // 文字已经输好：「发送/搜索/确定」排到最前，模型第一眼就看到该提交了
                .let { list -> if (typed) list.sortedBy { if (SUBMIT_WORDS.any { w -> it.text.trim() == w }) 0 else 1 } else list }
        }
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

        if (texts.isNotEmpty() && input != null) {
            operations["TYPE_TEXT"] = "把输入框（${describeTarget(input, typeOf(input))}）替换成目标里给出的原文，不要编造。" +
                "不需要先点输入框，也不需要先清空。"
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
                        // 按坐标找输入框写入：不依赖它有没有报告焦点
                        params = mapOf("text" to value, "mode" to "a11y", "target" to "${input.centerX},${input.centerY}"),
                        // 判重锚点是"这段文字"：同一个框输入不同文字不算重复
                        matchText = value,
                    ),
                )
            }
        }

        val named = namedApps(goal, apps, observation.packageName).take(MAX_APPS)
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
        operations["DONE"] = if (step != null && step.multiStep) {
            "当前这一步已完成：屏幕上已经能看到「${step.doneWhen}」。只判断这一步，不用管整个目标。"
        } else {
            "目标已经在屏幕上完成。只有屏幕上确实出现完成状态才能选。"
        }
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

        // 有输入框时才给"提交"和"清空"。两者都靠 lib 已有节点实现。
        if (input != null) {
            // 聊天页里回车就是发送：屏幕上有「发送」而目标没解锁发送时，不给回车，否则能绕开发送按钮的闸
            val sendVisible = observation.elements.any { it.text.trim() == "发送" }
            if (!sendVisible || allowedTap(goal, "发送", allowGoalUnlock)) {
                operations["PRESS_ENTER"] = "在输入框里按回车，用于提交搜索或确认输入。提交后等待屏幕变化。"
                actions.add(
                    CatalogAction(
                        id = "press_enter",
                        operation = "PRESS_ENTER",
                        label = "按回车提交",
                        // 无障碍 IME 回车，不依赖 Shizuku（真机上 input keyevent 在没开 Shizuku 时必失败）
                        tool = "ACTION_INPUT_TEXT",
                        params = mapOf("text" to "", "submit" to true, "mode" to "a11y", "target" to "${input.centerX},${input.centerY}"),
                    ),
                )
            }
            // 清空不是"删除键按 N 次"（次数猜不准、且每次动作会被判重挡住）：
            // ACTION_SET_TEXT 是整框替换，allow_empty 写入空串即清空。
            operations["CLEAR_TEXT"] = "清空输入框里已有的内容。"
            actions.add(
                CatalogAction(
                    id = "clear_text",
                    operation = "CLEAR_TEXT",
                    label = "清空输入框",
                    tool = "ACTION_INPUT_TEXT",
                    params = mapOf("text" to "", "allow_empty" to true, "mode" to "a11y", "target" to "${input.centerX},${input.centerY}"),
                ),
            )
        }

        // 设备工具快捷方式：跳到设置某页、被目标点名的开关/亮度/音量、拨号。界面里要翻好几屏的事一步做完。
        if (guard == null) {
            val shortcuts = DeviceTools.shortcuts(goal, step?.instruction.orEmpty(), apps, scope)
                .filter { "${it.tool}|${it.args}" !in failedTools }
                .filter { inv -> inv.outputs.isEmpty() || inv.outputs.none { it in knownFacts } }
            if (shortcuts.isNotEmpty()) {
                operations["USE_TOOL"] = "直接调用设备功能（跳到设置某一页、开关蓝牙/WLAN、调亮度音量），不用在界面里翻找。能用就优先用。"
                shortcuts.forEachIndexed { i, inv ->
                    val key = "t${i + 1}"
                    toolTargets[key] = inv.label
                    actions.add(
                        CatalogAction(
                            id = "tool_${inv.tool}_${inv.args.values.joinToString("_")}",
                            operation = "USE_TOOL",
                            target = key,
                            label = inv.label,
                            tool = inv.node,
                            params = inv.params,
                            successFlag = inv.successFlag,
                            outputs = inv.outputs,
                            toolKey = "${inv.tool}|${inv.args}",
                        ),
                    )
                }
            }
        }

        // 滑块：点一下只会跳到点的位置，拖到头才是"最大/最小"
        if (guard == null) {
            observation.elements.filter { it.rangeFraction != null && it.bounds != null && it.enabled }.take(MAX_SLIDERS).forEach { el ->
                val name = el.text.ifBlank { "滑块" }.take(TEXT_PREVIEW)
                val fraction = el.rangeFraction!!
                val percent = (fraction * 100).toInt()
                listOf("max" to "拉到最大", "min" to "拉到最小", "mid" to "拉到中间")
                    // 已经在最大/最小就不再提供同方向的拖动：真机上模型对着 100% 的亮度条反复"拉到最大"直到被判兜圈
                    .filterNot { (pos, _) -> (pos == "max" && fraction >= 0.98) || (pos == "min" && fraction <= 0.02) }
                    .forEach { (pos, verb) ->
                        val key = "${el.index}:$pos"
                        sliderTargets[key] = "$name（当前 $percent%）$verb"
                        actions.add(sliderAction(el, pos, key, "「$name」$verb"))
                    }
            }
            if (sliderTargets.isNotEmpty()) {
                operations["SET_SLIDER"] = "把滑块（亮度条、音量条、进度条）拖到最大/最小/中间。"
            }
        }

        return ActionCatalog(
            actions, operations, tapTargets, textValues, appTargets, longPressTargets, guard,
            toolTargets = toolTargets, sliderTargets = sliderTargets,
            submitReady = typed && taps.firstOrNull()?.let { el -> SUBMIT_WORDS.any { el.text.trim() == it } } == true,
        )
    }

    /** 从滑块当前位置拖到目标位置；拖过头一点（两端各留 1%）保证到头。 */
    private fun sliderAction(el: ScreenElement, pos: String, key: String, label: String): CatalogAction {
        val b = el.bounds!!
        val y = b.centerY
        val from = (b.left + b.width * (el.rangeFraction ?: 0.5)).toInt()
        val to = when (pos) {
            "max" -> b.right - 1
            "min" -> b.left + 1
            else -> b.centerX
        }
        return CatalogAction(
            id = "slider_${el.index}_$pos",
            operation = "SET_SLIDER",
            target = key,
            label = label,
            tool = "ACTION_SWIPE",
            params = mapOf("x1" to from, "y1" to y, "x2" to to, "y2" to y, "duration" to 500),
            matchText = el.text,
            viewId = el.viewId,
        )
    }

    /** 控件状态后缀：开关开/关、灰掉、滑块位置。让模型知道"这个开关已经是开的"。 */
    fun stateOf(el: ScreenElement): String = buildString {
        if (el.checkable) append(if (el.checked == true) "[已开启]" else "[已关闭]")
        if (!el.enabled) append("[不可用]")
        el.rangeFraction?.let {
            append("[当前${(it * 100).toInt()}%")
            if (it >= 0.98) append("，已是最大") else if (it <= 0.02) append("，已是最小")
            append("]")
        }
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
            // 状态栏/灵动岛（vivo 的「电话,8分钟18秒」胶囊）属于系统窗口，点了会跳去别的应用，不是页面控件
            .filter { !it.system }
            // 已经有焦点的输入框再点也不会变（真机上模型反复点搜索框直到被判兜圈），该做的是 TYPE_TEXT
            .filter { !(it.editable && it.focused) }
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
            el.rangeFraction != null -> "滑块"
            el.checkable && el.selectable -> "开关行"
            el.checkable || id.contains("switch") || id.contains("toggle") -> "开关"
            id.contains("checkbox") -> "复选框"
            el.selectable -> "列表行"
            id.contains("tab") -> "标签页"
            id.endsWith("icon") || id.contains("button") || id.contains("btn") -> "按钮"
            else -> if (el.clickable) "按钮" else "标签"
        }
    }

    /**
     * 给模型看的候选条目：`文字（类型）[状态] ⟪id/title⟫`。
     * 类型必须带上：真机上搜索框里残留着「关于手机」，不带类型时模型把输入框当成「关于手机」入口反复点。
     * 输入框写成「输入框，当前内容：…」，与同名的列表行区分开。
     */
    fun describeTarget(el: ScreenElement, type: String): String {
        val id = shortViewId(el.viewId)
        val base = if (el.editable) {
            "输入框" + (if (el.text.isBlank()) "（空）" else "，当前内容：${el.text}")
        } else {
            "${el.text}（$type）"
        } + stateOf(el)
        return if (id.isEmpty()) base else "$base ⟪$id⟫"
    }

    /** `com.android.settings:id/title` → `id/title`，去掉包名前缀省 token。 */
    fun shortViewId(viewId: String): String {
        if (viewId.isEmpty()) return ""
        val slash = viewId.indexOf(":id/")
        return if (slash >= 0) "id/" + viewId.substring(slash + 4) else viewId
    }

    fun allowedTap(goal: String, label: String, allowGoalUnlock: Boolean = true): Boolean {
        val hit = sensitiveWords.firstOrNull { label.contains(it) } ?: return true
        if (!allowGoalUnlock) return false
        return goal.contains(hit) || unlockAliases[hit]?.containsMatchIn(goal) == true
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
