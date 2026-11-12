package com.github.voxqon.xposed.autoflow.engine

import android.content.Context
import android.os.Binder
import android.os.Bundle
import com.github.voxqon.xposed.autoflow.model.ExecutionStatus
import com.github.voxqon.xposed.autoflow.store.ExecutionNotificationContract
import com.github.voxqon.xposed.autoflow.util.XLog

/** Delivers notifications to the module app instead of posting as the android package. */
object NotificationHelper {
    fun cancel(ctx: Context?, ruleId: String) {
        deliver(ctx, ExecutionNotificationContract.CANCEL, ruleId)
    }

    fun notifyRunning(ctx: Context?, ruleId: String, ruleName: String, phase: String, executionId: String) {
        deliver(ctx, ExecutionNotificationContract.RUNNING, ruleId, ruleName, phase, executionId)
    }

    fun notifyResult(ctx: Context?, ruleId: String, ruleName: String,
        status: ExecutionStatus, phase: String, executionId: String) {
        deliver(ctx, ExecutionNotificationContract.RESULT, ruleId, ruleName, phase, executionId, status)
    }

    private fun deliver(ctx: Context?, operation: String, ruleId: String,
        ruleName: String = "", phase: String = "", executionId: String = "",
        status: ExecutionStatus? = null) {
        if (ctx == null) return
        val token = Binder.clearCallingIdentity()
        try {
            runCatching {
                check(ctx.contentResolver.call(ExecutionNotificationContract.URI,
                    ExecutionNotificationContract.METHOD, operation, Bundle().apply {
                        putString("ruleId", ruleId)
                        putString("ruleName", ruleName)
                        putString("phase", phase)
                        putString("executionId", executionId)
                        status?.let { putString("status", it.name) }
                    }) != null) { "Notification provider unavailable" }
            }.onFailure { XLog.w("execution notification delivery failed: ${it.message}") }
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }
}
