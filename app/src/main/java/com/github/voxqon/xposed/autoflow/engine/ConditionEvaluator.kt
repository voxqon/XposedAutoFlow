package com.github.voxqon.xposed.autoflow.engine

import com.github.voxqon.xposed.autoflow.model.Condition
import com.github.voxqon.xposed.autoflow.model.ConditionType
import com.github.voxqon.xposed.autoflow.model.ConnectionMode
import com.github.voxqon.xposed.autoflow.model.GeofenceSpec
import com.github.voxqon.xposed.autoflow.model.TriggerMode
import com.github.voxqon.xposed.autoflow.model.TimeRangeSpec
import com.github.voxqon.xposed.autoflow.model.ChinaHoliday
import java.time.Instant
import java.time.ZoneId
import java.util.Calendar

/**
 * 条件求值器。
 *
 * 两个正交判定：
 * 1. [stateSatisfied]  —— 该条件在“当前状态快照”下是否成立。
 * 2. [freshFor]        —— 该条件是否由当前事件“新近满足”（防止历史状态反复触发规则）。
 *
 * 生命周期事件条件只对当前事件成立；其他条件由状态快照判定。
 */
object ConditionEvaluator {

    /** Match the configured trigger set and require at least one satisfied condition to be fresh. */
    fun triggerSatisfied(
        conditions: List<Condition>,
        mode: TriggerMode,
        e: Event,
        s: StateStore,
        now: Long,
        wallTimeMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        if (conditions.isEmpty()) return false
        // Time windows are mandatory gates even when event conditions use ANY.
        val ranges = conditions.filter { it.type == ConditionType.TIME_RANGE }
        if (ranges.any { !stateSatisfied(it, s, now, wallTimeMillis) }) return false
        val eventConditions = conditions.filter { it.type != ConditionType.TIME_RANGE }
        if (eventConditions.isEmpty()) return ranges.isNotEmpty() && e.type == EventTypes.TICK
        var hasFreshCondition = false
        for (condition in eventConditions) {
            val satisfied = satisfiedForCurrentEvent(condition, e, s, now, wallTimeMillis)
            if (!satisfied && mode == TriggerMode.ALL) return false
            if (satisfied && freshSatisfied(condition, e, s, now, wallTimeMillis)) {
                if (mode == TriggerMode.ANY) return true
                hasFreshCondition = true
            }
        }
        return hasFreshCondition
    }

    /** A condition true at trigger time must be observed false before it may cause exit. */
    fun shouldExit(initiallySatisfied: Boolean, observedFalse: Boolean, currentlySatisfied: Boolean): Boolean =
        currentlySatisfied && (!initiallySatisfied || observedFalse)

    /** 事件与条件类型的对应关系（fresh 判定依据）。 */
    private fun eventMatches(c: Condition, e: Event): Boolean = when (c.type) {
        ConditionType.GROUP -> c.children.any { eventMatches(it, e) }
        ConditionType.APP_NOT_FOREGROUND -> e.type == EventTypes.APP_FG && e.pkg != null && e.pkg != c.param
        ConditionType.APP_FOREGROUND -> e.type == EventTypes.APP_FG && e.pkg == c.param
        ConditionType.APP_LEFT -> e.type == EventTypes.APP_LEFT && e.pkg == c.param
        ConditionType.APP_COLD_START -> e.type == EventTypes.APP_COLD && e.pkg == c.param
        ConditionType.APP_KILLED -> e.type == EventTypes.APP_KILLED && e.pkg == c.param
        ConditionType.SCREEN_ON -> e.type == EventTypes.SCREEN_ON && e.value == "on"
        ConditionType.SCREEN_OFF -> e.type == EventTypes.SCREEN_OFF && e.value == "off"
        ConditionType.SCREEN_ON_FOR, ConditionType.SCREEN_OFF_FOR -> e.type == EventTypes.TICK
        ConditionType.LOCATION_ON -> e.type == EventTypes.LOCATION
        ConditionType.WIFI_ON -> e.type == EventTypes.WIFI
        ConditionType.BT_ON -> e.type == EventTypes.BT
        ConditionType.ROTATION_LOCKED -> e.type == EventTypes.ROTATION
        ConditionType.DND_ON -> e.type == EventTypes.DND
        ConditionType.POWER_SAVE_ON -> e.type == EventTypes.POWER_SAVE
        ConditionType.NIGHT_MODE -> e.type == EventTypes.NIGHT_MODE
        ConditionType.AIRPLANE_MODE -> e.type == EventTypes.AIRPLANE
        ConditionType.HOTSPOT_ON -> e.type == EventTypes.HOTSPOT
        ConditionType.WIFI_CONNECTED, ConditionType.WIFI_DISCONNECTED ->
            e.type == EventTypes.WIFI_CONNECTION || (e.type == EventTypes.WIFI && e.value == "off")
        ConditionType.BT_CONNECTED, ConditionType.BT_DISCONNECTED ->
            e.type == EventTypes.BTDEV || e.type == EventTypes.BT_CONNECTION || e.type == EventTypes.BT
        ConditionType.DEVICE_LOCKED -> e.type == EventTypes.KEYGUARD && e.value == "shown"
        ConditionType.DEVICE_UNLOCKED -> e.type == EventTypes.KEYGUARD && e.value == "hidden"
        ConditionType.HEADSET_CONNECTED -> e.type == EventTypes.HEADSET && e.value == "on"
        ConditionType.HEADSET_DISCONNECTED -> e.type == EventTypes.HEADSET && e.value == "off"
        ConditionType.CHARGER_CONNECTED -> e.type == EventTypes.CHARGER && e.value == "on"
        ConditionType.CHARGER_DISCONNECTED -> e.type == EventTypes.CHARGER && e.value == "off"
        ConditionType.BATTERY_BELOW, ConditionType.BATTERY_ABOVE -> e.type == EventTypes.BATTERY
        ConditionType.WIFI_SSID -> c.param != null &&
            if (c.param2.equals(ConnectionMode.NOT_CONNECTED.code, ignoreCase = true)) {
                when (e.type) {
                    EventTypes.SSID -> (e.value == c.param && e.value2 == "disconnect") ||
                        (e.value != null && e.value != c.param && e.value2 == "connect")
                    EventTypes.WIFI, EventTypes.WIFI_CONNECTION -> e.value == "off"
                    else -> false
                }
            } else {
                e.type == EventTypes.SSID && e.value == c.param &&
                    matchesConnectionMode(c.param2, e.value2)
            }
        ConditionType.BT_DEVICE -> c.param != null &&
            if (c.param2.equals(ConnectionMode.NOT_CONNECTED.code, ignoreCase = true)) {
                when (e.type) {
                    EventTypes.BTDEV -> matchesDevice(e.value, c.param) && e.value2 == "disconnect"
                    EventTypes.BT -> e.value == "off"
                    EventTypes.BT_CONNECTION -> true
                    else -> false
                }
            } else {
                e.type == EventTypes.BTDEV && matchesDevice(e.value, c.param) &&
                    matchesConnectionMode(c.param2, e.value2)
            }
        ConditionType.TIMER, ConditionType.TIME_RANGE -> e.type == EventTypes.TICK
        ConditionType.GEOFENCE -> e.type == EventTypes.GEOFENCE &&
            GeofenceSpec.parse(c.param)?.id == e.value2 &&
            GeofenceSpec.directionMatches(c.param2, e.value)
    }

    private fun matchesConnectionMode(want: String?, actual: String?): Boolean = when ((want ?: "any").lowercase()) {
        "connect" -> actual == "connect"
        "disconnect" -> actual == "disconnect"
        else -> actual == "connect" || actual == "disconnect"
    }

    private fun matchesDevice(actual: String?, expected: String): Boolean =
        actual?.split('|')?.any { it.equals(expected, ignoreCase = true) } == true

    private fun isEventOnly(c: Condition): Boolean = when (c.type) {
        ConditionType.APP_LEFT, ConditionType.APP_COLD_START, ConditionType.APP_KILLED -> true
        ConditionType.GEOFENCE -> !GeofenceSpec.isStateMode(c.param2)
        ConditionType.WIFI_SSID, ConditionType.BT_DEVICE ->
            c.param2.isNullOrBlank() || c.param2.equals("any", ignoreCase = true)
        else -> false
    }

    /** Evaluate transient lifecycle conditions against this event, not a sticky state snapshot. */
    fun satisfiedForCurrentEvent(
        c: Condition,
        e: Event,
        s: StateStore,
        now: Long,
        wallTimeMillis: Long = System.currentTimeMillis(),
    ): Boolean = when {
        c.type == ConditionType.GROUP ->
            c.children.any { satisfiedForCurrentEvent(it, e, s, now, wallTimeMillis) }
        isEventOnly(c) -> eventMatches(c, e)
        else -> stateSatisfied(c, s, now, wallTimeMillis)
    }

    private fun freshSatisfied(c: Condition, e: Event, s: StateStore, now: Long, wallTime: Long): Boolean =
        if (c.type == ConditionType.GROUP) {
            c.children.any {
                satisfiedForCurrentEvent(it, e, s, now, wallTime) && freshFor(it, e)
            }
        } else freshFor(c, e)

    /** 条件是否由当前事件“新近满足”。持续型条件（tick 驱动）在满足瞬间为 fresh。 */
    fun freshFor(c: Condition, e: Event): Boolean {
        if (!eventMatches(c, e)) return false
        return when (c.type) {
            ConditionType.GROUP -> c.children.any { freshFor(it, e) }
            ConditionType.APP_NOT_FOREGROUND,
            ConditionType.WIFI_CONNECTED, ConditionType.WIFI_DISCONNECTED,
            ConditionType.BT_CONNECTED, ConditionType.BT_DISCONNECTED -> true
            // 应用类：事件本身就代表状态翻转
            ConditionType.APP_FOREGROUND, ConditionType.APP_LEFT,
            ConditionType.APP_COLD_START, ConditionType.APP_KILLED,
            ConditionType.SCREEN_ON, ConditionType.SCREEN_OFF,
            ConditionType.CHARGER_CONNECTED, ConditionType.CHARGER_DISCONNECTED,
            ConditionType.DEVICE_LOCKED, ConditionType.DEVICE_UNLOCKED,
            ConditionType.HEADSET_CONNECTED, ConditionType.HEADSET_DISCONNECTED,
            ConditionType.WIFI_SSID, ConditionType.BT_DEVICE, ConditionType.GEOFENCE -> true
            // 开关类：仅当事件值与条件要求的方向一致时才算“新满足”
            ConditionType.LOCATION_ON -> matchesOnOff(c.param, e.value)
            ConditionType.WIFI_ON -> matchesOnOff(c.param, e.value)
            ConditionType.BT_ON -> matchesOnOff(c.param, e.value)
            ConditionType.ROTATION_LOCKED -> matchesLock(c.param, e.value)
            ConditionType.DND_ON -> matchesOnOff(c.param, e.value)
            ConditionType.POWER_SAVE_ON, ConditionType.NIGHT_MODE -> matchesOnOff(c.param, e.value)
            ConditionType.AIRPLANE_MODE, ConditionType.HOTSPOT_ON -> matchesOnOff(c.param, e.value)
            ConditionType.BATTERY_BELOW -> (e.value?.toIntOrNull() ?: -1) in 0 until threshold(c.param)
            ConditionType.BATTERY_ABOVE -> (e.value?.toIntOrNull() ?: -1) > threshold(c.param)
            // 持续型/定时：由 tick 驱动，stateSatisfied 在满足瞬间返回 true，
            // 这里直接放行，由 stateSatisfied 把关
            ConditionType.SCREEN_ON_FOR, ConditionType.SCREEN_OFF_FOR,
            ConditionType.TIMER, ConditionType.TIME_RANGE -> true
        }
    }

    /** 条件在当前状态快照下是否成立；瞬时生命周期条件始终由当前 Event 判定。 */
    fun stateSatisfied(c: Condition, s: StateStore, now: Long, wallTimeMillis: Long = System.currentTimeMillis()): Boolean {
        return when (c.type) {
            ConditionType.GROUP -> c.children.any { stateSatisfied(it, s, now, wallTimeMillis) }
            ConditionType.APP_NOT_FOREGROUND -> c.param != null &&
                s.foregroundPkg != null && s.foregroundPkg != c.param
            ConditionType.AIRPLANE_MODE -> s.airplaneModeOn?.let { matchesOnOff(c.param, if (it) "on" else "off") } ?: false
            ConditionType.HOTSPOT_ON -> s.hotspotOn?.let { matchesOnOff(c.param, if (it) "on" else "off") } ?: false
            ConditionType.WIFI_CONNECTED -> s.wifiConnected == true
            ConditionType.WIFI_DISCONNECTED -> s.wifiConnected == false
            ConditionType.BT_CONNECTED -> s.bluetoothConnected == true
            ConditionType.BT_DISCONNECTED -> s.bluetoothConnected == false
            ConditionType.APP_FOREGROUND -> s.foregroundPkg == c.param
            ConditionType.APP_LEFT, ConditionType.APP_COLD_START, ConditionType.APP_KILLED -> false
            ConditionType.GEOFENCE -> {
                if (!s.locationOn || !GeofenceSpec.isStateMode(c.param2)) return false
                val fence = GeofenceSpec.parse(c.param) ?: return false
                val inside = s.geofenceInside(fence.id, now) ?: return false
                if (c.param2.equals("inside", ignoreCase = true)) inside else !inside
            }
            ConditionType.DEVICE_LOCKED -> s.keyguardKnown && s.keyguardShown
            ConditionType.DEVICE_UNLOCKED -> s.keyguardKnown && !s.keyguardShown
            ConditionType.POWER_SAVE_ON -> s.powerSaveOn?.let { matchesOnOff(c.param, if (it) "on" else "off") } ?: false
            ConditionType.NIGHT_MODE -> s.nightModeOn?.let { matchesOnOff(c.param, if (it) "on" else "off") } ?: false
            ConditionType.HEADSET_CONNECTED -> s.headsetConnected == true
            ConditionType.HEADSET_DISCONNECTED -> s.headsetConnected == false
            ConditionType.SCREEN_ON -> s.screenOn
            ConditionType.SCREEN_OFF -> !s.screenOn
            ConditionType.SCREEN_ON_FOR -> {
                if (s.screenOn) {
                    val mins = c.param?.toLongOrNull()
                        ?.takeIf { it in 0..(Long.MAX_VALUE / 60_000) } ?: return false
                    s.screenOnSince >= 0 && now - s.screenOnSince >= mins * 60_000
                } else false
            }
            ConditionType.SCREEN_OFF_FOR -> {
                if (!s.screenOn) {
                    val mins = c.param?.toLongOrNull()
                        ?.takeIf { it in 0..(Long.MAX_VALUE / 60_000) } ?: return false
                    s.screenOffSince >= 0 && now - s.screenOffSince >= mins * 60_000
                } else false
            }
            ConditionType.LOCATION_ON -> matchesOnOff(c.param, if (s.locationOn) "on" else "off")
            ConditionType.WIFI_ON -> matchesOnOff(c.param, if (s.wifiOn) "on" else "off")
            ConditionType.BT_ON -> matchesOnOff(c.param, if (s.btOn) "on" else "off")
            ConditionType.ROTATION_LOCKED -> matchesLock(c.param, if (s.rotationLocked) "locked" else "unlocked")
            ConditionType.DND_ON -> matchesOnOff(c.param, if (s.dndOn) "on" else "off")
            ConditionType.CHARGER_CONNECTED -> s.charging
            ConditionType.CHARGER_DISCONNECTED -> !s.charging
            ConditionType.BATTERY_BELOW -> s.batteryLevel in 0 until threshold(c.param)
            ConditionType.BATTERY_ABOVE -> s.batteryLevel > threshold(c.param)
            ConditionType.WIFI_SSID -> {
                val want = c.param?.takeIf { it.isNotBlank() } ?: return false
                val cur = s.currentSsid
                val mode = (c.param2 ?: "any").lowercase()
                when (mode) {
                    "connect" -> cur == want
                    "disconnect" -> cur != want
                    "not_connected" -> s.wifiConnected == false || (cur != null && cur != want)
                    else -> cur == want
                }
            }
            ConditionType.BT_DEVICE -> {
                val want = c.param?.takeIf { it.isNotBlank() } ?: return false
                val mode = (c.param2 ?: "any").lowercase()
                when (mode) {
                    "disconnect" -> !s.isBluetoothDeviceConnected(want)
                    "not_connected" -> s.bluetoothConnected != null && !s.isBluetoothDeviceConnected(want)
                    else -> s.isBluetoothDeviceConnected(want)
                }
            }
            ConditionType.TIMER -> {
                // param = HH:mm；param2 = wd(周一到周五)/we(周末)/workday(法定工作日)/holiday(法定休息日)/空(每天)
                val parts = c.param?.split(":") ?: return false
                if (parts.size != 2) return false
                val h = parts[0].toIntOrNull() ?: return false
                val m = parts[1].toIntOrNull() ?: return false
                if (h !in 0..23 || m !in 0..59) return false
                val local = Instant.ofEpochMilli(wallTimeMillis).atZone(ZoneId.systemDefault())
                if (local.hour != h || local.minute != m) return false
                val date = local.toLocalDate()
                when ((c.param2 ?: "").lowercase()) {
                    "wd" -> date.dayOfWeek.value in 1..5
                    "we" -> date.dayOfWeek.value >= 6
                    "workday" -> ChinaHoliday.isWorkday(date)
                    "holiday" -> !ChinaHoliday.isWorkday(date)
                    else -> true
                }
            }
            ConditionType.TIME_RANGE -> {
                val range = TimeRangeSpec.parse(c.param) ?: return false
                val cal = Calendar.getInstance().apply { timeInMillis = wallTimeMillis }
                range.matches(cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE), c.param2)
            }
        }
    }

    private fun threshold(p: String?): Int = p?.toIntOrNull()?.coerceIn(0, 100) ?: 50

    private fun matchesOnOff(want: String?, actual: String?): Boolean = when ((want ?: "any").lowercase()) {
        "on" -> actual == "on"
        "off" -> actual == "off"
        else -> true
    }

    private fun matchesLock(want: String?, actual: String?): Boolean = when ((want ?: "any").lowercase()) {
        "locked" -> actual == "locked"
        "unlocked" -> actual == "unlocked"
        else -> true
    }
}
