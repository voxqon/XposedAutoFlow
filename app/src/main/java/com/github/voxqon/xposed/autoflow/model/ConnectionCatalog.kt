package com.github.voxqon.xposed.autoflow.model

import org.json.JSONArray
import org.json.JSONObject

enum class ConnectionKind { WIFI, BLUETOOTH }

enum class ConnectionMode(val code: String, val label: String) {
    CONNECT("connect", "连接"),
    DISCONNECT("disconnect", "断开"),
    ANY("any", "连接或断开"),
    NOT_CONNECTED("not_connected", "未连接");

    companion object {
        fun fromCode(code: String?): ConnectionMode? =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) }
    }
}

data class ConnectionTarget(val value: String, val name: String = value) {
    val label: String get() = if (name == value) value else "$name\n$value"
}

/** Contains identifiers only; Wi-Fi credentials never cross the catalog bridge. */
data class ConnectionCatalog(
    val wifi: List<ConnectionTarget> = emptyList(),
    val bluetooth: List<ConnectionTarget> = emptyList(),
    val wifiReadable: Boolean = false,
    val bluetoothReadable: Boolean = false,
) {
    fun targets(kind: ConnectionKind): List<ConnectionTarget> =
        if (kind == ConnectionKind.WIFI) wifi else bluetooth

    fun readable(kind: ConnectionKind): Boolean =
        if (kind == ConnectionKind.WIFI) wifiReadable else bluetoothReadable

    fun toJson(): String = JSONObject()
        .put("wifi", encode(wifi))
        .put("bluetooth", encode(bluetooth))
        .put("wifiReadable", wifiReadable)
        .put("bluetoothReadable", bluetoothReadable)
        .toString()

    companion object {
        const val MAX_TARGETS = 256
        const val MAX_JSON_LENGTH = 256 * 1024
        private val mac = Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")

        fun isMac(value: String): Boolean = mac.matches(value)

        fun wifiName(value: String?): String? = value?.removeSurrounding("\"")
            ?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }

        /** Resolve a name only when unambiguous; MACs are case insensitive. */
        fun bluetoothTarget(targets: List<ConnectionTarget>, value: String): ConnectionTarget? =
            if (isMac(value)) targets.singleOrNull { it.value.equals(value, ignoreCase = true) }
            else targets.filter { it.name.equals(value, ignoreCase = true) }.singleOrNull()

        fun fromJson(json: String?): ConnectionCatalog? = runCatching {
            if (json.isNullOrBlank() || json.length > MAX_JSON_LENGTH) return null
            val obj = JSONObject(json)
            ConnectionCatalog(
                wifi = decode(obj.optJSONArray("wifi")),
                bluetooth = decode(obj.optJSONArray("bluetooth")),
                wifiReadable = obj.optBoolean("wifiReadable"),
                bluetoothReadable = obj.optBoolean("bluetoothReadable"),
            )
        }.getOrNull()

        private fun encode(targets: List<ConnectionTarget>): JSONArray = JSONArray().apply {
            targets.take(MAX_TARGETS).forEach {
                put(JSONObject().put("value", it.value).put("name", it.name))
            }
        }

        private fun decode(array: JSONArray?): List<ConnectionTarget> {
            if (array == null) return emptyList()
            return (0 until minOf(array.length(), MAX_TARGETS)).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                val value = obj.optString("value").takeIf { it.isNotBlank() && it.length <= 256 }
                    ?: return@mapNotNull null
                val name = obj.optString("name", value).take(256).ifBlank { value }
                ConnectionTarget(value, name)
            }.distinctBy { it.value }
        }
    }
}
