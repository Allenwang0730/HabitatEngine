package com.ailun.habitat

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 按时刻定时（`"trigger":{"type":"timer","at":"08:00","days":"weekdays"}`）的纯计算：解析时刻、星期，算下一次触发时间。
 * 宿主用系统闹钟（AlarmManager）按 [nextFire] 安排；进程被杀也会按时拉起。
 */
object ScheduleMath {

    private val TIME = Regex("""^\s*([01]?\d|2[0-3])[:：]([0-5]\d)\s*$""")

    private val NAMES: Map<String, DayOfWeek> = mapOf(
        "mon" to DayOfWeek.MONDAY, "tue" to DayOfWeek.TUESDAY, "wed" to DayOfWeek.WEDNESDAY,
        "thu" to DayOfWeek.THURSDAY, "fri" to DayOfWeek.FRIDAY, "sat" to DayOfWeek.SATURDAY, "sun" to DayOfWeek.SUNDAY,
        "一" to DayOfWeek.MONDAY, "二" to DayOfWeek.TUESDAY, "三" to DayOfWeek.WEDNESDAY, "四" to DayOfWeek.THURSDAY,
        "五" to DayOfWeek.FRIDAY, "六" to DayOfWeek.SATURDAY, "日" to DayOfWeek.SUNDAY, "天" to DayOfWeek.SUNDAY,
    )

    /** "08:00" / "8:05" / "08：00" → 时刻；不合法返回 null。 */
    fun parseTime(value: String?): LocalTime? {
        val m = TIME.matchEntire(value ?: return null) ?: return null
        return LocalTime.of(m.groupValues[1].toInt(), m.groupValues[2].toInt())
    }

    /**
     * 星期：空 / daily / 每天 = 每天；weekdays / 工作日 = 周一到周五；weekends / 周末；
     * 否则逗号分隔的 1-7（1=周一）、区间 "1-5"、英文缩写 mon..sun、中文 一..日。不合法返回 null。
     */
    fun parseDays(value: String?): Set<DayOfWeek>? {
        val v = value?.trim()?.lowercase().orEmpty()
        when (v) {
            "", "daily", "everyday", "every day", "每天", "每日" -> return DayOfWeek.entries.toSet()
            "weekdays", "workdays", "工作日" -> return (1..5).map(DayOfWeek::of).toSet()
            "weekends", "weekend", "周末" -> return setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        }
        val out = mutableSetOf<DayOfWeek>()
        for (raw in v.split(',', '，', '、', ' ').map { it.trim().removePrefix("周").removePrefix("星期") }.filter { it.isNotEmpty() }) {
            val range = Regex("""^([1-7])-([1-7])$""").matchEntire(raw)
            when {
                range != null -> {
                    val (a, b) = range.destructured
                    if (a.toInt() > b.toInt()) return null
                    (a.toInt()..b.toInt()).forEach { out += DayOfWeek.of(it) }
                }
                raw.length == 1 && raw[0] in '1'..'7' -> out += DayOfWeek.of(raw.toInt())
                raw.take(3) in NAMES -> out += NAMES.getValue(raw.take(3))
                raw in NAMES -> out += NAMES.getValue(raw)
                else -> return null
            }
        }
        return out.takeIf { it.isNotEmpty() }
    }

    /** [now] 之后（严格晚于）下一个在 [days] 里、时刻为 [at] 的时间点（毫秒）。 */
    fun nextFire(nowMs: Long, at: LocalTime, days: Set<DayOfWeek>, zone: ZoneId = ZoneId.systemDefault()): Long {
        require(days.isNotEmpty())
        val now = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowMs), zone)
        var date: LocalDate = now.toLocalDate()
        repeat(8) {
            // ZonedDateTime.of 遇到夏令时跳过的时刻会顺延到有效时间
            val candidate = ZonedDateTime.of(date, at, zone)
            if (candidate.dayOfWeek in days && candidate.isAfter(now)) return candidate.toInstant().toEpochMilli()
            date = date.plusDays(1)
        }
        error("unreachable: days=$days")
    }

    /** 给人看的描述：「每天 08:00」「工作日 08:00」「周一、周三 21:30」。 */
    fun describe(at: LocalTime, days: Set<DayOfWeek>): String {
        val time = "%02d:%02d".format(at.hour, at.minute)
        val label = when (days) {
            DayOfWeek.entries.toSet() -> "每天"
            (1..5).map(DayOfWeek::of).toSet() -> "工作日"
            setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) -> "周末"
            else -> days.sorted().joinToString("、") { "周" + "一二三四五六日"[it.value - 1] }
        }
        return "$label $time"
    }
}
