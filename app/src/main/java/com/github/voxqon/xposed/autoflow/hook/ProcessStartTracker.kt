package com.github.voxqon.xposed.autoflow.hook

import com.github.voxqon.xposed.autoflow.util.Reflect
import java.util.WeakHashMap

internal class ProcessStartTracker {
    private data class Start(val pid: Int, val sequence: Long?)
    private val observed = WeakHashMap<Any, Start>()

    @Synchronized
    fun onStarted(record: Any): String? {
        val pid = (read(record, "getPid", "mPid", "pid") as? Number)?.toInt() ?: return null
        if (pid <= 0) return null
        val pkg = packageName(record) ?: return null
        val sequence = (read(record, "getStartSeq", "mStartSeq", "startSeq") as? Number)?.toLong()
        val start = Start(pid, sequence)
        // Completion overloads can call each other for the same process start.
        if (observed.put(record, start) == start) return null
        return pkg
    }

    companion object {
        fun packageName(record: Any?): String? {
            val info = Reflect.getField(record, "info")
            return (Reflect.getField(info, "packageName") as? String)?.takeIf { it.isNotBlank() }
                ?: (Reflect.getField(record, "processName") as? String)
                    ?.substringBefore(':')?.takeIf { it.isNotBlank() }
        }

        private fun read(record: Any, getter: String, vararg fields: String): Any? {
            runCatching { Reflect.findMethodByName(record.javaClass, getter, 0)?.invoke(record) }
                .getOrNull()?.let { return it }
            return fields.firstNotNullOfOrNull { Reflect.getField(record, it) }
        }
    }
}
