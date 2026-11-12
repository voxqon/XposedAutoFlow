package com.github.voxqon.xposed.autoflow.engine

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import com.github.voxqon.xposed.autoflow.model.ExecutionLog
import com.github.voxqon.xposed.autoflow.util.XLog
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

object ExecutionLogBridge {
    private val uri = Uri.parse("content://com.github.voxqon.xposed.autoflow.execution-logs/logs")
    private val writer = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
        ArrayBlockingQueue(256), { task -> Thread(task, "atlt-log-writer").apply { isDaemon = true } })

    fun record(context: Context?, log: ExecutionLog) {
        if (context == null) return
        runCatching {
            writer.execute {
                runCatching {
                    context.contentResolver.insert(uri, ContentValues().apply { put("json", log.toJson()) })
                }.onFailure { XLog.w("execution log could not be saved: ${it.message}") }
            }
        }.onFailure { XLog.w("execution log queue is full: ${it.message}") }
    }
}
