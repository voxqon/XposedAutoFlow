package com.github.voxqon.xposed.autoflow.engine

import android.provider.Settings
import android.view.Surface
import android.view.WindowManager
import com.github.voxqon.xposed.autoflow.model.Action
import com.github.voxqon.xposed.autoflow.model.ActionType
import com.github.voxqon.xposed.autoflow.model.ExecutionStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34])
class ActionCompatibilityTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test
    fun rotationLockUsesTheDisplayedOrientationAndRestoresBothSettings() {
        val resolver = app.contentResolver
        Settings.System.putInt(resolver, "accelerometer_rotation", 1)
        Settings.System.putInt(resolver, "user_rotation", 0)
        @Suppress("DEPRECATION")
        val display = app.getSystemService(WindowManager::class.java).defaultDisplay
        shadowOf(display).setRotation(Surface.ROTATION_90)
        val executor = ActionExecutor { app }
        assertEquals(ExecutionStatus.SUCCESS, executor.executeAll("in memory",
            listOf(Action(ActionType.ROTATION_LOCK, "lock")), true).single().status)
        assertEquals(0, Settings.System.getInt(resolver, "accelerometer_rotation"))
        assertEquals(Surface.ROTATION_90, Settings.System.getInt(resolver, "user_rotation"))
        assertEquals(ExecutionStatus.SUCCESS, executor.restoreAll("in memory").single().status)
        assertEquals(1, Settings.System.getInt(resolver, "accelerometer_rotation"))
        assertEquals(0, Settings.System.getInt(resolver, "user_rotation"))
    }

    @Test
    fun unknownRotationAndInvalidCommandsCannotGuessAToggleDirection() {
        Settings.System.putInt(app.contentResolver, "accelerometer_rotation", -1)
        val executor = ActionExecutor { app }
        listOf("toggle", "invalid").forEach { command ->
            assertEquals(ExecutionStatus.FAILED, executor.executeAll("rotation",
                listOf(Action(ActionType.ROTATION_LOCK, command)), false).single().status)
            assertEquals(-1, Settings.System.getInt(app.contentResolver, "accelerometer_rotation"))
        }
    }

    @Test
    fun serviceComponentsExpandRelativeClassNames() {
        ActionExecutor { app }.executeAll("service",
            listOf(Action(ActionType.START_SERVICE, "com.example.target/.TaskService")), false)
        val component = shadowOf(app).nextStartedService.component
        assertEquals("com.example.target", component?.packageName)
        assertEquals("com.example.target.TaskService", component?.className)
    }
}
