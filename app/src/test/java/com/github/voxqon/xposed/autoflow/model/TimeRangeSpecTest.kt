package com.github.voxqon.xposed.autoflow.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeRangeSpecTest {
    @Test
    fun daytimeRangeIncludesStartAndExcludesEndForBothModes() {
        val range = TimeRangeSpec.parse("09:00-18:00")!!
        assertEquals("09:00-18:00", range.value)
        for (minute in 0..1439) {
            assertEquals(minute in 540 until 1080, range.matches(minute, "inside"))
            assertEquals(minute !in 540 until 1080, range.matches(minute, "outside"))
        }
    }

    @Test
    fun overnightRangeCoversBothSidesOfMidnight() {
        val range = TimeRangeSpec.parse("22:30-07:15")!!
        assertTrue(range.crossesMidnight)
        for (minute in 0..1439) {
            val inside = minute >= 1350 || minute < 435
            assertEquals(inside, range.matches(minute, "inside"))
            assertEquals(!inside, range.matches(minute, "outside"))
        }
    }

    @Test
    fun malformedAndEqualTimesAreRejected() {
        listOf(null, "", "9:00-18:00", "24:00-07:00", "09:60-18:00", "09:00-09:00",
            "09:00-18:00-extra", "-01:00-18:00", "09:00:30-18:00").forEach {
            assertNull(it, TimeRangeSpec.parse(it))
        }
        assertNull(TimeRangeSpec.create(-1, 60))
        assertNull(TimeRangeSpec.create(60, 1440))
        assertFalse(TimeRangeSpec.parse("09:00-18:00")!!.matches(600, "unknown"))
        assertFalse(TimeRangeSpec.parse("09:00-18:00")!!.matches(-1, "outside"))
    }
}
