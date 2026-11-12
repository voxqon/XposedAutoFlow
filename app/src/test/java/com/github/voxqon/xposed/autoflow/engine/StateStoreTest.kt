package com.github.voxqon.xposed.autoflow.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StateStoreTest {
    private val state = StateStore()

    @Test
    fun rapidReturnToThePreviousAppIsARealForegroundTransition() {
        assertTrue(state.noteForeground("com.example.camera"))
        assertTrue(state.noteForeground("com.example.home"))
        assertTrue(state.noteForeground("com.example.camera"))
        assertEquals("com.example.camera", state.foregroundPkg)
        assertEquals("com.example.home", state.prevForegroundPkg)
    }

    @Test
    fun duplicateOrInvalidForegroundEventsPreserveThePreviousApp() {
        state.noteForeground("com.example.home")
        state.noteForeground("com.example.camera")
        assertFalse(state.noteForeground("com.example.camera"))
        assertFalse(state.noteForeground(null))
        assertFalse(state.noteForeground(""))
        assertEquals("com.example.home", state.prevForegroundPkg)
        assertEquals("com.example.camera", state.foregroundPkg)
    }

    @Test
    fun screenClocksOnlyRestartAfterARealStateChange() {
        state.noteScreen(false, 0L)
        state.noteScreen(false, 100L)
        assertEquals(0L, state.screenOffSince)
        state.noteScreen(true, 200L)
        state.noteScreen(true, 300L)
        assertEquals(200L, state.screenOnSince)
        state.noteScreen(false, 400L)
        assertEquals(400L, state.screenOffSince)
    }

    @Test
    fun disconnectingOneDevicePreservesOtherConnections() {
        state.noteBluetoothDevice("Headphones|AA:BB:CC:DD:EE:FF", connected = true)
        state.noteBluetoothDevice("Keyboard|11:22:33:44:55:66", connected = true)
        state.noteBluetoothDevice("Renamed headphones|aa:bb:cc:dd:ee:ff", connected = false)

        assertFalse(state.isBluetoothDeviceConnected("Headphones"))
        assertTrue(state.isBluetoothDeviceConnected("Keyboard"))
        assertTrue(state.isBluetoothDeviceConnected("11:22:33:44:55:66"))
        assertTrue(state.btOn)
    }

    @Test
    fun bluetoothOffClearsAllConnections() {
        state.noteBluetoothDevice("Headphones", connected = true)
        state.noteBluetoothDevice("Keyboard", connected = true)
        state.noteBluetooth(false)
        assertFalse(state.btOn)
        assertFalse(state.isBluetoothDeviceConnected("Headphones"))
        assertFalse(state.isBluetoothDeviceConnected("Keyboard"))
    }

    @Test
    fun renamedDeviceReplacesItsPreviousName() {
        state.noteBluetoothDevice("Old name|AA:BB:CC:DD:EE:FF", connected = true)
        state.noteBluetoothDevice("New name|aa:bb:cc:dd:ee:ff", connected = true)
        assertFalse(state.isBluetoothDeviceConnected("Old name"))
        assertTrue(state.isBluetoothDeviceConnected("New name"))
        assertTrue(state.isBluetoothDeviceConnected("AA:BB:CC:DD:EE:FF"))
    }
}
