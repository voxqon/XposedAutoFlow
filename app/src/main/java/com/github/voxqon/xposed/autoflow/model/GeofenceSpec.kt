package com.github.voxqon.xposed.autoflow.model

/** Validated, platform-independent geofence geometry stored in a condition's first parameter. */
data class GeofenceSpec(
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Double,
) {
    /** Stable across equivalent numeric input such as `31` and `31.0`. */
    val id: String
        get() = "${canonical(latitude)},${canonical(longitude)},${canonical(radiusMeters)}"

    fun distanceTo(latitude: Double, longitude: Double): Double {
        val earthRadiusMeters = 6_371_000.0
        val lat1 = Math.toRadians(this.latitude)
        val lat2 = Math.toRadians(latitude)
        val deltaLat = lat2 - lat1
        val deltaLon = Math.toRadians(longitude - this.longitude)
        val sinLat = kotlin.math.sin(deltaLat / 2.0)
        val sinLon = kotlin.math.sin(deltaLon / 2.0)
        val a = (sinLat * sinLat + kotlin.math.cos(lat1) * kotlin.math.cos(lat2) * sinLon * sinLon)
            .coerceIn(0.0, 1.0)
        return earthRadiusMeters * 2.0 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1.0 - a))
    }

    /** Apply a small accuracy-aware hysteresis band to avoid GPS jitter at the boundary. */
    fun isInside(distanceMeters: Double, previouslyInside: Boolean?, accuracyMeters: Double): Boolean {
        if (!distanceMeters.isFinite() || distanceMeters < 0.0) return previouslyInside ?: false
        if (previouslyInside == null) return distanceMeters <= radiusMeters
        val accuracy = accuracyMeters.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        val hysteresis = minOf(radiusMeters * 0.1, 100.0, maxOf(5.0, accuracy * 0.5))
        return if (previouslyInside) distanceMeters <= radiusMeters + hysteresis
        else distanceMeters <= radiusMeters - hysteresis
    }

    companion object {
        const val MAX_RADIUS_METERS = 100_000.0

        fun parse(value: String?): GeofenceSpec? {
            val parts = value?.split(',') ?: return null
            if (parts.size != 3) return null
            val latitude = parts[0].trim().toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
            val longitude = parts[1].trim().toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
            val radius = parts[2].trim().toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
            if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
            if (radius <= 0.0 || radius > MAX_RADIUS_METERS) return null
            return GeofenceSpec(latitude, longitude, radius)
        }

        fun isValidDirection(value: String?): Boolean = when (value?.lowercase() ?: "any") {
            "enter", "exit", "any", "inside", "outside" -> true
            else -> false
        }

        fun directionMatches(wanted: String?, actual: String?): Boolean = when (wanted?.lowercase() ?: "any") {
            "enter" -> actual == "enter"
            "exit" -> actual == "exit"
            "any" -> actual == "enter" || actual == "exit"
            "inside" -> actual == "inside" || actual == "enter"
            "outside" -> actual == "outside" || actual == "exit"
            else -> false
        }

        fun isStateMode(value: String?): Boolean =
            value.equals("inside", ignoreCase = true) || value.equals("outside", ignoreCase = true)

        private fun canonical(value: Double): String =
            if (value == 0.0) "0" else value.toString()
    }
}
