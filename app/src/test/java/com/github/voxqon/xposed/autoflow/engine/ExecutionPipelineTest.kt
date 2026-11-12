package com.github.voxqon.xposed.autoflow.engine

import com.github.voxqon.xposed.autoflow.app.ExecutionLogProvider
import com.github.voxqon.xposed.autoflow.model.Action
import com.github.voxqon.xposed.autoflow.model.ActionType
import com.github.voxqon.xposed.autoflow.model.ExecutionLog
import com.github.voxqon.xposed.autoflow.model.ExecutionStatus
import com.github.voxqon.xposed.autoflow.model.Rule
import com.github.voxqon.xposed.autoflow.store.ExecutionLogStore
import android.app.NotificationManager
import android.app.Notification
import android.net.Uri
import android.provider.Settings
import com.github.voxqon.xposed.autoflow.model.SoundKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34])
class ExecutionPipelineTest {
    @Before
    fun registerNotificationBridgeAndGrantPermission() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val provider = Robolectric.buildContentProvider(ExecutionLogProvider::class.java).create().get()
        ShadowContentResolver.registerProviderInternal("com.github.voxqon.xposed.autoflow.execution-logs", provider)
    }

    @Test
    fun executorWaitsBeforeEachActionAndReportsOneExecutionThroughAllDelays() {
        val app = RuntimeEnvironment.getApplication()
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val looper = shadowOf(handler.looper)
        val actions = listOf(
            Action(ActionType.BROADCAST, "test.delayed.first", delaySeconds = 2),
            Action(ActionType.BROADCAST, "test.delayed.second", delaySeconds = 3),
            Action(ActionType.BROADCAST, "test.delayed.third"),
        )
        val rule = Rule("delayed", "Delayed task", true, emptyList(), actions, emptyList(), emptyList(), 1, 1)
        ActionExecutor { app }.executeLogged(rule, "Trigger", "test", actions, capture = false,
            actionHandler = handler)
        val notifications = shadowOf(app.getSystemService(NotificationManager::class.java))
        val id = rule.id.hashCode() and 0x7FFFFFFF
        assertTrue(notifications.getNotification(id).flags and Notification.FLAG_ONGOING_EVENT != 0)
        fun sent() = shadowOf(app).broadcastIntents.mapNotNull { it.action }.filter { it.startsWith("test.delayed.") }
        assertTrue(sent().isEmpty())
        looper.idleFor(Duration.ofMillis(1999))
        assertTrue(sent().isEmpty())
        looper.idleFor(Duration.ofMillis(1))
        assertEquals(listOf("test.delayed.first"), sent())
        looper.idleFor(Duration.ofMillis(2999))
        assertEquals(listOf("test.delayed.first"), sent())
        looper.idleFor(Duration.ofMillis(1))
        assertEquals(listOf("test.delayed.first", "test.delayed.second", "test.delayed.third"), sent())
        assertEquals(0, notifications.getNotification(id).flags and Notification.FLAG_ONGOING_EVENT)
        val store = ExecutionLogStore.get(app)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (store.load().singleOrNull()?.status != ExecutionStatus.SUCCESS && System.nanoTime() < deadline) {
            Thread.sleep(5)
        }
        assertEquals(1, store.load().size)
        assertEquals(3, store.load().single().steps.size)
        assertEquals(5000L, store.load().single().durationMs)
    }

    @Test
    fun cancellingAWaitingExecutionPreventsItsActionsAndClearsItsNotification() {
        val app = RuntimeEnvironment.getApplication()
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val actions = listOf(Action(ActionType.BROADCAST, "test.cancelled", delaySeconds = 2))
        val rule = Rule("cancelled", "Cancelled", true, emptyList(), actions, emptyList(), emptyList(), 1, 1)
        val executor = ActionExecutor { app }
        executor.executeLogged(rule, "Trigger", "test", actions, capture = false, actionHandler = handler)
        executor.cancelPending(rule.id)
        shadowOf(handler.looper).idleFor(Duration.ofSeconds(3))
        assertTrue(shadowOf(app).broadcastIntents.none { it.action == "test.cancelled" })
        assertNull(shadowOf(app.getSystemService(NotificationManager::class.java))
            .getNotification(rule.id.hashCode() and 0x7FFFFFFF))
    }

    @Test
    fun executionIsDeliveredToPrivateStorageWithActionFailuresAndImmediateConnectionRejection() {
        val app = RuntimeEnvironment.getApplication()
        val provider = Robolectric.buildContentProvider(ExecutionLogProvider::class.java).create().get()
        ShadowContentResolver.registerProviderInternal("com.github.voxqon.xposed.autoflow.execution-logs", provider)
        val actions = listOf(Action(ActionType.BROADCAST, "autotasklite.test"),
            Action(ActionType.OPEN_APP, "com.example.missing"),
            Action(ActionType.WIFI_CONNECTION))
        val rule = Rule("pipeline", "Test task", true, emptyList(), actions,
            emptyList(), emptyList(), 1, 1)
        ActionExecutor { app }.executeLogged(rule, "Trigger", "screen_on", actions, capture = false)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        val store = ExecutionLogStore.get(app)
        var logs = emptyList<ExecutionLog>()
        while (logs.isEmpty() && System.nanoTime() < deadline) {
            logs = store.load()
            if (logs.isEmpty()) Thread.sleep(10)
        }
        assertEquals(1, logs.size)
        val log = logs.single()
        assertEquals("Test task", log.ruleName)
        assertEquals(ExecutionStatus.FAILED, log.status)
        assertEquals(listOf(ExecutionStatus.SUCCESS, ExecutionStatus.FAILED, ExecutionStatus.FAILED),
            log.steps.map { it.status })
        assertTrue(log.steps.first().name.contains("autotasklite.test"))
    }

    @Test
    fun notificationsArePostedForRealFailuresAndSkippedButNotForEmptyRuns() {
        val app = RuntimeEnvironment.getApplication()
        val provider = Robolectric.buildContentProvider(ExecutionLogProvider::class.java).create().get()
        ShadowContentResolver.registerProviderInternal("com.github.voxqon.xposed.autoflow.execution-logs", provider)
        val manager = app.getSystemService(NotificationManager::class.java)
        val nm = shadowOf(manager)

        // 失败动作：结果通知必须出现
        ActionExecutor { app }.executeLogged(
            Rule("fail", "Fail task", true, emptyList(),
                listOf(Action(ActionType.OPEN_APP, "com.example.missing")),
                emptyList(), emptyList(), 1, 1),
            "Trigger", "screen_on",
            listOf(Action(ActionType.OPEN_APP, "com.example.missing")), capture = false)
        assertNotNull("failed run must notify", nm.getNotification("fail".hashCode() and 0x7FFFFFFF))

        // 空动作：不打扰
        ActionExecutor { app }.executeLogged(
            Rule("empty", "Empty task", true, emptyList(), emptyList(),
                emptyList(), emptyList(), 1, 1),
            "Trigger", "screen_on", emptyList(), capture = false)
        assertNull("empty run must not notify", nm.getNotification("empty".hashCode() and 0x7FFFFFFF))
    }

    @Test
    fun asynchronousCompletionReplacesTheOngoingNotification() {
        val app = RuntimeEnvironment.getApplication()
        configureSound()
        val actions = listOf(Action(ActionType.PLAY_SOUND, SoundKind.NOTIFICATION.code, "1"))
        val rule = Rule("async", "Async task", true, emptyList(), actions, emptyList(), emptyList(), 1, 1)
        val notifications = shadowOf(app.getSystemService(NotificationManager::class.java))
        ActionExecutor { app }.executeLogged(rule, "Trigger", "test", actions, capture = false)
        val id = rule.id.hashCode() and 0x7FFFFFFF
        assertTrue(notifications.getNotification(id).flags and Notification.FLAG_ONGOING_EVENT != 0)
        val audio = shadowOf(ShadowLooper.getAllLoopers().first { it.thread.name == "atlt-audio" })
        audio.idle()
        audio.idleFor(Duration.ofSeconds(1))
        assertEquals(0, notifications.getNotification(id).flags and Notification.FLAG_ONGOING_EVENT)
        assertEquals("Trigger · Async task", notifications.getNotification(id).extras.getString(Notification.EXTRA_TITLE))
    }

    @Test
    fun lateCompletionCannotReplaceANewerExecutionResultAndExitClearsOngoingNotifications() {
        val app = RuntimeEnvironment.getApplication()
        configureSound()
        val sound = listOf(Action(ActionType.PLAY_SOUND, SoundKind.NOTIFICATION.code, "1"))
        val rule = Rule("async", "Async task", true, emptyList(), sound, emptyList(), emptyList(), 1, 1)
        val executor = ActionExecutor { app }
        val notifications = shadowOf(app.getSystemService(NotificationManager::class.java))
        val id = rule.id.hashCode() and 0x7FFFFFFF
        executor.executeLogged(rule, "Old", "test", sound, capture = false)
        val audio = shadowOf(ShadowLooper.getAllLoopers().first { it.thread.name == "atlt-audio" })
        audio.idle()
        executor.executeLogged(rule, "New", "test",
            listOf(Action(ActionType.BROADCAST, "test.notification")), capture = false)
        audio.idleFor(Duration.ofSeconds(1))
        assertEquals("New · Async task", notifications.getNotification(id).extras.getString(Notification.EXTRA_TITLE))
        executor.executeLogged(rule, "Trigger", "test", sound, capture = true)
        assertTrue(notifications.getNotification(id).flags and Notification.FLAG_ONGOING_EVENT != 0)
        executor.executeLogged(rule, "Exit", "test", emptyList(), capture = false, restoreCaptured = true)
        audio.idle()
        assertNull(notifications.getNotification(id))
    }

    private fun configureSound() {
        val app = RuntimeEnvironment.getApplication()
        val uri = Uri.parse("content://media/internal/audio/media/42")
        Settings.System.putString(app.contentResolver, Settings.System.NOTIFICATION_SOUND, uri.toString())
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(app, uri), ShadowMediaPlayer.MediaInfo(10_000, 0))
    }

    @Test
    fun exitConnectionIsNotCancelledByCapturedValueRestorationButCanBeCancelledExplicitly() {
        val app = RuntimeEnvironment.getApplication()
        val provider = Robolectric.buildContentProvider(ExecutionLogProvider::class.java).create().get()
        ShadowContentResolver.registerProviderInternal("com.github.voxqon.xposed.autoflow.execution-logs", provider)
        val action = Action(ActionType.WIFI_CONNECTION, "Not currently connected", "disconnect")
        val rule = Rule("exit connection", "Exit task", true, emptyList(), emptyList(), emptyList(),
            listOf(action), 1, 1)
        val executor = ActionExecutor { app }
        executor.executeLogged(rule, "Exit", "test", listOf(action), capture = false, restoreCaptured = true)
        val connections = shadowOf(ShadowLooper.getAllLoopers().first { it.thread.name == "atlt-connections" })
        connections.idle()
        val store = ExecutionLogStore.get(app)
        fun awaitStatus(): ExecutionStatus {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (System.nanoTime() < deadline) {
                store.load().firstOrNull()?.takeIf { it.status != ExecutionStatus.SUBMITTED }?.let { return it.status }
                Thread.sleep(5)
            }
            error("Connection completion was not recorded")
        }
        assertEquals(ExecutionStatus.SUCCESS, awaitStatus())
        store.clear()
        connections.pause()
        // Advance the wall clock so this execution is newer than clear's timestamp.
        org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofMillis(1))
        executor.executeLogged(rule, "Exit", "test", listOf(action), capture = false)
        executor.cancelPending(rule.id)
        connections.idle()
        assertEquals(ExecutionStatus.SKIPPED, awaitStatus())
    }

    @Test
    fun notConnectedStateIsRejectedAsAnActionDirection() {
        val app = RuntimeEnvironment.getApplication()
        var wifiStatus: ExecutionStatus? = null
        var bluetoothStatus: ExecutionStatus? = null
        ConnectionActions.wifi(app, "Home", "not_connected", null) { status, _ -> wifiStatus = status }
        ConnectionActions.bluetooth(app, "AA:BB:CC:DD:EE:FF", "not_connected", null) { status, _ ->
            bluetoothStatus = status
        }
        assertEquals(ExecutionStatus.FAILED, wifiStatus)
        assertEquals(ExecutionStatus.FAILED, bluetoothStatus)
    }
}
