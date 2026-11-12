package com.github.voxqon.xposed.autoflow.engine

import android.media.AudioManager
import android.os.HandlerThread
import com.github.voxqon.xposed.autoflow.app.AppPrefs
import com.github.voxqon.xposed.autoflow.model.Action
import com.github.voxqon.xposed.autoflow.model.ActionType
import com.github.voxqon.xposed.autoflow.model.Condition
import com.github.voxqon.xposed.autoflow.model.ConditionType
import com.github.voxqon.xposed.autoflow.model.Rule
import com.github.voxqon.xposed.autoflow.store.RuleConfigContract
import com.github.voxqon.xposed.autoflow.store.RuleStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34])
class RuleEngineTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var engine: RuleEngine
    private lateinit var thread: HandlerThread

    private fun rule(actions: List<Action>, restores: List<Action> = emptyList()) = Rule(
        "engine test", "Test", true, listOf(Condition(ConditionType.SCREEN_ON)), actions,
        listOf(Condition(ConditionType.SCREEN_OFF)), restores, 1, 1,
    )

    @Before
    fun setup() {
        shadowOf(app.contentResolver).registerInputStreamSupplier(RuleConfigContract.URI) {
            AppPrefs.configurationSnapshot(app).byteInputStream()
        }
        RuleStore.initialize(app)
        engine = RuleEngine(StateStore(), ActionExecutor { app })
        thread = RuleEngine::class.java.getDeclaredField("engineThread").apply { isAccessible = true }
            .get(engine) as HandlerThread
    }

    @After
    fun cleanup() {
        thread.quitSafely()
    }

    private fun save(rules: List<Rule>) {
        AppPrefs.saveRules(app, rules)
        RuleStore.invalidate()
    }

    private fun start(rule: Rule) {
        save(listOf(rule))
        engine.start()
        shadowOf(thread.looper).idle()
    }

    private fun event(type: String, value: String? = null) {
        engine.post(Event(type, value = value))
        shadowOf(thread.looper).idle()
    }

    private fun broadcasts(action: String) = shadowOf(app).broadcastIntents.count { it.action == action }

    @Test
    fun exitGroupUsesHysteresisForTheWholeGroupAndTriggersOnce() {
        val grouped = rule(listOf(Action(ActionType.BROADCAST, "test.group.trigger")),
            listOf(Action(ActionType.BROADCAST, "test.group.exit"))).copy(
            exitConditions = listOf(Condition(ConditionType.GROUP, children = listOf(
                Condition(ConditionType.WIFI_ON, "on"), Condition(ConditionType.CHARGER_CONNECTED)))),
        )
        start(grouped)
        event(EventTypes.WIFI, "on")
        event(EventTypes.SCREEN_ON, "on")
        event(EventTypes.CHARGER, "on")
        event(EventTypes.WIFI, "off")
        assertEquals(0, broadcasts("test.group.exit"))
        event(EventTypes.CHARGER, "off")
        event(EventTypes.WIFI, "on")
        assertEquals(1, broadcasts("test.group.trigger"))
        assertEquals(1, broadcasts("test.group.exit"))
    }

    @Test
    fun notConnectedWifiTriggersOnRadioOffAndExitsWhenTheTargetReconnects() {
        val rule = rule(listOf(Action(ActionType.BROADCAST, "test.wifi.absent")),
            listOf(Action(ActionType.BROADCAST, "test.wifi.present"))).copy(
            conditions = listOf(Condition(ConditionType.WIFI_SSID, "Home", "not_connected")),
            exitConditions = listOf(Condition(ConditionType.WIFI_SSID, "Home", "connect")),
        )
        start(rule)
        assertEquals(0, broadcasts("test.wifi.absent"))
        event(EventTypes.WIFI, "off")
        assertEquals(1, broadcasts("test.wifi.absent"))
        engine.post(Event(EventTypes.SSID, value = "Home", value2 = "connect"))
        shadowOf(thread.looper).idle()
        assertEquals(1, broadcasts("test.wifi.present"))
        engine.post(Event(EventTypes.SSID, value = "Guest", value2 = "connect"))
        shadowOf(thread.looper).idle()
        assertEquals(2, broadcasts("test.wifi.absent"))
    }

    @Test
    fun notConnectedTargetAsAnExitConditionRestoresOnlyAfterTheTargetDisconnects() {
        val rule = rule(listOf(Action(ActionType.BROADCAST, "test.bluetooth.trigger")),
            listOf(Action(ActionType.BROADCAST, "test.bluetooth.exit"))).copy(
            exitConditions = listOf(Condition(ConditionType.BT_DEVICE, "AA:BB:CC:DD:EE:FF", "not_connected")),
        )
        start(rule)
        fun device(target: String, direction: String) {
            engine.post(Event(EventTypes.BTDEV, value = target, value2 = direction))
            shadowOf(thread.looper).idle()
        }
        device("Headphones|AA:BB:CC:DD:EE:FF", "connect")
        event(EventTypes.SCREEN_ON, "on")
        device("Keyboard|11:22:33:44:55:66", "connect")
        device("Keyboard|11:22:33:44:55:66", "disconnect")
        assertEquals(0, broadcasts("test.bluetooth.exit"))
        device("Headphones|aa:bb:cc:dd:ee:ff", "disconnect")
        assertEquals(1, broadcasts("test.bluetooth.trigger"))
        assertEquals(1, broadcasts("test.bluetooth.exit"))
    }

    @Test
    fun groupChildrenRegisterGeofencesAndEnableTheTimeHeartbeat() {
        val fence = Condition(ConditionType.GEOFENCE, "31,121,200", "inside")
        val grouped = rule(emptyList()).copy(conditions = listOf(
            Condition(ConditionType.GROUP, children = listOf(fence, Condition(ConditionType.TIMER, "23:59")))))
        var observed = emptyList<Condition>()
        engine.setGeofenceObserver { conditions, _ -> observed = conditions }
        start(grouped)
        assertEquals(listOf(fence), observed)
        val nextTick = RuleEngine::class.java.getDeclaredField("nextTickAt").apply { isAccessible = true }
            .getLong(engine)
        assertEquals(30_000L, nextTick - android.os.SystemClock.uptimeMillis())
    }

    @Test
    fun disablingATriggerCancelsItsDelayedActionsAndRestoresCapturedValues() {
        val audio = app.getSystemService(AudioManager::class.java)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 3, 0)
        val rule = rule(listOf(Action(ActionType.MEDIA_VOLUME, "100"),
            Action(ActionType.BROADCAST, "test.delayed.trigger", delaySeconds = 5)))
        start(rule)
        event(EventTypes.SCREEN_ON, "on")
        assertEquals(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        save(listOf(rule.copy(enabled = false)))
        event(EventTypes.TICK)
        shadowOf(thread.looper).idleFor(Duration.ofSeconds(6))
        assertEquals(3, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertEquals(0, broadcasts("test.delayed.trigger"))
    }

    @Test
    fun deletingARuleDuringADelayedExitCancelsTheExitAndRestoresImmediately() {
        val audio = app.getSystemService(AudioManager::class.java)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 3, 0)
        start(rule(listOf(Action(ActionType.MEDIA_VOLUME, "100")),
            listOf(Action(ActionType.BROADCAST, "test.delayed.exit", delaySeconds = 5))))
        event(EventTypes.SCREEN_ON, "on")
        event(EventTypes.SCREEN_OFF, "off")
        save(emptyList())
        event(EventTypes.TICK)
        assertEquals(3, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        shadowOf(thread.looper).idleFor(Duration.ofSeconds(6))
        assertEquals(0, broadcasts("test.delayed.exit"))
    }

    @Test
    fun delayedExitRunsInOrderAndRestoresOriginalStateAfterItsLastAction() {
        val audio = app.getSystemService(AudioManager::class.java)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 3, 0)
        start(rule(listOf(Action(ActionType.MEDIA_VOLUME, "100")),
            listOf(Action(ActionType.BROADCAST, "test.exit.first", delaySeconds = 1),
                Action(ActionType.BROADCAST, "test.exit.second", delaySeconds = 1))))
        event(EventTypes.SCREEN_ON, "on")
        event(EventTypes.SCREEN_OFF, "off")
        shadowOf(thread.looper).idleFor(Duration.ofSeconds(1))
        assertEquals(1, broadcasts("test.exit.first"))
        assertEquals(0, broadcasts("test.exit.second"))
        assertEquals(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        shadowOf(thread.looper).idleFor(Duration.ofSeconds(1))
        assertEquals(1, broadcasts("test.exit.second"))
        assertEquals(3, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    @Test
    fun duplicateScreenSignalsCannotRetriggerAfterAnotherConditionCausesExit() {
        val rule = rule(listOf(Action(ActionType.BROADCAST, "test.screen"))).copy(
            exitConditions = listOf(Condition(ConditionType.WIFI_ON, "on")),
        )
        start(rule)
        event(EventTypes.SCREEN_ON, "on")
        event(EventTypes.WIFI, "on")
        event(EventTypes.SCREEN_ON, "on")
        assertEquals(1, broadcasts("test.screen"))
        event(EventTypes.SCREEN_OFF, "off")
        event(EventTypes.WIFI, "off")
        event(EventTypes.SCREEN_ON, "on")
        assertEquals(2, broadcasts("test.screen"))
    }
}
