package com.github.voxqon.xposed.autoflow.hook

import com.github.voxqon.xposed.autoflow.util.Reflect

internal object PowerStateResolver {
    fun isInteractive(service: Any?): Boolean? {
        val wakefulness = Reflect.getField(service, "mWakefulnessRaw")
            ?: Reflect.getField(service, "mWakefulness")
        // DREAMING still accepts input; DOZING is noninteractive.
        return when (wakefulness) {
            1, 2 -> true
            0, 3 -> false
            else -> null
        }
    }
}
