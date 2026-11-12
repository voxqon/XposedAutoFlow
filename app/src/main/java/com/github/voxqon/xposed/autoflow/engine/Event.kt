package com.github.voxqon.xposed.autoflow.engine

/**
 * 引擎事件。
 * type 取值见 EventTypes；pkg 用于应用类事件；value/value2 携带状态值
 * （如 wifi=on、ssid=MyHome、battery=37）。extras 通过桥接 JSON 传递并设有长度上限。
 */
data class Event(
    val type: String,
    val pkg: String? = null,
    val value: String? = null,
    val value2: String? = null,
    val extras: Map<String, String> = emptyMap(),
) {
    fun toJson(): String {
        val o = org.json.JSONObject()
        o.put("type", type)
        if (pkg != null) o.put("pkg", pkg)
        if (value != null) o.put("value", value)
        if (value2 != null) o.put("value2", value2)
        if (extras.isNotEmpty()) {
            o.put("extras", org.json.JSONObject().apply {
                extras.entries.take(MAX_EXTRAS).forEach { (key, extraValue) ->
                    if (key.length <= MAX_EXTRA_KEY_LENGTH && extraValue.length <= MAX_EXTRA_VALUE_LENGTH) {
                        put(key, extraValue)
                    }
                }
            })
        }
        return o.toString()
    }

    companion object {
        private const val MAX_JSON_LENGTH = 16 * 1024
        private const val MAX_EXTRAS = 16
        private const val MAX_EXTRA_KEY_LENGTH = 64
        private const val MAX_EXTRA_VALUE_LENGTH = 1024

        fun fromJson(s: String): Event? {
            if (s.length > MAX_JSON_LENGTH) return null
            return runCatching {
                val o = org.json.JSONObject(s)
                val type = o.optString("type").takeIf { EventTypes.isKnown(it) }
                    ?: return@runCatching null
                val pkg = o.optString("pkg").takeIf { it.isNotEmpty() && it.length <= 255 }
                val value = o.optString("value").takeIf { it.isNotEmpty() && it.length <= MAX_EXTRA_VALUE_LENGTH }
                val value2 = o.optString("value2").takeIf { it.isNotEmpty() && it.length <= MAX_EXTRA_VALUE_LENGTH }
                val extras = linkedMapOf<String, String>()
                o.optJSONObject("extras")?.let { extraJson ->
                    val keys = extraJson.keys()
                    while (keys.hasNext() && extras.size < MAX_EXTRAS) {
                        val key = keys.next()
                        val extraValue = extraJson.optString(key)
                        if (key.length <= MAX_EXTRA_KEY_LENGTH && extraValue.length <= MAX_EXTRA_VALUE_LENGTH) {
                            extras[key] = extraValue
                        }
                    }
                }
                Event(
                    type = type,
                    pkg = pkg,
                    value = value,
                    value2 = value2,
                    extras = extras,
                )
            }.getOrNull()
        }
    }
}

object EventTypes {
    const val APP_FG = "app_fg"
    const val APP_LEFT = "app_left"
    const val APP_COLD = "app_cold"
    const val APP_KILLED = "app_killed"
    const val SCREEN_ON = "screen_on"
    const val SCREEN_OFF = "screen_off"
    const val WIFI = "wifi"          // value = on/off, value2 = ssid(已连接时)
    const val SSID = "ssid"          // value = ssid, value2 = connect/disconnect
    const val BT = "bt"              // value = on/off
    const val BTDEV = "btdev"        // value = device(name|mac), value2 = connect/disconnect
    const val LOCATION = "location"  // value = on/off
    const val ROTATION = "rotation"  // value = locked/unlocked
    const val DND = "dnd"            // value = on/off
    const val CHARGER = "charger"    // value = on/off
    const val BATTERY = "battery"    // value = level
    const val NOTIF = "notif"        // pkg = 通知所属包
    const val KEYGUARD = "keyguard"  // value = shown/hidden
    const val TICK = "tick"          // 30s 调度心跳，驱动定时/持续时长条件
    const val GEOFENCE = "geofence"  // value = enter/exit, value2 = stable fence id
    const val POWER_SAVE = "power_save"
    const val NIGHT_MODE = "night_mode"
    const val HEADSET = "headset"
    const val AIRPLANE = "airplane"
    const val HOTSPOT = "hotspot"
    const val WIFI_CONNECTION = "wifi_connection"
    const val BT_CONNECTION = "bt_connection"

    private val knownTypes = setOf(
        APP_FG, APP_LEFT, APP_COLD, APP_KILLED, SCREEN_ON, SCREEN_OFF, WIFI, SSID,
        BT, BTDEV, LOCATION, ROTATION, DND, CHARGER, BATTERY, NOTIF, KEYGUARD, TICK, GEOFENCE,
        POWER_SAVE, NIGHT_MODE, HEADSET, AIRPLANE, HOTSPOT, WIFI_CONNECTION, BT_CONNECTION,
    )

    fun isKnown(type: String): Boolean = type in knownTypes
}
