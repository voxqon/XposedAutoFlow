package com.github.voxqon.xposed.autoflow.engine

import com.github.voxqon.xposed.autoflow.model.Condition
import com.github.voxqon.xposed.autoflow.model.ConditionType
import com.github.voxqon.xposed.autoflow.model.RuleJson
import com.github.voxqon.xposed.autoflow.model.TriggerMode
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConditionGroupTest {
    private fun group(vararg children: Condition) = Condition(ConditionType.GROUP, children = children.toList())

    @Test
    fun groupIsOneOrConditionWithinAnAllRule() {
        val state = StateStore().apply { wifiOn = true; charging = true }
        val group = group(Condition(ConditionType.WIFI_ON, "on"), Condition(ConditionType.BT_ON, "on"))
        val conditions = listOf(group, Condition(ConditionType.CHARGER_CONNECTED))
        assertTrue(ConditionEvaluator.triggerSatisfied(conditions, TriggerMode.ALL,
            Event(EventTypes.WIFI, value = "on"), state, 0))
        state.charging = false
        assertFalse(ConditionEvaluator.triggerSatisfied(conditions, TriggerMode.ALL,
            Event(EventTypes.WIFI, value = "on"), state, 0))
        state.charging = true
        state.wifiOn = false
        assertFalse(ConditionEvaluator.triggerSatisfied(conditions, TriggerMode.ALL,
            Event(EventTypes.CHARGER, value = "on"), state, 0))
        state.btOn = true
        assertTrue(ConditionEvaluator.triggerSatisfied(conditions, TriggerMode.ALL,
            Event(EventTypes.BT, value = "on"), state, 0))
    }

    @Test
    fun theSameChildMustBeBothSatisfiedAndFresh() {
        val state = StateStore().apply { wifiOn = true; btOn = false }
        val group = group(Condition(ConditionType.WIFI_ON, "on"), Condition(ConditionType.BT_ON, "on"))
        assertFalse(ConditionEvaluator.triggerSatisfied(listOf(group), TriggerMode.ALL,
            Event(EventTypes.BT, value = "off"), state, 0))
        assertFalse(ConditionEvaluator.triggerSatisfied(listOf(group), TriggerMode.ANY,
            Event(EventTypes.BATTERY, value = "70"), state, 0))
        val transient = group(Condition(ConditionType.APP_LEFT, "camera"), Condition(ConditionType.WIFI_ON, "off"))
        assertTrue(ConditionEvaluator.satisfiedForCurrentEvent(transient,
            Event(EventTypes.APP_LEFT, pkg = "camera"), state, 0))
        assertFalse(ConditionEvaluator.satisfiedForCurrentEvent(transient,
            Event(EventTypes.TICK), state, 0))
    }

    @Test
    fun notConnectedTargetsRemainAlternativesWithinAConditionGroup() {
        val state = StateStore().apply {
            charging = true
            currentSsid = "Home"
            wifiConnected = true
            noteBluetoothConnectionKnown()
        }
        val group = group(Condition(ConditionType.WIFI_SSID, "Home", "not_connected"),
            Condition(ConditionType.BT_DEVICE, "Headphones", "not_connected"))
        val conditions = listOf(Condition(ConditionType.CHARGER_CONNECTED), group)
        val charger = Event(EventTypes.CHARGER, value = "on")
        assertTrue(ConditionEvaluator.triggerSatisfied(conditions, TriggerMode.ALL, charger, state, 0))
        state.noteBluetoothDevice("Headphones", true)
        assertFalse(ConditionEvaluator.triggerSatisfied(conditions, TriggerMode.ALL, charger, state, 0))
        state.currentSsid = "Guest"
        assertTrue(ConditionEvaluator.triggerSatisfied(conditions, TriggerMode.ALL, charger, state, 0))
    }

    @Test
    fun aTimeRangeInsideAGroupIsAnAlternativeAndEmptyGroupsNeverSatisfy() {
        val state = StateStore().apply { wifiOn = true }
        val group = group(Condition(ConditionType.TIME_RANGE, "invalid"), Condition(ConditionType.WIFI_ON, "on"))
        assertTrue(ConditionEvaluator.triggerSatisfied(listOf(group), TriggerMode.ALL,
            Event(EventTypes.WIFI, value = "on"), state, 0))
        assertFalse(ConditionEvaluator.stateSatisfied(group(), state, 0))
        assertFalse(ConditionEvaluator.triggerSatisfied(listOf(group()), TriggerMode.ALL,
            Event(EventTypes.TICK), state, 0))
    }

    @Test
    fun groupJsonRoundTripPreservesMetadataAndRejectsPartialUnknownGroups() {
        val group = group(Condition(ConditionType.APP_FOREGROUND, "camera", appName = "Camera"),
            Condition(ConditionType.GEOFENCE, "31,121,200", "inside", "Home"))
        assertEquals(group, RuleJson.conditionFromJson(RuleJson.conditionToJson(group)))
        val malformed = RuleJson.conditionToJson(group)
        malformed.getJSONArray("children").put(JSONObject().put("type", "future"))
        assertTrue(RuleJson.conditionFromJson(malformed)!!.children.isEmpty())
        val nested = RuleJson.conditionToJson(group)
        nested.getJSONArray("children").put(RuleJson.conditionToJson(group))
        assertTrue(RuleJson.conditionFromJson(nested)!!.children.isEmpty())
    }

    @Test
    fun additionalConditionsDoNotTreatUnknownStatesAsDisconnectedOrOff() {
        val state = StateStore()
        val types = listOf(ConditionType.AIRPLANE_MODE, ConditionType.HOTSPOT_ON,
            ConditionType.WIFI_DISCONNECTED, ConditionType.BT_DISCONNECTED, ConditionType.APP_NOT_FOREGROUND)
        types.forEach { assertFalse(ConditionEvaluator.stateSatisfied(Condition(it, "off"), state, 0)) }
        state.airplaneModeOn = false
        state.hotspotOn = false
        state.wifiConnected = false
        state.noteBluetooth(false)
        types.take(4).forEach { assertTrue(ConditionEvaluator.stateSatisfied(Condition(it, "off"), state, 0)) }
        state.foregroundPkg = "maps"
        assertTrue(ConditionEvaluator.stateSatisfied(Condition(ConditionType.APP_NOT_FOREGROUND, "camera"), state, 0))
        state.noteBluetoothDevice("A|01", true)
        state.noteBluetoothDevice("B|02", true)
        state.noteBluetoothDevice("A|01", false)
        assertTrue(ConditionEvaluator.stateSatisfied(Condition(ConditionType.BT_CONNECTED), state, 0))
        assertFalse(ConditionEvaluator.stateSatisfied(Condition(ConditionType.BT_DISCONNECTED), state, 0))
    }
}
