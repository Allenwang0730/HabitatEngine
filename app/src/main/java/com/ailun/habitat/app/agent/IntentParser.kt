package com.ailun.habitat.app.agent

/** 目标的意图类型。只区分"有固定做法"的几类，其余都是 GENERIC，交给规划器。 */
enum class IntentKind { CALL, SEND_MESSAGE, GENERIC }

/**
 * 目标的结构化意图：类型 + 槽位。
 *
 * 不做自由的 query 改写：改写出来的文字没法再核对"是不是用户说的"。
 * 这里每个槽位都是目标原文的子串（号码除外，号码由 [ServiceNumbers]/通讯录在本机查），
 * 所以"输入内容必须来自目标原文""改设备状态要目标原文点名"这两条不变式照旧成立。
 */
data class ParsedIntent(
    val kind: IntentKind,
    /** 打给谁 / 发给谁。 */
    val contact: String? = null,
    /** 要发送的文字。 */
    val text: String? = null,
    /** 发消息的渠道：qq / wechat / sms；目标没说为 null。 */
    val channel: String? = null,
)

/**
 * 规则解析，零延迟、可单测。只覆盖高频句式，没把握就返回 GENERIC——
 * 解析错了比不解析更糟（「打开中国移动」不能被当成打电话）。
 */
object IntentParser {
    private const val PUNCT = "，。,.！!？?；;：:\\s"
    private const val NAME = "[^$PUNCT「」『』\"“”'‘’]"
    /** 名字后面到这些位置为止：标点、句尾、「说」「然后」这类接下一件事的词。 */
    private const val STOP = "(?=$|[$PUNCT]|说|并|然后|告诉|内容|吧)"

    private val CALL_PATTERNS = listOf(
        Regex("(?:拨打电话给|打(?:个|一个|一通)?电话给|打给|拨给|拨打|呼叫|致电)\\s*($NAME{1,20}?)(?:的电话|的手机)?$STOP"),
        Regex("给\\s*($NAME{1,20}?)\\s*(?:打|拨)(?:个|一个|一通)?电话"),
    )
    /** 这些是应用内通话或设置项，不是拨号。 */
    private val NOT_A_CALL = Regex("微信|qq|视频|语音|网络电话", RegexOption.IGNORE_CASE)
    private val NOT_A_CONTACT = setOf("转移", "等待", "设置", "记录", "电话", "号码")

    private const val SEND = "发(?:个|一个|一条|条)?(?:消息|信息|短信|微信|qq消息|QQ消息)"
    private val MSG_PATTERNS = listOf(
        Regex("给\\s*($NAME{1,20}?)\\s*$SEND"),
        Regex("$SEND\\s*给\\s*($NAME{1,20}?)$STOP"),
    )
    /** 「把『你好』发给张三」：文字在前，联系人在后。必须带引号——「把这张图片发给张三」里没有要输入的文字。 */
    private val MSG_TEXT_FIRST = Regex("把\\s*[「『\"“']([^」』\"”']{1,60})[」』\"”']\\s*发(?:送)?给\\s*($NAME{1,20}?)$STOP")
    /** 联系人之后的消息内容：「说…」「说：…」「内容是…」「：…」，到句尾或「，然后…」为止。 */
    private val MSG_TEXT = Regex(
        "(?:(?:说|内容是|内容为|告诉(?:他|她|ta|TA))\\s*[：:]?|[：:])\\s*[「『\"“']?(.{1,60}?)[」』\"”']?[。.!！~]?\\s*(?:[，,。]\\s*(?:然后|再|之后|并且).*)?$",
    )

    private val QUOTED = Regex("[\"「『“'‘]([^\"」』”'’]{1,40})[\"」』”'’]")
    private val TYPE_TRIGGER = Regex(
        "(?:输入|填写|填入|键入|搜索(?![框栏结果页按])|搜一下|搜(?![索一])|查找|查一下)\\s*" +
            "([^「」『』\"“”'‘’$PUNCT]{1,40}?)(?=$|[$PUNCT]|并|然后|再|后|的结果)",
    )
    private const val MAX_TEXTS = 6

    fun parse(goal: String): ParsedIntent {
        val g = goal.trim()
        parseMessage(g)?.let { return it }
        parseCall(g)?.let { return it }
        return ParsedIntent(IntentKind.GENERIC)
    }

    private fun parseCall(goal: String): ParsedIntent? {
        if (NOT_A_CALL.containsMatchIn(goal)) return null
        for (pattern in CALL_PATTERNS) {
            val contact = pattern.find(goal)?.groupValues?.get(1)?.trim().orEmpty()
            if (contact.isNotEmpty() && NOT_A_CONTACT.none { contact.contains(it) }) {
                return ParsedIntent(IntentKind.CALL, contact = contact)
            }
        }
        return null
    }

    private fun parseMessage(goal: String): ParsedIntent? {
        val channel = channelOf(goal)
        MSG_TEXT_FIRST.find(goal)?.let { m ->
            val text = m.groupValues[1].trim()
            val contact = m.groupValues[2].trim()
            if (text.isNotEmpty() && contact.isNotEmpty()) return ParsedIntent(IntentKind.SEND_MESSAGE, contact, text, channel)
        }
        for (pattern in MSG_PATTERNS) {
            val m = pattern.find(goal) ?: continue
            val contact = m.groupValues[1].trim()
            if (contact.isEmpty()) continue
            val rest = goal.substring(m.range.last + 1)
            val text = MSG_TEXT.find(rest)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
                ?: QUOTED.find(goal)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
            return ParsedIntent(IntentKind.SEND_MESSAGE, contact, text, channel)
        }
        return null
    }

    private fun channelOf(goal: String): String? {
        val g = goal.lowercase()
        return when {
            g.contains("qq") -> "qq"
            g.contains("微信") || g.contains("wechat") -> "wechat"
            g.contains("短信") -> "sms"
            else -> null
        }
    }

    /**
     * 目标里可以输入的原文，按优先级：要发送的文字 → 引号原文 → 「输入/搜索 X」→ 发消息的联系人（搜会话用）。
     * 不要求「输入」后面有空格：中文里没人写「输入 电池」。
     */
    fun inputTexts(goal: String): List<String> {
        val intent = parse(goal)
        val out = linkedSetOf<String>()
        intent.text?.let(out::add)
        QUOTED.findAll(goal).forEach { out += it.groupValues[1].trim() }
        TYPE_TRIGGER.findAll(goal).forEach { out += it.groupValues[1].trim() }
        if (intent.kind == IntentKind.SEND_MESSAGE) intent.contact?.let(out::add)
        return out.filter { it.isNotEmpty() }.take(MAX_TEXTS)
    }
}
