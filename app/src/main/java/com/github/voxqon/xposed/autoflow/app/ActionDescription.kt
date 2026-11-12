package com.github.voxqon.xposed.autoflow.app

import com.github.voxqon.xposed.autoflow.model.Action
import com.github.voxqon.xposed.autoflow.model.ActionType
import com.github.voxqon.xposed.autoflow.model.ConnectionMode
import com.github.voxqon.xposed.autoflow.model.SoundSpec

fun Action.description(): String {
    val description = when {
        type == ActionType.PLAY_SOUND -> SoundSpec.parse(param, param2)
            ?.let { "播放${it.kind.label}（${it.seconds} 秒）" }
            ?: type.label
        type == ActionType.VIBRATE -> "振动 $param 毫秒"
        type == ActionType.MEDIA_VOLUME -> "媒体音量 $param%"
        type.isAppAction -> {
            val target = appName?.takeIf { it.isNotBlank() }?.let { "$it ($param)" } ?: param.orEmpty()
            "${type.label}：$target"
        }
        type.isSwitchAction -> {
            val state = when (param?.lowercase()) {
                "on" -> "开启"
                "off" -> "关闭"
                "lock" -> "锁定"
                "unlock" -> "解锁"
                else -> "切换当前状态"
            }
            val target = when (type) {
                ActionType.LOCATION -> "定位"
                ActionType.WIFI -> "Wi-Fi"
                ActionType.HOTSPOT -> "个人热点"
                ActionType.BLUETOOTH -> "蓝牙"
                ActionType.ROTATION_LOCK -> "方向锁定"
                ActionType.DND -> "勿扰模式"
                else -> "隐身模式"
            }
            "$target：$state"
        }
        type == ActionType.WIFI_CONNECTION || type == ActionType.BLUETOOTH_CONNECTION -> {
            val direction = ConnectionMode.fromCode(param2)?.label ?: param2.orEmpty()
            val kind = if (type == ActionType.WIFI_CONNECTION) "Wi-Fi" else "蓝牙"
            "$direction $kind ($param)"
        }
        else -> {
            val parameters = listOfNotNull(param, param2)
            if (parameters.isEmpty()) type.label else "${type.label} (${parameters.joinToString(" / ")})"
        }
    }
    return if (delaySeconds > 0) "$description（延迟 $delaySeconds 秒）" else description
}
