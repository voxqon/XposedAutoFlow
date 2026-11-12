package com.github.voxqon.xposed.autoflow.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import com.github.voxqon.xposed.autoflow.model.ExecutionStatus
import com.github.voxqon.xposed.autoflow.store.ExecutionNotificationContract

/** All NotificationManager calls use the module app's identity and permission. */
object ExecutionNotifications {
    const val RUNNING_CHANNEL = "atlt_execution_running"
    const val RESULT_CHANNEL = "atlt_execution_result"

    fun ensureChannels(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(
            NotificationChannel(RUNNING_CHANNEL, "任务运行中", NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(RESULT_CHANNEL, "任务执行结果", NotificationManager.IMPORTANCE_DEFAULT),
        ))
    }

    fun permitted(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    @Synchronized
    fun receive(context: Context, operation: String?, data: Bundle) {
        val ruleId = requireNotNull(data.getString("ruleId"))
        require(ruleId.length in 1..128)
        val manager = context.getSystemService(NotificationManager::class.java)
        // Delays can outlive the app process; keep the token across provider restarts.
        val activeExecutions = context.getSharedPreferences("active_execution_notifications", Context.MODE_PRIVATE)
        val id = ruleId.hashCode() and 0x7FFFFFFF
        if (operation == ExecutionNotificationContract.CANCEL) {
            activeExecutions.edit().remove(ruleId).apply()
            manager.cancel(id)
            return
        }
        val executionId = requireNotNull(data.getString("executionId"))
        require(executionId.length in 1..128)
        when (operation) {
            ExecutionNotificationContract.RUNNING -> activeExecutions.edit().putString(ruleId, executionId).apply()
            ExecutionNotificationContract.RESULT -> {
                if (activeExecutions.getString(ruleId, null) != executionId) return
                activeExecutions.edit().remove(ruleId).apply()
            }
            else -> throw IllegalArgumentException("Unknown notification operation")
        }
        if (!AppPrefs.notifyExecution(context) || !permitted(context)) {
            manager.cancel(id)
            return
        }
        ensureChannels(context)
        val name = data.getString("ruleName").orEmpty().take(256)
        val phase = data.getString("phase").orEmpty().take(100)
        val running = operation == ExecutionNotificationContract.RUNNING
        val detail = if (running) "$phase：$name" else when (
            ExecutionStatus.valueOf(requireNotNull(data.getString("status")))) {
            ExecutionStatus.SUCCESS -> "任务完成：$name"
            ExecutionStatus.FAILED -> "任务失败：$name"
            ExecutionStatus.SUBMITTED -> "任务已提交：$name"
            ExecutionStatus.SKIPPED -> "任务已跳过：$name"
        }
        val content = PendingIntent.getActivity(context, 0,
            Intent(context, ExecutionLogsActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(id, Notification.Builder(context, if (running) RUNNING_CHANNEL else RESULT_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(if (running) "正在执行任务" else "$phase · $name")
            .setContentText(detail)
            .setContentIntent(content)
            .setCategory(if (running) Notification.CATEGORY_PROGRESS else Notification.CATEGORY_STATUS)
            .setOngoing(running)
            .setAutoCancel(!running)
            .setOnlyAlertOnce(running)
            .build())
    }
}
