package com.github.voxqon.xposed.autoflow.model

enum class SoundKind(val code: String, val label: String) {
    NOTIFICATION("notification", "系统通知声"),
    ALARM("alarm", "系统闹钟声"),
    RINGTONE("ringtone", "系统来电铃声");

    companion object {
        fun fromCode(value: String?): SoundKind? = entries.firstOrNull { it.code == value }
    }
}

data class SoundSpec(val kind: SoundKind, val seconds: Int) {
    companion object {
        fun parse(kind: String?, seconds: String?): SoundSpec? {
            val selected = SoundKind.fromCode(kind) ?: return null
            val duration = if (seconds.isNullOrBlank()) 5 else seconds.toIntOrNull() ?: return null
            return duration.takeIf { it in 1..30 }?.let { SoundSpec(selected, it) }
        }
    }
}
