package com.github.voxqon.xposed.autoflow.store

import android.net.Uri

/** Framework-only contract shared by the injected engine and the module app. */
object ExecutionNotificationContract {
    val URI: Uri = Uri.parse("content://com.github.voxqon.xposed.autoflow.execution-logs/logs")
    const val METHOD = "execution_notification"
    const val RUNNING = "running"
    const val RESULT = "result"
    const val CANCEL = "cancel"
}
