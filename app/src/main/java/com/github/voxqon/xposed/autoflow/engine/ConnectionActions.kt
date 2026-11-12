package com.github.voxqon.xposed.autoflow.engine

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.HandlerThread
import com.github.voxqon.xposed.autoflow.model.ConnectionCatalog
import com.github.voxqon.xposed.autoflow.model.ConnectionMode
import com.github.voxqon.xposed.autoflow.model.ConnectionTarget
import com.github.voxqon.xposed.autoflow.model.ExecutionStatus
import com.github.voxqon.xposed.autoflow.util.Reflect
import com.github.voxqon.xposed.autoflow.util.XLog
import java.lang.reflect.Proxy
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
object ConnectionActions {
    private val handler by lazy { Handler(HandlerThread("atlt-connections").apply { start() }.looper) }
    private val wifiGeneration = AtomicLong()
    private val bluetoothGeneration = AtomicLong()
    private val bluetoothRequests = ConcurrentHashMap<String, Long>()
    private val ruleTokens = ConcurrentHashMap<String, Any>()
    private var nextBluetoothRequest = 0L
    private const val MAX_ATTEMPTS = 30
    private const val RETRY_MS = 500L

    fun cancelWifi() { wifiGeneration.incrementAndGet() }
    fun cancelBluetooth() { bluetoothGeneration.incrementAndGet() }
    fun cancelRule(ruleId: String) { ruleTokens.remove(ruleId) }

    private fun completion(callback: (ExecutionStatus, String) -> Unit): (ExecutionStatus, String) -> Unit {
        val finished = AtomicBoolean()
        lateinit var timeout: Runnable
        val done: (ExecutionStatus, String) -> Unit = { status, detail ->
            if (finished.compareAndSet(false, true)) {
                handler.removeCallbacks(timeout)
                callback(status, detail)
            }
        }
        timeout = Runnable { done(ExecutionStatus.FAILED, "连接请求超时，未收到系统确认") }
        handler.postDelayed(timeout, (MAX_ATTEMPTS + 10) * RETRY_MS)
        return done
    }

    fun wifi(ctx: Context?, target: String?, direction: String?, ruleId: String?,
        onComplete: (ExecutionStatus, String) -> Unit = { _, _ -> }) {
        val done = completion(onComplete)
        val mode = ConnectionMode.fromCode(direction)
        if (ctx == null || target.isNullOrBlank() ||
            (mode != ConnectionMode.CONNECT && mode != ConnectionMode.DISCONNECT)) {
            done(ExecutionStatus.FAILED, "缺少有效的 Wi-Fi 名称或连接方向")
            return
        }
        val token = ruleId?.let { ruleTokens.getOrPut(it) { Any() } }
        val generation = wifiGeneration.incrementAndGet()
        fun current(): Boolean {
            val valid = wifiGeneration.get() == generation && (ruleId == null || ruleTokens[ruleId] === token)
            if (!valid) done(ExecutionStatus.SKIPPED, "连接请求已被后续动作或规则退出取消")
            return valid
        }
        handler.post {
            if (!current()) return@post
            runCatching {
                val manager = ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager
                if (mode == ConnectionMode.DISCONNECT) {
                    if (ConnectionCatalog.wifiName(manager.connectionInfo?.ssid) == target) {
                        val ok = manager.disconnect()
                        done(if (ok) ExecutionStatus.SUCCESS else ExecutionStatus.FAILED,
                            if (ok) "系统已接受断开请求" else "系统拒绝断开请求")
                    } else done(ExecutionStatus.SUCCESS, "当前未连接此 Wi-Fi，无需断开")
                    return@runCatching
                }
                check(manager.isWifiEnabled || manager.setWifiEnabled(true)) { "无法开启 Wi-Fi" }
                fun connectWhenReady(attempt: Int) {
                    if (!current()) return
                    runCatching ready@{
                        if (!manager.isWifiEnabled) {
                            if (attempt >= MAX_ATTEMPTS) done(ExecutionStatus.FAILED, "等待 Wi-Fi 开启超时")
                            else handler.postDelayed({ connectWhenReady(attempt + 1) }, RETRY_MS)
                            return@ready
                        }
                        val network = manager.configuredNetworks?.firstOrNull {
                            ConnectionCatalog.wifiName(it.SSID) == target
                        } ?: error("目标 Wi-Fi 尚未在系统设置中保存")
                        val method = manager.javaClass.methods.firstOrNull {
                            it.name == "connect" && it.parameterTypes.size == 2 &&
                                it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                                it.parameterTypes[1].name == "android.net.wifi.WifiManager\$ActionListener"
                        } ?: error("系统不支持 Wi-Fi 连接接口")
                        val type = method.parameterTypes[1]
                        val listener = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, called, args ->
                            when (called.name) {
                                "onSuccess" -> { done(ExecutionStatus.SUCCESS, "系统已接受连接请求"); null }
                                "onFailure" -> {
                                    done(ExecutionStatus.FAILED, "系统拒绝连接请求：${args?.firstOrNull()}"); null
                                }
                                "hashCode" -> System.identityHashCode(proxy)
                                "equals" -> proxy === args?.firstOrNull()
                                "toString" -> "AutoTaskLiteWifiListener"
                                else -> null
                            }
                        }
                        method.invoke(manager, network.networkId, listener)
                    }.onFailure { done(ExecutionStatus.FAILED, "Wi-Fi 连接失败：${it.cause?.message ?: it.message}") }
                }
                connectWhenReady(0)
            }.onFailure { done(ExecutionStatus.FAILED, "Wi-Fi 动作失败：${it.message}") }
        }
    }

    fun bluetooth(ctx: Context?, target: String?, direction: String?, ruleId: String?,
        onComplete: (ExecutionStatus, String) -> Unit = { _, _ -> }) {
        val done = completion(onComplete)
        val mode = ConnectionMode.fromCode(direction)
        if (ctx == null || target.isNullOrBlank() ||
            (mode != ConnectionMode.CONNECT && mode != ConnectionMode.DISCONNECT)) {
            done(ExecutionStatus.FAILED, "缺少有效的蓝牙设备或连接方向")
            return
        }
        val token = ruleId?.let { ruleTokens.getOrPut(it) { Any() } }
        val generation = bluetoothGeneration.get()
        val key = target.lowercase(Locale.ROOT)
        handler.post {
            val request = ++nextBluetoothRequest
            bluetoothRequests[key] = request
            fun current(): Boolean {
                val valid = bluetoothGeneration.get() == generation && bluetoothRequests[key] == request &&
                    (ruleId == null || ruleTokens[ruleId] === token)
                if (!valid) done(ExecutionStatus.SKIPPED, "连接请求已被后续动作或规则退出取消")
                return valid
            }
            if (!current()) return@post
            runCatching {
                val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                    ?: error("蓝牙适配器不可用")
                if (!adapter.isEnabled) {
                    if (mode == ConnectionMode.DISCONNECT) {
                        done(ExecutionStatus.SUCCESS, "蓝牙已关闭，无需断开")
                        return@runCatching
                    }
                    check(adapter.enable()) { "无法开启蓝牙" }
                }
                fun connectWhenReady(attempt: Int) {
                    if (!current()) return
                    runCatching ready@{
                        if (!adapter.isEnabled) {
                            if (attempt >= MAX_ATTEMPTS) done(ExecutionStatus.FAILED, "等待蓝牙开启超时")
                            else handler.postDelayed({ connectWhenReady(attempt + 1) }, RETRY_MS)
                            return@ready
                        }
                        val devices = adapter.bondedDevices.toList()
                        val selected = ConnectionCatalog.bluetoothTarget(devices.map {
                            ConnectionTarget(it.address, it.name ?: it.address)
                        }, target) ?: error("设备尚未配对或名称重复，请使用已配对设备的 MAC")
                        val device = devices.single { it.address.equals(selected.value, ignoreCase = true) }
                        changeBluetooth(ctx, adapter, device, mode == ConnectionMode.CONNECT, ::current, done)
                    }.onFailure { done(ExecutionStatus.FAILED, "蓝牙连接失败：${it.cause?.message ?: it.message}") }
                }
                connectWhenReady(0)
            }.onFailure { done(ExecutionStatus.FAILED, "蓝牙动作失败：${it.message}") }
        }
    }

    private fun changeBluetooth(ctx: Context, adapter: BluetoothAdapter, device: BluetoothDevice,
        connect: Boolean, current: () -> Boolean, done: (ExecutionStatus, String) -> Unit) {
        val name = if (connect) "connect" else "disconnect"
        val deviceMethod = Reflect.findMethodByName(device.javaClass, name, 0)
        val adapterMethod = Reflect.findMethodExactIfExists(adapter.javaClass,
            if (connect) "connectAllEnabledProfiles" else "disconnectAllEnabledProfiles",
            BluetoothDevice::class.java)
        if (deviceMethod != null || adapterMethod != null) {
            val result = if (deviceMethod != null) deviceMethod.invoke(device)
                else adapterMethod!!.invoke(adapter, device)
            done(if (result == false) ExecutionStatus.FAILED else ExecutionStatus.SUCCESS,
                if (result == false) "系统拒绝蓝牙请求" else "系统已接受蓝牙请求")
            return
        }
        // Older releases expose commands only through profile proxies.
        listOf(BluetoothProfile.HEADSET, BluetoothProfile.A2DP, 4, 5, 21, 22).forEach { profile ->
            runCatching {
                adapter.getProfileProxy(ctx, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(id: Int, proxy: BluetoothProfile) {
                        try {
                            if (!current()) return
                            val method = Reflect.findMethodExactIfExists(proxy.javaClass, name,
                                BluetoothDevice::class.java) ?: return
                            if (method.invoke(proxy, device) != false) {
                                done(ExecutionStatus.SUCCESS, "系统已接受蓝牙 profile=$id 请求")
                            }
                        } catch (t: Throwable) {
                            XLog.w("Bluetooth profile=$id $name failed: ${t.message}")
                        } finally {
                            adapter.closeProfileProxy(id, proxy)
                        }
                    }
                    override fun onServiceDisconnected(id: Int) = Unit
                }, profile)
            }.onFailure { XLog.w("Bluetooth profile=$profile unavailable: ${it.message}") }
        }
    }
}
