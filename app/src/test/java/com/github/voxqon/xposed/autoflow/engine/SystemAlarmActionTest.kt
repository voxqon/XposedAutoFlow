package com.github.voxqon.xposed.autoflow.engine

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.AlarmClock
import com.github.voxqon.xposed.autoflow.app.AppPrefs
import com.github.voxqon.xposed.autoflow.model.Action
import com.github.voxqon.xposed.autoflow.model.ActionType
import com.github.voxqon.xposed.autoflow.model.ExecutionStatus
import com.github.voxqon.xposed.autoflow.model.Rule
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34])
class SystemAlarmActionTest {
    private val app get() = RuntimeEnvironment.getApplication()

    private fun time(hour: Int, minute: Int, second: Int) = Calendar.getInstance().apply {
        clear()
        set(2026, Calendar.NOVEMBER, 12, hour, minute, second)
    }.timeInMillis

    @Test fun submitsOneNonRepeatingAlarmToTheSystemClock() {
        val step = SystemAlarmAction.execute(app, "提醒喝水", time(9, 30, 20))
        assertEquals(ExecutionStatus.SUCCESS, step.status)
        assertTrue(step.detail.contains("09:31"))
        val intent = shadowOf(app).nextStartedActivity
        assertEquals(AlarmClock.ACTION_SET_ALARM, intent.action)
        assertEquals(9, intent.getIntExtra(AlarmClock.EXTRA_HOUR, -1))
        assertEquals(31, intent.getIntExtra(AlarmClock.EXTRA_MINUTES, -1))
        assertEquals("提醒喝水", intent.getStringExtra(AlarmClock.EXTRA_MESSAGE))
        assertTrue(intent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertFalse(intent.hasExtra(AlarmClock.EXTRA_DAYS))
        assertNull(shadowOf(app).nextStartedActivity)
        @Suppress("DEPRECATION")
        val permissions = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.toList()
        assertTrue(permissions.contains("com.android.alarm.permission.SET_ALARM"))
    }

    @Test fun nearMidnightLeavesEnoughTimeAndRollsIntoTheNextDay() {
        SystemAlarmAction.execute(app, "Midnight", time(23, 59, 58))
        val intent = shadowOf(app).nextStartedActivity
        assertEquals(0, intent.getIntExtra(AlarmClock.EXTRA_HOUR, -1))
        assertEquals(1, intent.getIntExtra(AlarmClock.EXTRA_MINUTES, -1))
    }

    @Test fun fiveSecondsBeforeTheNextMinuteKeepsThatMinute() {
        SystemAlarmAction.execute(app, "Boundary", time(9, 30, 55))
        assertEquals(31, shadowOf(app).nextStartedActivity.getIntExtra(AlarmClock.EXTRA_MINUTES, -1))
    }

    @Test fun missingClockOrRejectedLaunchFailsWithoutThrowingOrRetrying() {
        for (error in listOf(ActivityNotFoundException("No clock"), SecurityException("Denied"))) {
            var calls = 0
            val unavailable = object : ContextWrapper(app) {
                override fun startActivity(intent: Intent) {
                    calls++
                    throw error
                }
            }
            assertEquals(ExecutionStatus.FAILED,
                SystemAlarmAction.execute(unavailable, "Unavailable").status)
            assertEquals(1, calls)
        }
        assertEquals(ExecutionStatus.FAILED, SystemAlarmAction.execute(null, "Unavailable").status)
    }

    @Test fun alarmCompletesImmediatelyWithoutNotificationPermissionOrWaitingForDismissal() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        AppPrefs.setNotifyExecution(app, false)
        val actions = listOf(Action(ActionType.SYSTEM_ALARM),
            Action(ActionType.BROADCAST, "test.after.system.alarm"))
        val rule = Rule("alarm test", "喝水提醒", true, emptyList(), actions,
            emptyList(), emptyList(), 1, 1)
        val executor = ActionExecutor { app }
        var dispatched = false
        executor.executeLogged(rule, "Trigger", "test", actions, capture = true) {
            dispatched = true
        }
        assertTrue(dispatched)
        val intent = shadowOf(app).nextStartedActivity
        assertEquals(AlarmClock.ACTION_SET_ALARM, intent.action)
        assertEquals(rule.name, intent.getStringExtra(AlarmClock.EXTRA_MESSAGE))
        assertTrue(shadowOf(app).broadcastIntents.any { it.action == "test.after.system.alarm" })
        executor.cancelPending(rule.id)
        assertTrue(executor.restoreAll(rule.id).isEmpty())
        assertNull(shadowOf(app).nextStartedActivity)
    }
}
