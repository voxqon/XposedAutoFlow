package com.github.voxqon.xposed.autoflow.app

import android.Manifest
import android.app.NotificationManager
import android.os.Bundle
import android.os.Process
import com.github.voxqon.xposed.autoflow.engine.NotificationHelper
import com.github.voxqon.xposed.autoflow.model.ExecutionStatus
import com.github.voxqon.xposed.autoflow.store.ExecutionNotificationContract
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34])
class ExecutionNotificationsTest {
    @Test
    fun systemEngineUsesTheAppBridgeAndNotificationOpensExecutionLogs() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val provider = Robolectric.buildContentProvider(ExecutionLogProvider::class.java).create().get()
        ShadowContentResolver.registerProviderInternal("com.github.voxqon.xposed.autoflow.execution-logs", provider)
        ShadowBinder.setCallingUid(Process.SYSTEM_UID)
        try {
            NotificationHelper.notifyRunning(app, "bridge", "Task", "Trigger", "run")
            NotificationHelper.notifyResult(app, "bridge", "Task", ExecutionStatus.SUCCESS, "Trigger", "run")
        } finally {
            ShadowBinder.setCallingUid(Process.myUid())
        }
        val notification = shadowOf(app.getSystemService(NotificationManager::class.java))
            .getNotification("bridge".hashCode() and 0x7FFFFFFF)
        assertNotNull(notification)
        assertEquals(ExecutionNotifications.RESULT_CHANNEL, notification.channelId)
        assertEquals(ExecutionLogsActivity::class.java.name,
            shadowOf(notification.contentIntent).savedIntent.component?.className)
    }

    @Test
    fun bridgeRejectsUntrustedCallersAndRespectsTheAppPreference() {
        val app = RuntimeEnvironment.getApplication()
        val provider = Robolectric.buildContentProvider(ExecutionLogProvider::class.java).create().get()
        val data = Bundle().apply {
            putString("ruleId", "private")
            putString("executionId", "run")
        }
        ShadowBinder.setCallingUid(42_000)
        try {
            assertThrows(SecurityException::class.java) {
                provider.call(ExecutionNotificationContract.METHOD, ExecutionNotificationContract.RUNNING, data)
            }
        } finally {
            ShadowBinder.setCallingUid(Process.myUid())
        }
        AppPrefs.setNotifyExecution(app, false)
        provider.call(ExecutionNotificationContract.METHOD, ExecutionNotificationContract.RUNNING, data)
        assertNull(shadowOf(app.getSystemService(NotificationManager::class.java))
            .getNotification("private".hashCode() and 0x7FFFFFFF))
    }

    @Test
    @Config(sdk = [34])
    fun deniedPermissionDoesNotPostAndTheHomePageRequestsItOnlyOnce() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS),
            shadowOf(controller.get()).lastRequestedPermission.requestedPermissions.toList())
        val data = Bundle().apply {
            putString("ruleId", "denied")
            putString("executionId", "run")
        }
        ExecutionNotifications.receive(app, ExecutionNotificationContract.RUNNING, data)
        assertNull(shadowOf(app.getSystemService(NotificationManager::class.java))
            .getNotification("denied".hashCode() and 0x7FFFFFFF))
        controller.pause().stop().destroy()
        val reopened = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertNull(shadowOf(reopened.get()).lastRequestedPermission)
        reopened.pause().stop().destroy()
    }
}
