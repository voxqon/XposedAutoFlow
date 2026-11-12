package com.github.voxqon.xposed.autoflow.engine

/**
 * 进程内状态快照。RuleEngine / ConditionEvaluator 基于它做“与”关系状态判断。
 *
 * 说明：条件监听按“条件归属进程”原则单点产生事件（见各 Hook 注册器注释），
 * 因此 StateStore 是单进程权威状态；system_server 是绝大多数条件的归属进程。
 */
class StateStore {
    @Volatile var screenOn: Boolean = false
    @Volatile var screenOnSince: Long = -1L
    @Volatile var screenOffSince: Long = -1L

    @Volatile var foregroundPkg: String? = null
    @Volatile var prevForegroundPkg: String? = null

    @Volatile var wifiOn: Boolean = false
    @Volatile var currentSsid: String? = null
    @Volatile var btOn: Boolean = false
    @Volatile private var connectedBtDevices: Set<String> = emptySet()
    @Volatile var locationOn: Boolean = false
    @Volatile var rotationLocked: Boolean = false
    @Volatile var dndOn: Boolean = false
    @Volatile var charging: Boolean = false
    @Volatile var batteryLevel: Int = -1
    @Volatile var keyguardShown: Boolean = false
    @Volatile var keyguardKnown: Boolean = false
    @Volatile var powerSaveOn: Boolean? = null
    @Volatile var nightModeOn: Boolean? = null
    @Volatile var headsetConnected: Boolean? = null
    @Volatile var airplaneModeOn: Boolean? = null
    @Volatile var hotspotOn: Boolean? = null
    @Volatile var wifiConnected: Boolean? = null
    @Volatile private var bluetoothConnectionKnown = false
    val bluetoothConnected: Boolean?
        get() = if (bluetoothConnectionKnown) connectedBtDevices.isNotEmpty() else null

    fun noteBluetoothConnectionKnown() { bluetoothConnectionKnown = true }
    private val geofences = HashMap<String, Pair<Boolean, Long>>()

    fun noteGeofence(id: String?, value: String?, now: Long) {
        if (id == null) return
        when (value) {
            "inside", "enter" -> geofences[id] = true to now
            "outside", "exit" -> geofences[id] = false to now
            "unknown" -> geofences.remove(id)
        }
    }

    fun geofenceInside(id: String, now: Long): Boolean? =
        geofences[id]?.takeIf { now - it.second in 0..120_000L }?.first

    fun retainGeofences(ids: Set<String>) {
        geofences.keys.retainAll(ids)
    }

    fun clearGeofences() = geofences.clear()

    fun noteForeground(pkg: String?): Boolean {
        if (pkg.isNullOrBlank() || pkg == foregroundPkg) return false
        val old = foregroundPkg
        prevForegroundPkg = old
        foregroundPkg = pkg
        return true
    }

    @Synchronized
    fun noteScreen(on: Boolean, now: Long) {
        val initialized = if (on) screenOnSince >= 0 else screenOffSince >= 0
        if (screenOn == on && initialized) return
        screenOn = on
        if (on) screenOnSince = now else screenOffSince = now
    }

    fun noteBluetooth(on: Boolean) {
        btOn = on
        if (!on) {
            connectedBtDevices = emptySet()
            bluetoothConnectionKnown = true
        }
    }

    fun noteBluetoothDevice(device: String?, connected: Boolean) {
        if (device.isNullOrBlank()) return
        bluetoothConnectionKnown = true
        // Broadcasts include name|MAC; the MAC remains stable when the device name changes.
        val id = device.substringAfterLast('|')
        val others = connectedBtDevices.filterNot { it.substringAfterLast('|').equals(id, ignoreCase = true) }
        connectedBtDevices = if (connected) others.toSet() + device else others.toSet()
        if (connected) btOn = true
    }

    fun isBluetoothDeviceConnected(device: String): Boolean =
        connectedBtDevices.any { current ->
            current.split('|').any { it.equals(device, ignoreCase = true) }
        }
}
