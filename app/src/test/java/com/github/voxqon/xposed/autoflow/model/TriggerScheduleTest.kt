package com.github.voxqon.xposed.autoflow.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class TriggerScheduleTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    private val holidayData: Map<Int, Map<LocalDate, Boolean>> = mapOf(
        2026 to mapOf(
            LocalDate.of(2026, 1, 1) to true,   // 元旦法定休息
            LocalDate.of(2026, 2, 28) to false, // 春节调休补班
            LocalDate.of(2026, 10, 10) to false, // 国庆调休补班
        ),
    )

    @Test
    fun weekdayAndWeekendMasksUseMondayFirstBits() {
        val weekdays = 0b0011111
        val weekend = 0b1100000

        assertTrue(TriggerSchedule.allows(weekdays, LocalDate.of(2026, 11, 9)))
        assertFalse(TriggerSchedule.allows(weekdays, LocalDate.of(2026, 11, 14)))
        assertTrue(TriggerSchedule.allows(weekend, LocalDate.of(2026, 11, 14)))
        assertFalse(TriggerSchedule.allows(weekend, LocalDate.of(2026, 11, 13)))
    }

    @Test
    fun nextAllowedTimeIsNowForTodayOrMidnightForNextAllowedDay() {
        val now = LocalDate.of(2026, 11, 9).atTime(12, 30).atZone(zone).toInstant().toEpochMilli()
        val sunday = LocalDate.of(2026, 11, 8).atTime(12, 30).atZone(zone).toInstant().toEpochMilli()
        val nextMonday = LocalDate.of(2026, 11, 9).atStartOfDay(zone).toInstant().toEpochMilli()

        assertEquals(now, TriggerSchedule.nextAllowedAtMillis(0b0011111, now, zone))
        assertEquals(nextMonday, TriggerSchedule.nextAllowedAtMillis(0b0011111, sunday, zone))
    }

    @Test
    fun allDaysAreAlwaysAllowedIncludingLegacyZeroMask() {
        assertTrue(TriggerSchedule.allows(ALL_DAYS_MASK, LocalDate.of(2026, 11, 8)))
        assertTrue(TriggerSchedule.allows(0, LocalDate.of(2026, 11, 8)))
    }

    @Test
    fun statutoryHolidayAndMakeUpWorkdayFollowChinaHolidayData() {
        com.github.voxqon.xposed.autoflow.model.ChinaHoliday.setDataForTest(holidayData)
        // 2026-01-01 是法定休息日，不是普通工作日
        assertFalse(TriggerSchedule.allows(
            ScheduleMode.WORKDAY, ALL_DAYS_MASK, LocalDate.of(2026, 1, 1)))
        assertTrue(TriggerSchedule.allows(
            ScheduleMode.HOLIDAY, ALL_DAYS_MASK, LocalDate.of(2026, 1, 1)))
        // 2026-02-28 是周六，但调休补班，应该算工作日
        assertTrue(TriggerSchedule.allows(
            ScheduleMode.WORKDAY, ALL_DAYS_MASK, LocalDate.of(2026, 2, 28)))
        assertFalse(TriggerSchedule.allows(
            ScheduleMode.HOLIDAY, ALL_DAYS_MASK, LocalDate.of(2026, 2, 28)))
        // 未收录的普通周六仍按休息日
        assertFalse(TriggerSchedule.allows(
            ScheduleMode.WORKDAY, ALL_DAYS_MASK, LocalDate.of(2026, 11, 14)))
        assertTrue(TriggerSchedule.allows(
            ScheduleMode.HOLIDAY, ALL_DAYS_MASK, LocalDate.of(2026, 11, 14)))
    }

    @Test
    fun nextAllowedTextUsesTimerMinutesForTodayAndSkipsToNextAllowedDay() {
        com.github.voxqon.xposed.autoflow.model.ChinaHoliday.setDataForTest(holidayData)
        val now = LocalDate.of(2026, 10, 9).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val text = TriggerSchedule.nextAllowedText(
            ScheduleMode.WORKDAY, ALL_DAYS_MASK, now, zone, timerMinutes = listOf(11 * 60 + 30))
        assertEquals("下次可执行：今天 11:30（需满足条件与冷却）", text)

        val after = LocalDate.of(2026, 10, 10).atTime(15, 0).atZone(zone).toInstant().toEpochMilli()
        // 2026-10-10 是调休补班（周六），当天 09:00 已过；11 号周日休息，12 号周一为下一个工作日
        val nextText = TriggerSchedule.nextAllowedText(
            ScheduleMode.WORKDAY, ALL_DAYS_MASK, after, zone, timerMinutes = listOf(9 * 60))
        val expected = LocalDate.of(2026, 10, 12).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals("下次可执行：2026年10月12日 星期一 09:00（需满足条件与冷却）", nextText)
        assertEquals(expected, TriggerSchedule.nextAllowedAtMillis(
            ScheduleMode.WORKDAY, ALL_DAYS_MASK, after, zone, 9 * 60))
        com.github.voxqon.xposed.autoflow.model.ChinaHoliday.setDataForTest(null)
    }

    @Test
    fun nonHolidayDatesInCachedYearFallBackToCalendarWithoutReloading() {
        com.github.voxqon.xposed.autoflow.model.ChinaHoliday.setDataForTest(holidayData)
        // 2026-11-09 is a Monday with no holiday-cn entry; it must still be a
        // workday (calendar weekday) without relying on the holiday map.
        assertTrue(TriggerSchedule.allows(
            ScheduleMode.WORKDAY, ALL_DAYS_MASK, LocalDate.of(2026, 11, 9)))
        assertFalse(TriggerSchedule.allows(
            ScheduleMode.HOLIDAY, ALL_DAYS_MASK, LocalDate.of(2026, 11, 9)))
        // 2026-11-14 is a Saturday with no entry; calendar says rest day.
        assertFalse(TriggerSchedule.allows(
            ScheduleMode.WORKDAY, ALL_DAYS_MASK, LocalDate.of(2026, 11, 14)))
        // Missing year (2028 has no test data) falls back to plain calendar.
        assertTrue(TriggerSchedule.allows(
            ScheduleMode.WORKDAY, ALL_DAYS_MASK, LocalDate.of(2028, 11, 13)))
        assertFalse(TriggerSchedule.allows(
            ScheduleMode.WORKDAY, ALL_DAYS_MASK, LocalDate.of(2028, 11, 18)))
        com.github.voxqon.xposed.autoflow.model.ChinaHoliday.setDataForTest(null)
    }
}
