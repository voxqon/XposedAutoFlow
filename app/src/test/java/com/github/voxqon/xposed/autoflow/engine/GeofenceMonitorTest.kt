package com.github.voxqon.xposed.autoflow.engine

import android.location.Location
import android.os.HandlerThread
import android.os.SystemClock
import com.github.voxqon.xposed.autoflow.hook.GeofenceMonitor
import com.github.voxqon.xposed.autoflow.model.GeofenceSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34])
class GeofenceMonitorTest {
    @Test fun firstFixPublishesStateOnlyAndBoundaryCrossingsKeepTheirOriginalEvents() {
        withMonitor { monitor, events ->
            location(monitor, 0.0)
            assertEquals(listOf("inside"), events.map { it.value })
            events.clear()
            location(monitor, 0.01)
            assertEquals(listOf("outside", "exit"), events.map { it.value })
            events.clear()
            location(monitor, 0.0)
            assertEquals(listOf("inside", "enter"), events.map { it.value })
            assertTrue(events.all { it.value2 == GeofenceSpec(0.0, 0.0, 200.0).id })
        }
    }

    @Test fun staleOrInvalidFixesDoNotCreateStateOrBoundaryEvents() {
        withMonitor { monitor, events ->
            location(monitor, 0.0, System.currentTimeMillis() - 121_000)
            location(monitor, 91.0)
            assertTrue(events.isEmpty())
            location(monitor, 0.0)
            assertEquals(listOf("inside"), events.map { it.value })
        }
    }

    @Test fun wallClockChangesDoNotInvalidateFreshFixesOrRefreshTheirSampleTime() {
        withMonitor { monitor, events ->
            val sampleTime = SystemClock.elapsedRealtimeNanos()
            val fix = Location("network").apply {
                latitude = 0.0
                longitude = 0.0
                accuracy = 5f
                time = System.currentTimeMillis() + 3_600_000L
                elapsedRealtimeNanos = sampleTime
            }
            GeofenceMonitor::class.java.getDeclaredMethod("processLocation", Location::class.java)
                .apply { isAccessible = true }.invoke(monitor, fix)
            assertEquals(listOf("inside"), events.map { it.value })
            assertEquals((sampleTime / 1_000_000L).toString(), events.single().extras["locationElapsedMs"])
        }
    }

    private fun withMonitor(block: (GeofenceMonitor, MutableList<Event>) -> Unit) {
        val events = mutableListOf<Event>()
        val monitor = GeofenceMonitor(RuntimeEnvironment.getApplication()) { events.add(it) }
        fun set(name: String, value: Any) {
            GeofenceMonitor::class.java.getDeclaredField(name).apply { isAccessible = true }.set(monitor, value)
        }
        val fence = GeofenceSpec(0.0, 0.0, 200.0)
        set("fences", mapOf(fence.id to fence))
        set("locationEnabled", true)
        try {
            block(monitor, events)
        } finally {
            val thread = GeofenceMonitor::class.java.getDeclaredField("thread").apply { isAccessible = true }
                .get(monitor) as HandlerThread
            thread.quitSafely()
        }
    }

    private fun location(monitor: GeofenceMonitor, latitude: Double, time: Long = System.currentTimeMillis()) {
        val fix = Location("network").apply {
            this.latitude = latitude
            longitude = 0.0
            accuracy = 5f
            this.time = time
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() +
                (time - System.currentTimeMillis()) * 1_000_000L
        }
        GeofenceMonitor::class.java.getDeclaredMethod("processLocation", Location::class.java)
            .apply { isAccessible = true }.invoke(monitor, fix)
    }
}
