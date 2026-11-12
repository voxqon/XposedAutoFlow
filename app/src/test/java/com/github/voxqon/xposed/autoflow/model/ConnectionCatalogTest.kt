package com.github.voxqon.xposed.autoflow.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionCatalogTest {
    @Test
    fun catalogRoundTripKeepsIdentifiersSeparateFromLabels() {
        val catalog = ConnectionCatalog(
            wifi = listOf(ConnectionTarget(" Home \"5G\" ")),
            bluetooth = listOf(ConnectionTarget("AA:BB:CC:DD:EE:FF", "Headphones")),
            wifiReadable = true,
            bluetoothReadable = false,
        )
        assertEquals(catalog, ConnectionCatalog.fromJson(catalog.toJson()))
    }

    @Test
    fun sameBluetoothNameRequiresAnAddress() {
        val targets = listOf(
            ConnectionTarget("AA:BB:CC:DD:EE:FF", "Headphones"),
            ConnectionTarget("11:22:33:44:55:66", "Headphones"),
        )
        assertNull(ConnectionCatalog.bluetoothTarget(targets, "Headphones"))
        assertEquals(targets.first(),
            ConnectionCatalog.bluetoothTarget(targets, "aa:bb:cc:dd:ee:ff"))
        assertNull(ConnectionCatalog.bluetoothTarget(targets, "00:00:00:00:00:00"))
    }

    @Test
    fun wifiNamesKeepCaseSpacesAndEmbeddedQuotes() {
        assertEquals(" Home \"5G\" ", ConnectionCatalog.wifiName("\" Home \"5G\" \""))
        assertEquals("home", ConnectionCatalog.wifiName("home"))
        assertNull(ConnectionCatalog.wifiName("<unknown ssid>"))
        assertNull(ConnectionCatalog.wifiName(null))
    }

    @Test
    fun malformedAndOversizedCatalogsAreRejected() {
        assertNull(ConnectionCatalog.fromJson("invalid"))
        assertNull(ConnectionCatalog.fromJson(" ".repeat(ConnectionCatalog.MAX_JSON_LENGTH + 1)))
        val catalog = ConnectionCatalog.fromJson(
            """{"wifi":[null,{"value":""},{"value":"Home"},{"value":"Home"}]}"""
        )
        assertEquals(listOf(ConnectionTarget("Home")), catalog?.wifi)
    }

    @Test
    fun connectionActionsAndExplicitExitActionsSurviveRuleSerialization() {
        val rule = Rule("connections", "Home", true,
            listOf(Condition(ConditionType.WIFI_SSID, "Home", "connect")),
            listOf(
                Action(ActionType.WIFI_CONNECTION, "Home", "connect"),
                Action(ActionType.BLUETOOTH_CONNECTION, "AA:BB:CC:DD:EE:FF", "connect"),
                Action(ActionType.WIFI, "toggle"),
            ),
            listOf(Condition(ConditionType.BT_DEVICE, "AA:BB:CC:DD:EE:FF", "disconnect")),
            listOf(Action(ActionType.BLUETOOTH_CONNECTION, "AA:BB:CC:DD:EE:FF", "disconnect")),
            1, 2,
        )
        assertEquals(rule, RuleJson.ruleFromJson(RuleJson.ruleToJson(rule)))
    }
}
