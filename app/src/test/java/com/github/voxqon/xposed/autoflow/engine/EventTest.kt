package com.github.voxqon.xposed.autoflow.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EventTest {

    @Test
    fun extrasSurviveBridgeSerialization() {
        val event = Event(
            type = EventTypes.APP_FG,
            pkg = "com.example.app",
            extras = mapOf("source" to "systemui", "version" to "2"),
        )

        assertEquals(event, Event.fromJson(event.toJson()))
    }

    @Test
    fun geofenceEventTypeSurvivesLocalSerialization() {
        val event = Event(EventTypes.GEOFENCE, value = "enter", value2 = "31.0,121.0,200.0")

        assertEquals(event, Event.fromJson(event.toJson()))
    }

    @Test
    fun unknownAndOversizedEventsAreRejected() {
        assertNull(Event.fromJson("""{"type":"future_event"}"""))
        assertNull(Event.fromJson("x".repeat(16 * 1024 + 1)))
    }
}
