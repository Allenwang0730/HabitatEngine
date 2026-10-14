package com.ailun.habitat.app.agent

/**
 * 高频意图的固定做法：意图和槽位齐全时直接出计划，不经过 LLM 规划。
 *
 * 为什么：规划器每次都要重新"想"打电话、发消息怎么做，想错一次就是"打开了中国移动 App"
 * 或"点了输入框不输入"。这几件事的步骤是固定的，代码写死更稳，也省一次请求。
 * 执行还是交给执行器一步步点；完成判定用 [StepCheck]，不靠模型自己说做完了。
 */
object SkillPlans {

    sealed interface Result {
        data class Ready(val plan: Plan) : Result
        /** 缺了关键信息，宁可不做也不瞎做。 */
        data class Refuse(val reason: String) : Result
        /** 不是这里覆盖的意图，交给规划器。 */
        data object None : Result
    }

    val CHAT_APPS: Map<String, List<String>> = mapOf(
        "qq" to listOf("com.tencent.mobileqq", "com.tencent.tim", "com.tencent.qqlite"),
        "wechat" to listOf("com.tencent.mm"),
    )
    private val CHANNEL_NAME = mapOf("qq" to "QQ", "wechat" to "微信")

    /**
     * @param contactsReadable 没查到号码时用来区分"通讯录没这个人"和"没给通讯录权限"。
     * @param allowSend 远程发起为 false：只把消息填好，不点发送。
     */
    fun build(
        intent: ParsedIntent,
        goal: String,
        apps: List<AppEntry>,
        scope: ToolScope,
        contactsReadable: Boolean,
        allowSend: Boolean,
    ): Result = when (intent.kind) {
        IntentKind.CALL -> call(intent, goal, apps, scope, contactsReadable)
        IntentKind.SEND_MESSAGE -> message(intent, apps, allowSend)
        IntentKind.GENERIC -> Result.None
    }

    private fun call(intent: ParsedIntent, goal: String, apps: List<AppEntry>, scope: ToolScope, contactsReadable: Boolean): Result {
        val name = intent.contact ?: return Result.None
        val inv = DeviceTools.resolve("call", emptyMap(), goal, apps, scope)
        if (inv == null) {
            val why = if (contactsReadable) "通讯录和常用客服号码里都没有「$name」" else "没查到「$name」的号码（未授予通讯录权限）"
            return Result.Refuse("$why，没有拨号")
        }
        val direct = inv.node == "ACTION_CALL_DIRECT"
        return Result.Ready(
            Plan(
                listOf(PlanStep(inv.label, "已进入拨号/通话界面", tool = "call")),
                summary = inv.label,
                // 只填号码时有意不拨出：不走复核，否则复核判"没在通话"会重规划去点拨号键
                note = if (direct) "" else "已打开拨号盘并填好「$name」的号码，请手动拨出" +
                    if (scope.allowDirectCall) "（紧急号码不自动拨出）" else "（远程发起或未授予拨号权限时不自动拨出）",
            ),
        )
    }

    private fun message(intent: ParsedIntent, apps: List<AppEntry>, allowSend: Boolean): Result {
        val contact = intent.contact ?: return Result.None
        val channel = intent.channel ?: return Result.None
        val packages = CHAT_APPS[channel] ?: return Result.None
        val appName = CHANNEL_NAME.getValue(channel)
        val app = apps.firstOrNull { it.packageName in packages } ?: return Result.Refuse("没有安装$appName")
        val text = intent.text ?: return Result.Refuse("目标里没说要发什么内容，没有发送")
        val steps = mutableListOf(
            PlanStep("打开${app.label}", "已在${app.label}里", tool = "open_app", args = mapOf("package" to app.packageName)),
            PlanStep(
                "进入与「$contact」的聊天窗口：在消息列表里直接点它，看不到就点顶部搜索框搜「$contact」再点结果",
                "聊天窗口顶部标题是「$contact」",
                text = contact,
                check = StepCheck.ChatWith(contact),
                autoTap = contact,
            ),
            PlanStep("在聊天输入框里输入「$text」", "输入框里是「$text」", text = text, check = StepCheck.InputHas(text)),
        )
        if (allowSend) {
            // 带上 text：框里已经是它 → 不再提供输入，「发送」排到候选最前
            steps += PlanStep("点「发送」", "聊天记录里出现「$text」，输入框已清空", text = text, check = StepCheck.Sent(text))
        }
        return Result.Ready(
            Plan(
                steps,
                summary = "用$appName 给「$contact」发「$text」",
                note = if (allowSend) "" else "远程发起的任务不自动发送，消息已填好，请在手机上确认后点发送",
                finishWhen = if (allowSend) StepCheck.Sent(text) else StepCheck.InputHas(text),
            ),
        )
    }
}
