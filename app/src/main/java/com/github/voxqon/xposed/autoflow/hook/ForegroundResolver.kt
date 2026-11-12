package com.github.voxqon.xposed.autoflow.hook

import android.content.ComponentName
import android.content.Intent
import android.os.IBinder
import com.github.voxqon.xposed.autoflow.util.Reflect

internal object ForegroundResolver {
    fun packageName(
        receiver: Any?,
        args: Array<Any?>,
        resolveToken: (IBinder) -> Any?,
    ): String? {
        fun fromRecord(record: Any?): String? {
            if (record == null) return null
            (Reflect.getField(record, "packageName") as? String)
                ?.takeIf { it.isNotBlank() }?.let { return it }
            (Reflect.getField(record, "mActivityComponent") as? ComponentName)
                ?.let { return it.packageName }
            return (Reflect.getField(record, "intent") as? Intent)?.component?.packageName
        }
        fromRecord(receiver)?.let { return it }
        args.forEach { fromRecord(it)?.let { pkg -> return pkg } }
        // Modern ActivityRecord resume callbacks carry an IBinder, not an ActivityRecord.
        args.filterIsInstance<IBinder>().forEach { token ->
            fromRecord(runCatching { resolveToken(token) }.getOrNull())?.let { return it }
        }
        return null
    }
}
