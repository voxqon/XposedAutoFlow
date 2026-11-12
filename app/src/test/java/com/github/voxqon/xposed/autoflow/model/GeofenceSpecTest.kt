package com.github.voxqon.xposed.autoflow.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeofenceSpecTest {
    @Test
    fun parsesBoundedCoordinatesAndCanonicalizesEquivalentInput() {
        val integerInput = GeofenceSpec.parse("31,121,200")
        val decimalInput = GeofenceSpec.parse(" 31.0, 121.000, 200.0 ")

        assertNotNull(integerInput)
        assertEquals(integerInput?.id, decimalInput?.id)
    }

    @Test
    fun rejectsMalformedOrOutOfRangeFenceValues() {
        listOf(null, "", "31,121", "91,121,200", "31,181,200", "31,121,0", "31,121,100001", "NaN,1,10")
            .forEach { assertNull(GeofenceSpec.parse(it)) }
    }

    @Test
    fun directionMatchingIsExplicitAndDefaultsToEitherEdge() {
        assertTrue(GeofenceSpec.directionMatches(null, "enter"))
        assertTrue(GeofenceSpec.directionMatches("any", "exit"))
        assertTrue(GeofenceSpec.directionMatches("enter", "enter"))
        assertFalse(GeofenceSpec.directionMatches("enter", "exit"))
        assertFalse(GeofenceSpec.directionMatches("unsupported", "enter"))
    }

    @Test
    fun distanceUsesEarthSurfaceDistance() {
        val fence = GeofenceSpec.parse("0,0,1000")!!

        assertEquals(0.1112, fence.distanceTo(0.0, 0.001) / 1000.0, 0.0005)
    }

    @Test
    fun boundaryHysteresisPreventsSmallLocationFluctuationsFromFlippingState() {
        val fence = GeofenceSpec.parse("0,0,100")!!

        assertFalse(fence.isInside(95.0, false, 20.0))
        assertTrue(fence.isInside(89.0, false, 20.0))
        assertTrue(fence.isInside(105.0, true, 20.0))
        assertFalse(fence.isInside(111.0, true, 20.0))
        assertTrue(fence.isInside(50.0, null, 20.0))
    }
}
