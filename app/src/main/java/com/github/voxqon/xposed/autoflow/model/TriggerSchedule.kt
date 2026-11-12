package com.github.voxqon.xposed.autoflow.model

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Weekday bit 0 is Monday; bit 6 is Sunday. */
object TriggerSchedule {
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE", Locale.CHINA)
    private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE HH:mm", Locale.CHINA)
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)

    fun allows(mask: Int, date: java.time.LocalDate): Boolean {
        val validMask = mask and ALL_DAYS_MASK
        return validMask == 0 || (validMask and (1 shl (date.dayOfWeek.value - 1))) != 0
    }

    fun allows(mode: ScheduleMode, mask: Int, date: java.time.LocalDate): Boolean = when (mode) {
        ScheduleMode.EVERYDAY -> true
        ScheduleMode.WEEKDAYS -> date.dayOfWeek.value in 1..5
        ScheduleMode.WEEKEND -> date.dayOfWeek.value >= 6
        ScheduleMode.WORKDAY -> ChinaHoliday.isWorkday(date)
        ScheduleMode.HOLIDAY -> !ChinaHoliday.isWorkday(date)
        ScheduleMode.CUSTOM_DAYS -> allows(mask, date)
    }

    fun allowsAtMillis(mask: Int, timeMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean =
        allows(mask, Instant.ofEpochMilli(timeMillis).atZone(zone).toLocalDate())

    fun allowsAtMillis(
        mode: ScheduleMode,
        mask: Int,
        timeMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Boolean = allows(mode, mask, Instant.ofEpochMilli(timeMillis).atZone(zone).toLocalDate())

    fun nextAllowedAtMillis(mask: Int, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        for (offset in 0L..7L) {
            val date = now.toLocalDate().plusDays(offset)
            if (!allows(mask, date)) continue
            return if (offset == 0L) nowMillis else date.atStartOfDay(zone).toInstant().toEpochMilli()
        }
        return nowMillis
    }

    /**
     * 下一次允许触发的时刻。minuteOfDay 非空时按当天该时刻计算（已过则顺延到下个允许日），
     * 为空时今天允许则返回 now，否则返回下个允许日的 00:00。
     */
    fun nextAllowedAtMillis(
        mode: ScheduleMode,
        mask: Int,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        minuteOfDay: Int? = null,
    ): Long {
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        for (offset in 0L..366L) {
            val date = now.toLocalDate().plusDays(offset)
            if (!allows(mode, mask, date)) continue
            if (minuteOfDay == null) {
                return if (offset == 0L) nowMillis else date.atStartOfDay(zone).toInstant().toEpochMilli()
            }
            val candidate = date.atTime(minuteOfDay / 60, minuteOfDay % 60).atZone(zone)
            if (offset == 0L && candidate.toInstant().toEpochMilli() <= nowMillis) continue
            return candidate.toInstant().toEpochMilli()
        }
        return nowMillis
    }

    fun nextAllowedText(mask: Int, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val validMask = mask and ALL_DAYS_MASK
        if (validMask == ALL_DAYS_MASK || validMask == 0) {
            return "下次可执行：现在（日期不限；需满足条件与冷却）"
        }
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val next = Instant.ofEpochMilli(nextAllowedAtMillis(mask, nowMillis, zone)).atZone(zone)
        return if (next.toLocalDate() == now.toLocalDate()) {
            "下次可执行：现在（今天允许；需满足条件与冷却）"
        } else {
            "下次可执行：${dateFormatter.format(next)}（需满足条件与冷却）"
        }
    }

    /** 列表展示：结合日期模式与定时条件（timerMinutes）给出下一次可执行时刻。 */
    fun nextAllowedText(
        mode: ScheduleMode,
        mask: Int,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        timerMinutes: List<Int> = emptyList(),
    ): String {
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val nextMillis = timerMinutes
            .map { nextAllowedAtMillis(mode, mask, nowMillis, zone, it) }
            .minOrNull() ?: nextAllowedAtMillis(mode, mask, nowMillis, zone)
        val next = Instant.ofEpochMilli(nextMillis).atZone(zone)
        val sameDay = next.toLocalDate() == now.toLocalDate()
        if (timerMinutes.isEmpty()) {
            return if (mode == ScheduleMode.EVERYDAY) {
                if (sameDay) "下次可执行：现在（需满足条件与冷却）"
                else "下次可执行：${dateTimeFormatter.format(next)}（需满足条件与冷却）"
            } else if (sameDay) {
                "下次可执行：今天（当天允许；需满足条件与冷却）"
            } else {
                "下次可执行：${dateFormatter.format(next)}（当天允许；需满足条件与冷却）"
            }
        }
        return if (sameDay) {
            "下次可执行：今天 ${timeFormatter.format(next)}（需满足条件与冷却）"
        } else {
            "下次可执行：${dateTimeFormatter.format(next)}（需满足条件与冷却）"
        }
    }
}
