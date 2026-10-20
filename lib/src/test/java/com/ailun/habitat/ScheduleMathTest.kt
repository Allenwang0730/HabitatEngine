package com.ailun.habitat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class ScheduleMathTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()
    private fun fmt(ms: Long) = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), zone).let {
        "%s %02d:%02d".format(it.dayOfWeek, it.hour, it.minute)
    }
    private val daily = DayOfWeek.entries.toSet()

    @Test
    fun parsesTimes() {
        assertEquals(LocalTime.of(8, 0), ScheduleMath.parseTime("08:00"))
        assertEquals(LocalTime.of(8, 5), ScheduleMath.parseTime("8:05"))
        assertEquals(LocalTime.of(21, 30), ScheduleMath.parseTime(" 21：30 "))
        assertNull(ScheduleMath.parseTime("24:00"))
        assertNull(ScheduleMath.parseTime("8点"))
        assertNull(ScheduleMath.parseTime(null))
    }

    @Test
    fun parsesDays() {
        assertEquals(daily, ScheduleMath.parseDays(null))
        assertEquals(daily, ScheduleMath.parseDays("每天"))
        assertEquals((1..5).map(DayOfWeek::of).toSet(), ScheduleMath.parseDays("weekdays"))
        assertEquals((1..5).map(DayOfWeek::of).toSet(), ScheduleMath.parseDays("1-5"))
        assertEquals(setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), ScheduleMath.parseDays("周末"))
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY), ScheduleMath.parseDays("1,3,5"))
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY), ScheduleMath.parseDays("mon, sun"))
        assertEquals(setOf(DayOfWeek.TUESDAY, DayOfWeek.SUNDAY), ScheduleMath.parseDays("周二、星期日"))
        assertNull(ScheduleMath.parseDays("8"))
        assertNull(ScheduleMath.parseDays("5-1"))
        assertNull(ScheduleMath.parseDays("someday"))
    }

    @Test
    fun nextFireToday() {
        // 2026-10-16 是周五
        val next = ScheduleMath.nextFire(ms(2026, 10, 16, 7, 59), LocalTime.of(8, 0), daily, zone)
        assertEquals(ms(2026, 10, 16, 8, 0), next)
    }

    @Test
    fun exactlyAtTimeMeansTomorrow() {
        val next = ScheduleMath.nextFire(ms(2026, 10, 16, 8, 0), LocalTime.of(8, 0), daily, zone)
        assertEquals("刚好到点算已触发过，排到明天", ms(2026, 10, 17, 8, 0), next)
    }

    @Test
    fun weekdaysSkipWeekend() {
        val weekdays = ScheduleMath.parseDays("weekdays")!!
        // 周五 09:00 之后 → 下周一 08:00
        assertEquals("MONDAY 08:00", fmt(ScheduleMath.nextFire(ms(2026, 10, 16, 9, 0), LocalTime.of(8, 0), weekdays, zone)))
        // 周六 → 周一
        assertEquals("MONDAY 08:00", fmt(ScheduleMath.nextFire(ms(2026, 10, 17, 7, 0), LocalTime.of(8, 0), weekdays, zone)))
    }

    @Test
    fun singleDayWrapsAWeek() {
        val friday = setOf(DayOfWeek.FRIDAY)
        assertEquals(ms(2026, 10, 23, 8, 0), ScheduleMath.nextFire(ms(2026, 10, 16, 8, 30), LocalTime.of(8, 0), friday, zone))
    }

    @Test
    fun dstGapMovesForward() {
        // 纽约 2026-03-08 02:30 不存在（夏令时跳过），顺延到 03:30
        val ny = ZoneId.of("America/New_York")
        val now = ZonedDateTime.of(2026, 3, 8, 1, 0, 0, 0, ny).toInstant().toEpochMilli()
        val next = ScheduleMath.nextFire(now, LocalTime.of(2, 30), daily, ny)
        assertEquals(ZonedDateTime.of(2026, 3, 8, 3, 30, 0, 0, ny).toInstant().toEpochMilli(), next)
    }

    @Test
    fun describes() {
        assertEquals("每天 08:00", ScheduleMath.describe(LocalTime.of(8, 0), daily))
        assertEquals("工作日 07:30", ScheduleMath.describe(LocalTime.of(7, 30), ScheduleMath.parseDays("weekdays")!!))
        assertEquals("周一、周三 21:05", ScheduleMath.describe(LocalTime.of(21, 5), ScheduleMath.parseDays("3,1")!!))
    }

    @Test
    fun triggerConfigExposesSchedule() {
        val c = TriggerConfig("timer", mapOf("at" to "08:00", "days" to "weekdays"))
        assertEquals(LocalTime.of(8, 0), c.at)
        assertEquals(5, c.days!!.size)
        assertNull("没写 at 时按间隔定时", TriggerConfig("timer", mapOf("interval_ms" to 60000)).at)
    }
}
