package com.ailun.habitat.handlers

/**
 * [NodeInputTextHandler] 里不依赖 Android 的判定，抽出来单测。
 */
internal object InputTarget {

    /** `"x,y"` → 坐标；格式不对返回 null。 */
    fun parsePoint(spec: String?): Pair<Int, Int>? {
        val parts = spec?.split(",")?.map { it.trim() } ?: return null
        if (parts.size != 2) return null
        val x = parts[0].toIntOrNull() ?: return null
        val y = parts[1].toIntOrNull() ?: return null
        return x to y
    }

    /**
     * 包含 (x,y) 的矩形里面积最小的一个的下标；没有返回 -1。
     * 矩形是 `[left, top, right, bottom]`。嵌套的输入框（外层容器也报 editable）取最里面那个。
     */
    fun smallestContaining(rects: List<IntArray>, x: Int, y: Int): Int {
        var best = -1
        var bestArea = Long.MAX_VALUE
        rects.forEachIndexed { i, r ->
            if (x < r[0] || x >= r[2] || y < r[1] || y >= r[3]) return@forEachIndexed
            val area = (r[2] - r[0]).toLong() * (r[3] - r[1])
            if (area < bestArea) {
                best = i
                bestArea = area
            }
        }
        return best
    }

    /**
     * 写入后框里的文字算不算"已经是要的内容"。忽略空白：号码框会自动插空格（138 0013 8000）。
     * ACTION_SET_TEXT 在部分 App（QQ/微信的自定义输入框）上返回 true 但内容没变，必须读回来核对。
     */
    fun applied(expected: String, actual: CharSequence?): Boolean {
        val want = expected.filterNot { it.isWhitespace() }
        val got = actual?.toString().orEmpty().filterNot { it.isWhitespace() }
        return if (want.isEmpty()) got.isEmpty() else got.contains(want)
    }
}
