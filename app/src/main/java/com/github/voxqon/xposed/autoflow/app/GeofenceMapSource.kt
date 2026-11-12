package com.github.voxqon.xposed.autoflow.app

import androidx.annotation.StringRes
import com.github.voxqon.xposed.autoflow.R
import org.osmdroid.tileprovider.tilesource.TileSourcePolicy
import org.osmdroid.tileprovider.tilesource.XYTileSource

enum class GeofenceMapSource(
    @StringRes val label: Int,
    val maximumZoom: Int,
    val attribution: String,
    val attributionUrl: String,
    private val endpoint: String,
) {
    HUMANITARIAN(
        R.string.geofence_map_humanitarian,
        19,
        "\u00a9 OpenStreetMap contributors | HOT | OSM France",
        "https://www.openstreetmap.fr/fonds-de-carte/",
        "https://a.tile.openstreetmap.fr/hot/",
    ),
    STANDARD(
        R.string.geofence_map_standard,
        19,
        "\u00a9 OpenStreetMap contributors",
        "https://www.openstreetmap.org/copyright",
        "https://tile.openstreetmap.org/",
    ),
    TOPOGRAPHIC(
        R.string.geofence_map_topographic,
        17,
        "\u00a9 OpenStreetMap contributors, SRTM | \u00a9 OpenTopoMap (CC-BY-SA)",
        "https://opentopomap.org/about",
        "https://a.tile.opentopomap.org/",
    );

    fun tileSource() = XYTileSource(
        // A new cache namespace also avoids reusing previously cached error images.
        "AutoTaskLite-${name}-v2",
        0,
        maximumZoom,
        256,
        ".png",
        arrayOf(endpoint),
        attribution,
        TileSourcePolicy(
            2,
            TileSourcePolicy.FLAG_NO_BULK or
                TileSourcePolicy.FLAG_NO_PREVENTIVE or
                TileSourcePolicy.FLAG_USER_AGENT_MEANINGFUL,
        ),
    )

    companion object {
        fun fromName(name: String?): GeofenceMapSource =
            entries.firstOrNull { it.name == name } ?: HUMANITARIAN
    }
}
