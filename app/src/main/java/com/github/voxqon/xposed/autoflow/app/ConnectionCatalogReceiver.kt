package com.github.voxqon.xposed.autoflow.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.github.voxqon.xposed.autoflow.engine.ConnectionCatalogBridge
import com.github.voxqon.xposed.autoflow.model.ConnectionCatalog
import com.github.voxqon.xposed.autoflow.model.ConnectionKind
import java.util.UUID

class ConnectionCatalogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.data?.lastPathSegment ?: return
        val callback = waiting.remove(id) ?: return
        val catalog = ConnectionCatalog.fromJson(intent.getStringExtra(ConnectionCatalogBridge.CATALOG))
        if (catalog != null) {
            val previous = cached(context)
            val merged = catalog.copy(
                wifi = if (catalog.wifiReadable) catalog.wifi else previous.wifi,
                bluetooth = if (catalog.bluetoothReadable) catalog.bluetooth else previous.bluetooth,
            )
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_CATALOG, merged.toJson()).apply()
            callback(merged)
        } else callback(null)
    }

    companion object {
        private const val PREFS = "connection_catalog"
        private const val KEY_CATALOG = "catalog"
        private val handler = Handler(Looper.getMainLooper())
        private val waiting = mutableMapOf<String, (ConnectionCatalog?) -> Unit>()

        fun cached(ctx: Context): ConnectionCatalog =
            ConnectionCatalog.fromJson(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_CATALOG, null)) ?: ConnectionCatalog()

        fun request(ctx: Context, kind: ConnectionKind? = null, callback: (ConnectionCatalog?) -> Unit): () -> Unit {
            val app = ctx.applicationContext
            val pending = mutableMapOf<String, PendingIntent>()
            var active = true
            var latest: ConnectionCatalog? = null
            var fallbackSent = false
            var replies = 0
            lateinit var timeout: Runnable
            lateinit var fallback: Runnable
            fun cancel() {
                active = false
                handler.removeCallbacks(timeout)
                handler.removeCallbacks(fallback)
                pending.forEach { (id, token) ->
                    waiting.remove(id)
                    token.cancel()
                }
                pending.clear()
            }
            fun finish() {
                if (!active) return
                cancel()
                callback(latest)
            }
            fun send(packageName: String) {
                if (!active) return
                val id = UUID.randomUUID().toString()
                val reply = Intent(app, ConnectionCatalogReceiver::class.java)
                    .setData(Uri.parse("autotasklite://connection-catalog/$id"))
                val flags = PendingIntent.FLAG_ONE_SHOT or
                    if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                val token = PendingIntent.getBroadcast(app, 0, reply, flags)
                pending[id] = token
                waiting[id] = { catalog ->
                    if (active) {
                        replies++
                        if (catalog != null) latest = catalog
                        if ((catalog != null && (kind == null || catalog.readable(kind))) || replies >= 2) {
                            finish()
                        } else if (!fallbackSent) {
                            handler.removeCallbacks(fallback)
                            fallback.run()
                        }
                    }
                }
                runCatching {
                    app.sendBroadcast(Intent(ConnectionCatalogBridge.REQUEST).setPackage(packageName)
                        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                        .putExtra(ConnectionCatalogBridge.CALLBACK, token))
                }.onFailure { waiting.remove(id)?.invoke(null) }
            }
            timeout = Runnable { finish() }
            fallback = Runnable {
                if (active && !fallbackSent) {
                    fallbackSent = true
                    send(ConnectionCatalogBridge.SETTINGS_PACKAGE)
                }
            }
            handler.postDelayed(timeout, 5_000L)
            handler.postDelayed(fallback, 1_000L)
            send("android")
            return ::cancel
        }
    }
}
