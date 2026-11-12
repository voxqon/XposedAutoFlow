package com.github.voxqon.xposed.autoflow.app

import com.github.voxqon.xposed.autoflow.model.Condition
import com.github.voxqon.xposed.autoflow.model.ConditionType
import com.github.voxqon.xposed.autoflow.model.ConnectionMode
import com.github.voxqon.xposed.autoflow.model.GeofenceSpec
import com.github.voxqon.xposed.autoflow.model.TimeRangeSpec
import com.github.voxqon.xposed.autoflow.model.Rule
import com.github.voxqon.xposed.autoflow.model.TriggerMode
import java.util.Locale

fun Condition.description(): String {
    if (type == ConditionType.GROUP) {
        return "条件组（" + children.joinToString(" 或 ") { it.description() } + "）"
    }
    if (type.isSwitchCondition) {
        val state = when (param?.lowercase(Locale.ROOT)) {
            "on" -> "开启"
            "off" -> "关闭"
            "locked" -> "锁定"
            "unlocked" -> "解锁"
            else -> "开启或关闭"
        }
        return "${type.label}：$state"
    }
    if (type.isAppCondition) {
        val target = appName?.takeIf { it.isNotBlank() }?.let { "$it ($param)" } ?: param.orEmpty()
        return "${type.label}：$target"
    }
    if (type == ConditionType.TIMER) {
        val days = when (param2) {
            "wd" -> "工作日"
            "we" -> "周末"
            "workday" -> "法定工作日"
            "holiday" -> "法定休息日"
            else -> "每天"
        }
        return "定时触发 $param（$days）"
    }
    if (type == ConditionType.TIME_RANGE) {
        TimeRangeSpec.parse(param)?.let {
            val mode = if (param2 == "outside") "范围外" else "范围内"
            val nextDay = if (it.crossesMidnight) "次日 " else ""
            return "时间范围 ${it.startText} - $nextDay${it.endText}（$mode）"
        }
    }
    if (type == ConditionType.WIFI_SSID || type == ConditionType.BT_DEVICE) {
        val direction = ConnectionMode.fromCode(param2)?.label ?: ConnectionMode.ANY.label
        val kind = if (type == ConditionType.WIFI_SSID) "Wi-Fi" else "蓝牙"
        return "$direction $kind ($param)"
    }
    if (type == ConditionType.GEOFENCE) {
        GeofenceSpec.parse(param)?.let { fence ->
            val direction = when (param2?.lowercase(Locale.ROOT)) {
                "enter" -> "进入"
                "exit" -> "离开"
                "inside" -> "在围栏内："
                "outside" -> "在围栏外："
                else -> "进入或离开"
            }
            val place = placeName?.takeIf { it.isNotBlank() }
                ?: String.format(Locale.ROOT, "%.6f, %.6f", fence.latitude, fence.longitude)
            val radius = java.math.BigDecimal.valueOf(fence.radiusMeters).stripTrailingZeros().toPlainString()
            return "$direction $place（半径 $radius 米）"
        }
    }
    val parameters = listOfNotNull(param, param2)
    return if (parameters.isEmpty()) type.label else "${type.label} (${parameters.joinToString(" / ")})"
}

fun Rule.triggerDescription(): String {
    val (ranges, events) = conditions.partition { it.type == ConditionType.TIME_RANGE }
    val separator = if (triggerMode == TriggerMode.ANY) " 或 " else " 且 "
    val eventText = events.joinToString(separator) { it.description() }
    val groupedEvents = if (ranges.isNotEmpty() && events.size > 1 && triggerMode == TriggerMode.ANY) {
        "（$eventText）"
    } else eventText
    return (ranges.map { it.description() } + listOf(groupedEvents).filter { it.isNotBlank() }).joinToString(" 且 ")
}
