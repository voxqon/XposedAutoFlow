package com.github.voxqon.xposed.autoflow.engine

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import com.github.voxqon.xposed.autoflow.model.ConnectionCatalog
import com.github.voxqon.xposed.autoflow.model.ConnectionTarget
import com.github.voxqon.xposed.autoflow.store.RuleStore
import com.github.voxqon.xposed.autoflow.util.XLog

object ConnectionCatalogBridge {
    const val REQUEST = "com.github.voxqon.xposed.autoflow.REQUEST_CONNECTION_CATALOG"
    const val CALLBACK = "callback"
    const val CATALOG = "catalog"
    const val SETTINGS_PACKAGE = "com.android.settings"
    private var registered = false

    @Synchronized
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun register(ctx: Context) {
        if (registered) return
        val thread = HandlerThread("atlt-catalog").apply { start() }
        runCatching {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action != REQUEST) return
                    runCatching {
                        @Suppress("DEPRECATION")
                        val callback = intent.getParcelableExtra<PendingIntent>(CALLBACK) ?: return
                        // The reply capability must belong to this module. Never broadcast the catalog.
                        if (callback.creatorPackage != RuleStore.MODULE_PKG) return
                        @Suppress("DEPRECATION")
                        val moduleUid = ctx.packageManager.getApplicationInfo(RuleStore.MODULE_PKG, 0).uid
                        if (callback.creatorUid != moduleUid) return
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !callback.isBroadcast) return
                        callback.send(ctx, 0, Intent().putExtra(CATALOG, read(ctx).toJson()))
                    }.onFailure { XLog.w("connection catalog request failed: ${it.message}") }
                }
            }
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(receiver, IntentFilter(REQUEST), null, Handler(thread.looper),
                    Context.RECEIVER_EXPORTED)
            } else {
                ctx.registerReceiver(receiver, IntentFilter(REQUEST), null, Handler(thread.looper))
            }
            registered = true
            XLog.i("connection catalog bridge registered in ${ctx.packageName}")
        }.onFailure {
            thread.quitSafely()
            XLog.w("connection catalog bridge unavailable: ${it.message}")
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun read(ctx: Context): ConnectionCatalog {
        val wifi = runCatching {
            val manager = ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val saved = manager.configuredNetworks ?: error("Saved Wi-Fi list unavailable")
            saved.mapNotNull { config ->
                ConnectionCatalog.wifiName(config.SSID)?.let { ConnectionTarget(it) }
            }.distinctBy { it.value }.sortedBy { it.name }
        }.onFailure { XLog.w("saved Wi-Fi list unavailable: ${it.message}") }
        val bluetooth = runCatching {
            val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                ?: error("Bluetooth adapter unavailable")
            // An off adapter may return an empty list even when bonds exist.
            check(adapter.isEnabled) { "Bluetooth is off" }
            adapter.bondedDevices.map { device ->
                ConnectionTarget(device.address, device.name?.takeIf { it.isNotBlank() } ?: device.address)
            }.sortedWith(compareBy({ it.name }, { it.value }))
        }.onFailure { XLog.w("bonded Bluetooth list unavailable: ${it.message}") }
        return ConnectionCatalog(
            wifi = wifi.getOrDefault(emptyList()),
            bluetooth = bluetooth.getOrDefault(emptyList()),
            wifiReadable = wifi.isSuccess,
            bluetoothReadable = bluetooth.isSuccess,
        )
    }
}
