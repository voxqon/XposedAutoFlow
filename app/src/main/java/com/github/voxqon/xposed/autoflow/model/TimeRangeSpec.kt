package com.github.voxqon.xposed.autoflow.model

import java.util.Locale

/** Daily local time window, including its start and excluding its end. */
data class TimeRangeSpec private constructor(val startMinute: Int, val endMinute: Int) {
    val crossesMidnight: Boolean get() = endMinute < startMinute
    val startText: String get() = formatMinute(startMinute)
    val endText: String get() = formatMinute(endMinute)
    val value: String get() = "$startText-$endText"

    fun matches(minute: Int, mode: String?): Boolean {
        if (minute !in 0..1439 || !isValidMode(mode)) return false
        val inside = if (crossesMidnight) minute >= startMinute || minute < endMinute
            else minute >= startMinute && minute < endMinute
        return if (mode == "outside") !inside else inside
    }

    companion object {
        fun create(startMinute: Int, endMinute: Int): TimeRangeSpec? =
            if (startMinute in 0..1439 && endMinute in 0..1439 && startMinute != endMinute) {
                TimeRangeSpec(startMinute, endMinute)
            } else null

        fun parse(value: String?): TimeRangeSpec? {
            val parts = value?.split('-')?.takeIf { it.size == 2 } ?: return null
            return create(parseMinute(parts[0]) ?: return null, parseMinute(parts[1]) ?: return null)
        }

        fun isValidMode(mode: String?): Boolean = mode == null || mode == "inside" || mode == "outside"

        fun formatMinute(minute: Int): String =
            String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60)

        fun parseMinute(text: String): Int? {
            if (text.length != 5 || text[2] != ':' ||
                text.filterIndexed { index, _ -> index != 2 }.any { it !in '0'..'9' }) return null
            val hour = text.substring(0, 2).toIntOrNull()?.takeIf { it in 0..23 } ?: return null
            val minute = text.substring(3, 5).toIntOrNull()?.takeIf { it in 0..59 } ?: return null
            return hour * 60 + minute
        }
    }
}
