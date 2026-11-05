package com.ailun.habitat.app.agent

/**
 * 提问类目标的答案整形：只按**目标里写明的格式**收拾复核员给的答案，目标没提格式就只做去空白、去引号。
 *
 * 为什么要有：调用方（评测器、别的程序）常按格式严格比对。AndroidWorld 的判分器按逗号拆开、数字用 float()、
 * True/False 原样比较——复核员偶尔写成「答案是 3。」「3 items」「Yes」就全错。提示词里已经要求只写答案本身，
 * 这里是兜底，不猜目标没要求的格式。
 */
object AnswerFormat {
    private val LEAD = Regex("""^(?i)(?:the\s+)?answer\s*(?:is|:)\s*|^答案\s*[是为:：]\s*""")
    private val NUMBER = Regex("""-?\d[\d,]*(?:\.\d+)?""")
    private val ASKS_NUMBER = Regex("""(?i)\b(?:single (?:integer|number)|just a (?:single )?number|as a number|only the number|an integer|nearest integer)\b|只(?:回答|给|写)?(?:一个)?数字""")
    private val ASKS_BOOL = Regex("""(?i)\b(?:'True'|"True"|True)\b.*\b(?:'False'|"False"|False)\b""")
    private val QUESTION = Regex(
        """(?i)^\s*(?:what|how|which|when|where|who|is|are|do|does|did|was|were|has|have|can)\b|\?\s*$|\b(?:express your answer|answer with|answer in|format your answer)\b|[？]|(?:多少|几个|几点|哪[个些天里]|是什么|是否|有没有|吗)\s*[？?]?\s*$""",
    )

    /** 目标是不是一个提问（要给出答案，而不是改设备状态）。 */
    fun isQuestion(goal: String): Boolean = QUESTION.containsMatchIn(goal.trim())

    fun normalize(goal: String, raw: String): String {
        var a = raw.trim()
        if (a.isEmpty()) return a
        a = LEAD.replace(a, "").trim()
        a = a.trim('"', '\'', '“', '”', '‘', '’', '「', '」', '`').trim()
        if (ASKS_BOOL.containsMatchIn(goal)) {
            val v = a.lowercase().trimEnd('.', '。', '!', '！')
            when {
                v == "true" || v == "yes" || v == "是" || v.startsWith("true") || v.startsWith("yes") -> return "True"
                v == "false" || v == "no" || v == "否" || v == "不是" || v.startsWith("false") || v.startsWith("no") -> return "False"
            }
        }
        if (ASKS_NUMBER.containsMatchIn(goal)) {
            // 只有一个数时才抽出来：「3 items」→「3」，「1,234」→「1234」；有好几个数时不猜
            val nums = NUMBER.findAll(a).map { it.value }.toList()
            if (nums.size == 1) return nums.single().replace(",", "").removeSuffix(".0").let { if (it.endsWith(".")) it.dropLast(1) else it }
        }
        // 句末标点不属于答案（「Quarterly review.」）；中间的逗号是多答案分隔，保留
        return a.trimEnd('.', '。')
    }
}
