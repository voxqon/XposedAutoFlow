package com.github.voxqon.xposed.autoflow.hook

import android.annotation.SuppressLint
import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.github.voxqon.xposed.autoflow.engine.Event
import com.github.voxqon.xposed.autoflow.engine.EventTypes
import com.github.voxqon.xposed.autoflow.model.Condition
import com.github.voxqon.xposed.autoflow.model.ConditionType
import com.github.voxqon.xposed.autoflow.model.GeofenceSpec
import com.github.voxqon.xposed.autoflow.util.XLog

/** Low-rate system location listener. Geofence transitions are delivered directly to the system_server engine. */
class GeofenceMonitor(
    context: Context,
    private val emit: (Event) -> Unit,
) {
    private val locationManager = runCatching {
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    }.getOrNull()
    private val thread = HandlerThread("atlt-geofence").apply { start() }
    private val handler = Handler(thread.looper)
    private var fences: Map<String, GeofenceSpec> = emptyMap()
    private val insideById = HashMap<String, Boolean>()
    private var locationEnabled = false
    private var registeredProvider: String? = null

    private val retryStart = Runnable { startUpdates() }
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            runCatching { processLocation(location) }
                .onFailure { XLog.e("geofence location evaluation failed", it) }
        }
        override fun onProviderDisabled(provider: String) {
            if (provider == registeredProvider) {
                invalidateLocations()
                stopUpdates(cancelRetry = false)
                startUpdates()
            }
        }
        override fun onProviderEnabled(provider: String) {
            if (registeredProvider == null) startUpdates()
        }
        @Suppress("DEPRECATION")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
    }

    /** Called by RuleEngine after rule refreshes and location-mode changes. */
    fun sync(conditions: List<Condition>, enabled: Boolean) {
        handler.post {
            locationEnabled = locationManager?.let { manager ->
                runCatching {
                    if (Build.VERSION.SDK_INT >= 28) manager.isLocationEnabled else enabled
                }.getOrDefault(enabled)
            } ?: enabled
            val next = linkedMapOf<String, GeofenceSpec>()
            conditions.asSequence()
                .filter { it.type == ConditionType.GEOFENCE && GeofenceSpec.isValidDirection(it.param2) }
                .forEach { condition ->
                    val spec = GeofenceSpec.parse(condition.param)
                    if (spec == null) {
                        XLog.w("invalid geofence condition ignored: ${condition.param}")
                    } else {
                        next[spec.id] = spec
                    }
                }

            if (!locationEnabled || next.isEmpty()) {
                stopUpdates()
                fences = emptyMap()
                insideById.clear()
                return@post
            }

            fences = next
            insideById.keys.retainAll(next.keys)
            if (registeredProvider == null) startUpdates()
        }
    }

    // Only instantiated inside system_server, whose context supplies location permissions.
    @SuppressLint("MissingPermission")
    private fun startUpdates() {
        if (!locationEnabled || fences.isEmpty() || registeredProvider != null) return
        val manager = locationManager ?: run {
            scheduleRetry()
            return
        }
        val provider = runCatching {
            manager.getBestProvider(Criteria().apply {
                accuracy = Criteria.ACCURACY_COARSE
                powerRequirement = Criteria.POWER_LOW
                isAltitudeRequired = false
                isBearingRequired = false
                isSpeedRequired = false
            }, true)
        }.getOrNull()
        if (provider.isNullOrBlank()) {
            XLog.w("geofence has no enabled location provider; retry scheduled")
            scheduleRetry()
            return
        }
        try {
            manager.requestLocationUpdates(provider, LOCATION_INTERVAL_MS, 0f, listener, handler.looper)
            registeredProvider = provider
            XLog.i("geofence location updates enabled via $provider (${fences.size} fences)")
            runCatching { manager.getLastKnownLocation(provider) }
                .onSuccess { it?.let(::processLocation) }
                .onFailure { XLog.w("geofence last location unavailable: ${it.message}") }
        } catch (t: Throwable) {
            registeredProvider = null
            XLog.e("geofence location updates unavailable", t)
            scheduleRetry()
        }
    }

    private fun processLocation(location: Location) {
        if (!locationEnabled || fences.isEmpty()) return
        val latitude = location.latitude
        val longitude = location.longitude
        if (!latitude.isFinite() || !longitude.isFinite() || latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
            return
        }
        val accuracy = if (location.hasAccuracy() && location.accuracy.isFinite()) {
            location.accuracy.toDouble().coerceAtLeast(0.0)
        } else 0.0
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val ageNanos = nowNanos - location.elapsedRealtimeNanos
        if (ageNanos !in 0L..(MAX_LOCATION_AGE_MS * 1_000_000L)) return
        val timing = mapOf("locationElapsedMs" to (location.elapsedRealtimeNanos / 1_000_000L).toString())

        for ((id, fence) in fences) {
            val distance = fence.distanceTo(latitude, longitude)
            val previous = insideById[id]
            val inside = fence.isInside(distance, previous, accuracy)
            insideById[id] = inside
            emit(Event(EventTypes.GEOFENCE, value = if (inside) "inside" else "outside", value2 = id, extras = timing))
            if (previous != null && previous != inside) {
                val direction = if (inside) "enter" else "exit"
                runCatching { emit(Event(EventTypes.GEOFENCE, value = direction, value2 = id, extras = timing)) }
                    .onFailure { XLog.e("geofence $direction event delivery failed", it) }
            }
        }
    }

    private fun invalidateLocations() {
        insideById.keys.forEach { emit(Event(EventTypes.GEOFENCE, value = "unknown", value2 = it)) }
        insideById.clear()
    }

    private fun stopUpdates(cancelRetry: Boolean = true) {
        if (cancelRetry) handler.removeCallbacks(retryStart)
        val provider = registeredProvider
        registeredProvider = null
        if (provider != null) {
            runCatching { locationManager?.removeUpdates(listener) }
                .onFailure { XLog.w("geofence location listener removal failed: ${it.message}") }
        }
    }

    private fun scheduleRetry() {
        if (locationEnabled && fences.isNotEmpty()) {
            handler.removeCallbacks(retryStart)
            handler.postDelayed(retryStart, RETRY_INTERVAL_MS)
        }
    }

    private companion object {
        const val LOCATION_INTERVAL_MS = 60_000L
        const val RETRY_INTERVAL_MS = 5 * 60_000L
        const val MAX_LOCATION_AGE_MS = 2 * 60_000L
    }
}
