package com.github.voxqon.xposed.autoflow.engine

internal object ToggleResolver {
    fun resolve(parameter: String?, current: () -> Boolean?): Boolean? =
        when (parameter?.lowercase() ?: "toggle") {
            "on" -> true
            "off" -> false
            "toggle" -> current()?.not()
            else -> null
        }
}
