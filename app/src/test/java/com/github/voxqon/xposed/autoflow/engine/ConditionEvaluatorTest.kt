package com.github.voxqon.xposed.autoflow.engine

import com.github.voxqon.xposed.autoflow.model.Condition
import com.github.voxqon.xposed.autoflow.model.ConditionType
import com.github.voxqon.xposed.autoflow.model.TriggerMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class ConditionEvaluatorTest {
    private val state = StateStore()

    @Test
    fun geofenceStateCanGateOtherEventsButExpiresAndRequiresLocation() {
        val inside = Condition(ConditionType.GEOFENCE, "31,121,200", "inside")
        val outside = inside.copy(param2 = "outside")
        val wifi = Condition(ConditionType.WIFI_ON, "on")
        val event = Event(EventTypes.WIFI, value = "on")
        state.locationOn = true
        state.wifiOn = true
        assertFalse(ConditionEvaluator.stateSatisfied(outside, state, 1_000))
        state.noteGeofence("31.0,121.0,200.0", "inside", 1_000)
        assertTrue(ConditionEvaluator.triggerSatisfied(listOf(inside, wifi), TriggerMode.ALL, event, state, 2_000))
        assertFalse(ConditionEvaluator.triggerSatisfied(listOf(outside, wifi), TriggerMode.ALL, event, state, 2_000))
        assertFalse(ConditionEvaluator.stateSatisfied(inside, state, 121_001))
        assertFalse(ConditionEvaluator.stateSatisfied(outside, state, 121_001))
        state.noteGeofence("31.0,121.0,200.0", "exit", 122_000)
        assertTrue(ConditionEvaluator.stateSatisfied(outside, state, 123_000))
        state.locationOn = false
        assertFalse(ConditionEvaluator.stateSatisfied(outside, state, 123_000))
        state.clearGeofences()
        state.locationOn = true
        assertFalse(ConditionEvaluator.stateSatisfied(outside, state, 123_000))
    }

    @Test
    fun baselineLocationSatisfiesStateConditionsWithoutSynthesizingAnEnterEvent() {
        val inside = Condition(ConditionType.GEOFENCE, "31,121,200", "inside")
        val enter = inside.copy(param2 = "enter")
        val baseline = Event(EventTypes.GEOFENCE, value = "inside", value2 = "31.0,121.0,200.0")
        state.locationOn = true
        state.noteGeofence(baseline.value2, baseline.value, 1_000)
        assertTrue(ConditionEvaluator.triggerSatisfied(listOf(inside), TriggerMode.ALL, baseline, state, 1_000))
        assertFalse(ConditionEvaluator.triggerSatisfied(listOf(enter), TriggerMode.ALL, baseline, state, 1_000))
        state.noteGeofence(baseline.value2, "unknown", 2_000)
        assertFalse(ConditionEvaluator.stateSatisfied(inside, state, 2_000))
    }

    @Test
    fun newDeviceConditionsRequireKnownStateAndTheirOwnEvent() {
        val power = Condition(ConditionType.POWER_SAVE_ON, "on")
        val night = Condition(ConditionType.NIGHT_MODE, "off")
        val unlocked = Condition(ConditionType.DEVICE_UNLOCKED)
        val disconnected = Condition(ConditionType.HEADSET_DISCONNECTED)
        listOf(power, night, unlocked, disconnected).forEach {
            assertFalse(ConditionEvaluator.stateSatisfied(it, state, 0))
        }
        state.powerSaveOn = true
        state.nightModeOn = false
        state.keyguardKnown = true
        state.headsetConnected = false
        listOf(power, night, unlocked, disconnected).forEach {
            assertTrue(ConditionEvaluator.stateSatisfied(it, state, 0))
            assertFalse(ConditionEvaluator.freshFor(it, Event(EventTypes.BATTERY, value = "50")))
        }
        assertTrue(ConditionEvaluator.freshFor(power, Event(EventTypes.POWER_SAVE, value = "on")))
        assertFalse(ConditionEvaluator.freshFor(power, Event(EventTypes.POWER_SAVE, value = "off")))
        assertTrue(ConditionEvaluator.freshFor(night, Event(EventTypes.NIGHT_MODE, value = "off")))
        assertTrue(ConditionEvaluator.freshFor(unlocked, Event(EventTypes.KEYGUARD, value = "hidden")))
        assertTrue(ConditionEvaluator.freshFor(disconnected, Event(EventTypes.HEADSET, value = "off")))
    }

    @Test
    fun timeWindowsCannotBeBypassedByAnyModeOrConditionOrder() {
        val app = Condition(ConditionType.APP_FOREGROUND, "com.example.camera")
        val range = Condition(ConditionType.TIME_RANGE, "09:00-18:00", "inside")
        state.foregroundPkg = "com.example.camera"
        val event = Event(EventTypes.APP_FG, pkg = state.foregroundPkg)
        for (mode in TriggerMode.entries) {
            for (conditions in listOf(listOf(app, range), listOf(range, app))) {
                assertTrue(ConditionEvaluator.triggerSatisfied(conditions, mode, event, state, 1_000L, at(9, 0)))
                assertTrue(ConditionEvaluator.triggerSatisfied(conditions, mode, event, state, 1_000L, at(17, 59)))
                assertFalse(ConditionEvaluator.triggerSatisfied(conditions, mode, event, state, 1_000L, at(18, 0)))
                assertFalse(ConditionEvaluator.triggerSatisfied(conditions, mode, event, state, 1_000L, at(8, 59)))
            }
        }
    }

    @Test
    fun outsideOvernightWindowAndMultipleWindowsAreMandatory() {
        val screen = Condition(ConditionType.SCREEN_ON)
        val outside = Condition(ConditionType.TIME_RANGE, "22:00-07:00", "outside")
        val daytime = Condition(ConditionType.TIME_RANGE, "09:00-18:00", "inside")
        state.screenOn = true
        val event = Event(EventTypes.SCREEN_ON, value = "on")
        for (mode in TriggerMode.entries) {
            assertTrue(ConditionEvaluator.triggerSatisfied(listOf(screen, outside), mode, event, state, 0L, at(7, 0)))
            assertFalse(ConditionEvaluator.triggerSatisfied(listOf(screen, outside), mode, event, state, 0L, at(22, 0)))
            assertFalse(ConditionEvaluator.triggerSatisfied(listOf(screen, outside), mode, event, state, 0L, at(0, 0)))
            assertFalse(ConditionEvaluator.triggerSatisfied(listOf(screen, outside, daytime), mode, event, state, 0L, at(8, 0)))
            assertTrue(ConditionEvaluator.triggerSatisfied(listOf(screen, outside, daytime), mode, event, state, 0L, at(12, 0)))
        }
    }

    @Test
    fun timeOnlyRulesUseTicksAndWindowsDoNotProvideFreshnessForOtherConditions() {
        val range = Condition(ConditionType.TIME_RANGE, "09:00-18:00", "inside")
        val tick = Event(EventTypes.TICK)
        state.wifiOn = true
        for (mode in TriggerMode.entries) {
            assertTrue(ConditionEvaluator.triggerSatisfied(listOf(range), mode, tick, state, 0L, at(9, 0)))
            assertFalse(ConditionEvaluator.triggerSatisfied(listOf(range), mode, Event(EventTypes.BATTERY), state, 0L, at(9, 0)))
            assertFalse(ConditionEvaluator.triggerSatisfied(
                listOf(range, Condition(ConditionType.WIFI_ON, "on")), mode, tick, state, 0L, at(9, 0)))
        }
        assertTrue(ConditionEvaluator.satisfiedForCurrentEvent(
            range, tick, state, 0L, at(9, 0)))
        assertFalse(ConditionEvaluator.satisfiedForCurrentEvent(
            range, tick, state, 0L, at(18, 0)))
        assertFalse(ConditionEvaluator.triggerSatisfied(
            listOf(range.copy(param = "invalid", param2 = "outside")), TriggerMode.ANY, tick, state, 0L, at(9, 0)))
    }

    private fun at(hour: Int, minute: Int): Long = Calendar.getInstance().apply {
        clear()
        set(2026, Calendar.NOVEMBER, 6, hour, minute)
    }.timeInMillis

    @Test
    fun lifecycleConditionsOnlyMatchTheirCurrentEvent() {
        val coldStart = Condition(ConditionType.APP_COLD_START, "com.example.camera")
        val wrongEvent = Event(EventTypes.APP_FG, pkg = "com.example.camera")
        val matchingEvent = Event(EventTypes.APP_COLD, pkg = "com.example.camera")

        assertFalse(ConditionEvaluator.stateSatisfied(coldStart, state, now = 1_000L))
        assertFalse(ConditionEvaluator.satisfiedForCurrentEvent(coldStart, wrongEvent, state, 1_000L))
        assertTrue(ConditionEvaluator.satisfiedForCurrentEvent(coldStart, matchingEvent, state, 1_000L))
        assertTrue(ConditionEvaluator.freshFor(coldStart, matchingEvent))
    }

    @Test
    fun ssidEventsMustMatchBothNetworkAndRequestedDirection() {
        val disconnect = Condition(ConditionType.WIFI_SSID, "Home", "disconnect")
        val unrelatedDisconnect = Event(EventTypes.SSID, value = "Guest", value2 = "disconnect")
        val matchingDisconnect = Event(EventTypes.SSID, value = "Home", value2 = "disconnect")

        state.currentSsid = null
        assertFalse(ConditionEvaluator.freshFor(disconnect, unrelatedDisconnect))
        assertTrue(ConditionEvaluator.freshFor(disconnect, matchingDisconnect))
        assertTrue(ConditionEvaluator.satisfiedForCurrentEvent(disconnect, matchingDisconnect, state, 1_000L))
    }

    @Test
    fun wifiTargetsWithDifferentLetterCaseAreDistinctNetworks() {
        val connect = Condition(ConditionType.WIFI_SSID, "Home", "connect")
        val disconnect = Condition(ConditionType.WIFI_SSID, "Home", "disconnect")
        val differentNetwork = Event(EventTypes.SSID, value = "home", value2 = "connect")
        state.currentSsid = "home"
        assertFalse(ConditionEvaluator.freshFor(connect, differentNetwork))
        assertFalse(ConditionEvaluator.stateSatisfied(connect, state, 1_000L))
        assertTrue(ConditionEvaluator.stateSatisfied(disconnect, state, 1_000L))
    }

    @Test
    fun bluetoothEventsMustMatchDeviceAndRequestedDirection() {
        val connect = Condition(ConditionType.BT_DEVICE, "Headphones", "connect")
        val unrelatedConnect = Event(EventTypes.BTDEV, value = "Keyboard", value2 = "connect")
        val matchingConnect = Event(EventTypes.BTDEV, value = "Headphones", value2 = "connect")

        state.noteBluetoothDevice("Headphones", connected = true)
        assertFalse(ConditionEvaluator.freshFor(connect, unrelatedConnect))
        assertTrue(ConditionEvaluator.freshFor(connect, matchingConnect))
        assertTrue(ConditionEvaluator.stateSatisfied(connect, state, now = 1_000L))
    }

    @Test
    fun bluetoothConditionCanMatchAddressWhenNameIsAvailable() {
        val address = "AA:BB:CC:DD:EE:FF"
        val condition = Condition(ConditionType.BT_DEVICE, address, "connect")
        val event = Event(EventTypes.BTDEV, value = "Headphones|$address", value2 = "connect")

        state.noteBluetoothDevice(event.value, connected = true)
        assertTrue(ConditionEvaluator.freshFor(condition, event))
        assertTrue(ConditionEvaluator.stateSatisfied(condition, state, now = 1_000L))
    }

    @Test
    fun bluetoothDisconnectConditionRequiresTheTargetDisconnectEvent() {
        val disconnect = Condition(ConditionType.BT_DEVICE, "Headphones", "disconnect")
        val targetDisconnect = Event(EventTypes.BTDEV, value = "Headphones", value2 = "disconnect")
        val targetConnect = Event(EventTypes.BTDEV, value = "Headphones", value2 = "connect")

        state.noteBluetoothDevice("Headphones", connected = false)
        assertTrue(ConditionEvaluator.freshFor(disconnect, targetDisconnect))
        assertFalse(ConditionEvaluator.freshFor(disconnect, targetConnect))
        assertTrue(ConditionEvaluator.stateSatisfied(disconnect, state, now = 1_000L))
    }

    @Test
    fun notConnectedWifiRequiresAKnownNetworkOrConfirmedDisconnection() {
        val condition = Condition(ConditionType.WIFI_SSID, "Home", "not_connected")
        assertFalse(ConditionEvaluator.stateSatisfied(condition, state, 0))
        state.wifiConnected = true
        assertFalse(ConditionEvaluator.stateSatisfied(condition, state, 0))
        state.currentSsid = "Home"
        assertFalse(ConditionEvaluator.stateSatisfied(condition, state, 0))
        state.currentSsid = "home"
        assertTrue(ConditionEvaluator.stateSatisfied(condition, state, 0))
        state.currentSsid = null
        state.wifiConnected = false
        assertTrue(ConditionEvaluator.stateSatisfied(condition, state, 0))
        assertFalse(ConditionEvaluator.stateSatisfied(condition.copy(param = " "), state, 0))
    }

    @Test
    fun notConnectedBluetoothChecksTheTargetAmongAllConnectedDevices() {
        val condition = Condition(ConditionType.BT_DEVICE, "aa:bb:cc:dd:ee:ff", "NOT_CONNECTED")
        assertFalse(ConditionEvaluator.stateSatisfied(condition, state, 0))
        state.noteBluetoothConnectionKnown()
        assertTrue(ConditionEvaluator.stateSatisfied(condition, state, 0))
        state.noteBluetoothDevice("Keyboard|11:22:33:44:55:66", true)
        assertTrue(ConditionEvaluator.stateSatisfied(condition, state, 0))
        state.noteBluetoothDevice("Headphones|AA:BB:CC:DD:EE:FF", true)
        assertFalse(ConditionEvaluator.stateSatisfied(condition, state, 0))
        state.noteBluetoothDevice("Keyboard|11:22:33:44:55:66", false)
        assertFalse(ConditionEvaluator.stateSatisfied(condition, state, 0))
        state.noteBluetooth(false)
        assertTrue(ConditionEvaluator.stateSatisfied(condition, state, 0))
        assertFalse(ConditionEvaluator.stateSatisfied(condition.copy(param = ""), state, 0))
    }

    @Test
    fun notConnectedTargetsCanGateAnotherEventWithoutPriorTargetConnections() {
        val wifi = Condition(ConditionType.WIFI_SSID, "Home", "not_connected")
        val bluetooth = Condition(ConditionType.BT_DEVICE, "Headphones", "not_connected")
        val screen = Condition(ConditionType.SCREEN_ON)
        val screenOn = Event(EventTypes.SCREEN_ON, value = "on")
        state.screenOn = true
        assertFalse(ConditionEvaluator.triggerSatisfied(listOf(screen, wifi, bluetooth),
            TriggerMode.ALL, screenOn, state, 0))
        state.wifiConnected = false
        state.noteBluetoothConnectionKnown()
        assertTrue(ConditionEvaluator.triggerSatisfied(listOf(screen, wifi, bluetooth),
            TriggerMode.ALL, screenOn, state, 0))
        state.currentSsid = "Home"
        state.wifiConnected = true
        assertFalse(ConditionEvaluator.triggerSatisfied(listOf(screen, wifi, bluetooth),
            TriggerMode.ALL, screenOn, state, 0))
        for (mode in TriggerMode.entries) {
            assertFalse(ConditionEvaluator.triggerSatisfied(listOf(bluetooth),
                mode, screenOn, state, 0))
            assertFalse(ConditionEvaluator.triggerSatisfied(listOf(bluetooth),
                mode, Event(EventTypes.TICK), state, 0))
        }
    }

    @Test
    fun notConnectedWifiFreshnessIncludesTargetDisconnectOtherNetworkAndRadioOff() {
        val condition = Condition(ConditionType.WIFI_SSID, "Home", "not_connected")
        state.wifiConnected = false
        for (event in listOf(
            Event(EventTypes.SSID, value = "Home", value2 = "disconnect"),
            Event(EventTypes.WIFI_CONNECTION, value = "off"),
            Event(EventTypes.WIFI, value = "off"),
        )) {
            assertTrue(ConditionEvaluator.triggerSatisfied(listOf(condition), TriggerMode.ALL, event, state, 0))
        }
        assertFalse(ConditionEvaluator.freshFor(condition,
            Event(EventTypes.SSID, value = "Guest", value2 = "disconnect")))
        assertFalse(ConditionEvaluator.freshFor(condition, Event(EventTypes.WIFI, value = "on")))
        state.currentSsid = "Guest"
        state.wifiConnected = true
        assertTrue(ConditionEvaluator.triggerSatisfied(listOf(condition), TriggerMode.ALL,
            Event(EventTypes.SSID, value = "Guest", value2 = "connect"), state, 0))
        state.currentSsid = "Home"
        assertFalse(ConditionEvaluator.triggerSatisfied(listOf(condition), TriggerMode.ALL,
            Event(EventTypes.SSID, value = "Home", value2 = "connect"), state, 0))
    }

    @Test
    fun notConnectedBluetoothFreshnessIgnoresOtherDevicesAndHandlesRadioOff() {
        val condition = Condition(ConditionType.BT_DEVICE, "AA:BB:CC:DD:EE:FF", "not_connected")
        state.noteBluetoothConnectionKnown()
        for (event in listOf(
            Event(EventTypes.BTDEV, value = "Headphones|aa:bb:cc:dd:ee:ff", value2 = "disconnect"),
            Event(EventTypes.BT, value = "off"),
            Event(EventTypes.BT_CONNECTION),
        )) {
            assertTrue(ConditionEvaluator.triggerSatisfied(listOf(condition), TriggerMode.ALL, event, state, 0))
        }
        for (event in listOf(
            Event(EventTypes.BTDEV, value = "Keyboard|11:22:33:44:55:66", value2 = "disconnect"),
            Event(EventTypes.BTDEV, value = "Keyboard|11:22:33:44:55:66", value2 = "connect"),
            Event(EventTypes.BT, value = "on"),
        )) {
            assertFalse(ConditionEvaluator.triggerSatisfied(listOf(condition), TriggerMode.ALL, event, state, 0))
        }
        state.noteBluetoothDevice("Headphones|AA:BB:CC:DD:EE:FF", true)
        assertFalse(ConditionEvaluator.triggerSatisfied(listOf(condition), TriggerMode.ALL,
            Event(EventTypes.BT_CONNECTION), state, 0))
    }

    @Test
    fun geofenceConditionsMatchTheConfiguredFenceAndDirectionOnly() {
        val condition = Condition(ConditionType.GEOFENCE, "31,121,200", "enter")
        val fenceId = "31.0,121.0,200.0"
        val enter = Event(EventTypes.GEOFENCE, value = "enter", value2 = fenceId)
        val exit = Event(EventTypes.GEOFENCE, value = "exit", value2 = fenceId)
        val otherFence = Event(EventTypes.GEOFENCE, value = "enter", value2 = "31.0,121.0,300.0")

        assertTrue(ConditionEvaluator.triggerSatisfied(listOf(condition), TriggerMode.ALL, enter, state, 1_000L))
        assertFalse(ConditionEvaluator.satisfiedForCurrentEvent(condition, exit, state, 1_000L))
        assertFalse(ConditionEvaluator.satisfiedForCurrentEvent(condition, otherFence, state, 1_000L))
        assertFalse(ConditionEvaluator.stateSatisfied(condition, state, 1_000L))
    }

    @Test
    fun batteryBelowIsStrictlyBelowThreshold() {
        val belowTwenty = Condition(ConditionType.BATTERY_BELOW, "20")
        val atTwenty = Event(EventTypes.BATTERY, value = "20")
        val atNineteen = Event(EventTypes.BATTERY, value = "19")

        state.batteryLevel = 20
        assertFalse(ConditionEvaluator.stateSatisfied(belowTwenty, state, now = 1_000L))
        assertFalse(ConditionEvaluator.freshFor(belowTwenty, atTwenty))
        assertTrue(ConditionEvaluator.freshFor(belowTwenty, atNineteen))
    }

    @Test
    fun invalidScreenDurationsCannotBecomeImmediatelySatisfied() {
        state.screenOn = true
        state.screenOnSince = 100L

        assertFalse(ConditionEvaluator.stateSatisfied(
            Condition(ConditionType.SCREEN_ON_FOR, "-1"), state, now = 200L
        ))
        assertFalse(ConditionEvaluator.stateSatisfied(
            Condition(ConditionType.SCREEN_ON_FOR, Long.MAX_VALUE.toString()), state, now = 200L
        ))
    }

    @Test
    fun triggerModeSupportsAllAndAnyWithoutChangingFreshEventRequirement() {
        val conditions = listOf(
            Condition(ConditionType.WIFI_ON, "on"),
            Condition(ConditionType.DND_ON, "on"),
        )
        val wifiOn = Event(EventTypes.WIFI, value = "on")

        state.wifiOn = true
        state.dndOn = false
        assertFalse(ConditionEvaluator.triggerSatisfied(conditions, TriggerMode.ALL, wifiOn, state, 1_000L))
        assertTrue(ConditionEvaluator.triggerSatisfied(conditions, TriggerMode.ANY, wifiOn, state, 1_000L))

        state.dndOn = true
        assertTrue(ConditionEvaluator.triggerSatisfied(conditions, TriggerMode.ALL, wifiOn, state, 1_000L))
    }

    @Test
    fun initiallyTrueExitMustBeObservedFalseBeforeItCanRestore() {
        assertFalse(ConditionEvaluator.shouldExit(
            initiallySatisfied = true,
            observedFalse = false,
            currentlySatisfied = true,
        ))
        assertTrue(ConditionEvaluator.shouldExit(
            initiallySatisfied = true,
            observedFalse = true,
            currentlySatisfied = true,
        ))
        assertTrue(ConditionEvaluator.shouldExit(
            initiallySatisfied = false,
            observedFalse = false,
            currentlySatisfied = true,
        ))
    }

    @Test
    fun anySsidDirectionMatchesDisconnectWithoutRemainingConnected() {
        val condition = Condition(ConditionType.WIFI_SSID, "Home")
        val disconnect = Event(EventTypes.SSID, value = "Home", value2 = "disconnect")

        state.currentSsid = null
        assertTrue(ConditionEvaluator.triggerSatisfied(
            listOf(condition), TriggerMode.ALL, disconnect, state, 1_000L
        ))
        assertFalse(ConditionEvaluator.satisfiedForCurrentEvent(
            condition, Event(EventTypes.SSID, value = "Guest", value2 = "disconnect"), state, 1_000L
        ))
        assertFalse(ConditionEvaluator.satisfiedForCurrentEvent(
            condition, Event(EventTypes.TICK), state, 1_000L
        ))
    }

    @Test
    fun anyBluetoothDirectionMatchesTheTargetDisconnectOnly() {
        val condition = Condition(ConditionType.BT_DEVICE, "Headphones", "ANY")
        val disconnect = Event(EventTypes.BTDEV, value = "Headphones", value2 = "disconnect")

        assertTrue(ConditionEvaluator.triggerSatisfied(
            listOf(condition), TriggerMode.ALL, disconnect, state, 1_000L
        ))
        assertFalse(ConditionEvaluator.satisfiedForCurrentEvent(
            condition, Event(EventTypes.BTDEV, value = "Keyboard", value2 = "disconnect"), state, 1_000L
        ))
        assertFalse(ConditionEvaluator.satisfiedForCurrentEvent(
            condition, Event(EventTypes.TICK), state, 1_000L
        ))
    }

    @Test
    fun connectingAnotherBluetoothDeviceKeepsAnExistingConditionSatisfied() {
        val headphones = Condition(ConditionType.BT_DEVICE, "Headphones", "connect")
        state.noteBluetoothDevice("Headphones|AA:BB:CC:DD:EE:FF", connected = true)
        state.noteBluetoothDevice("Keyboard|11:22:33:44:55:66", connected = true)
        state.noteBluetoothDevice("Keyboard|11:22:33:44:55:66", connected = false)

        assertTrue(ConditionEvaluator.stateSatisfied(headphones, state, 1_000L))
    }

    @Test
    fun historicalStateCannotTriggerEvenWhenEveryConditionIsSatisfied() {
        val conditions = listOf(Condition(ConditionType.WIFI_ON, "on"), Condition(ConditionType.DND_ON, "on"))
        state.wifiOn = true
        state.dndOn = true

        for (mode in TriggerMode.entries) {
            assertFalse(ConditionEvaluator.triggerSatisfied(conditions, mode, Event(EventTypes.TICK), state, 1_000L))
            assertFalse(ConditionEvaluator.triggerSatisfied(emptyList(), mode, Event(EventTypes.TICK), state, 1_000L))
        }
    }

    @Test
    fun repeatedScreenEventsDoNotDelayDurationConditions() {
        state.noteScreen(true, 0L)
        state.noteScreen(true, 59_000L)
        assertTrue(ConditionEvaluator.stateSatisfied(Condition(ConditionType.SCREEN_ON_FOR, "1"), state, 60_000L))

        state.noteScreen(false, 60_000L)
        state.noteScreen(false, 119_000L)
        assertTrue(ConditionEvaluator.stateSatisfied(Condition(ConditionType.SCREEN_OFF_FOR, "1"), state, 120_000L))
    }

    @Test
    fun durationConditionsWaitForAnInitialScreenObservation() {
        assertFalse(ConditionEvaluator.stateSatisfied(Condition(ConditionType.SCREEN_OFF_FOR, "0"), state, 60_000L))
    }
}
