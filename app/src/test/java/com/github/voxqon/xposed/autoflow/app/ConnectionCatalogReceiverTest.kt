package com.github.voxqon.xposed.autoflow.app

import android.app.PendingIntent
import android.content.Intent
import android.os.Looper
import com.github.voxqon.xposed.autoflow.engine.ConnectionCatalogBridge
import com.github.voxqon.xposed.autoflow.model.ConnectionCatalog
import com.github.voxqon.xposed.autoflow.model.ConnectionTarget
import com.github.voxqon.xposed.autoflow.model.ConnectionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34])
class ConnectionCatalogReceiverTest {
    @Test
    fun privateReplyUpdatesTheCacheAndUnreadableRadiosKeepTheirPreviousIdentifiers() {
        val app = RuntimeEnvironment.getApplication()
        val first = ConnectionCatalog(
            wifi = listOf(ConnectionTarget("Home")),
            bluetooth = listOf(ConnectionTarget("AA:BB:CC:DD:EE:FF", "Headphones")),
            wifiReadable = true, bluetoothReadable = true,
        )
        fun reply(catalog: ConnectionCatalog): ConnectionCatalog? {
            var result: ConnectionCatalog? = null
            ConnectionCatalogReceiver.request(app) { result = it }
            val request = shadowOf(app).broadcastIntents.last()
            assertEquals("android", request.`package`)
            @Suppress("DEPRECATION")
            val callback = request.getParcelableExtra<PendingIntent>(ConnectionCatalogBridge.CALLBACK)!!
            callback.send(app, 0, Intent().putExtra(ConnectionCatalogBridge.CATALOG, catalog.toJson()))
            shadowOf(Looper.getMainLooper()).idle()
            return result
        }
        assertEquals(first, reply(first))
        val second = ConnectionCatalog(wifi = emptyList(), wifiReadable = true)
        val result = reply(second)
        assertEquals(emptyList<ConnectionTarget>(), result?.wifi)
        assertEquals(first.bluetooth, result?.bluetooth)
        assertEquals(result, ConnectionCatalogReceiver.cached(app))
    }

    @Test
    fun cancellingARequestPreventsALateTimeoutFromReachingTheDialog() {
        var calls = 0
        val cancel = ConnectionCatalogReceiver.request(RuntimeEnvironment.getApplication()) { calls++ }
        cancel()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        assertEquals(0, calls)
        var result: ConnectionCatalog? = ConnectionCatalog()
        ConnectionCatalogReceiver.request(RuntimeEnvironment.getApplication()) { result = it }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        assertNull(result)
    }

    @Test
    fun anUnavailableSystemBridgeFallsBackToSettingsAndOnlyDeliversOnce() {
        val app = RuntimeEnvironment.getApplication()
        var calls = 0
        var result: ConnectionCatalog? = null
        ConnectionCatalogReceiver.request(app, ConnectionKind.WIFI) { calls++; result = it }
        val primary = shadowOf(app).broadcastIntents.last()
        assertEquals("android", primary.`package`)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_100))
        val backup = shadowOf(app).broadcastIntents.last()
        assertEquals(ConnectionCatalogBridge.SETTINGS_PACKAGE, backup.`package`)
        val expected = ConnectionCatalog(wifi = listOf(ConnectionTarget("Home")), wifiReadable = true)
        @Suppress("DEPRECATION")
        val token = backup.getParcelableExtra<PendingIntent>(ConnectionCatalogBridge.CALLBACK)!!
        token.send(app, 0, Intent().putExtra(ConnectionCatalogBridge.CATALOG, expected.toJson()))
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        assertEquals(1, calls)
        assertEquals(expected, result)
    }

    @Test
    fun anUnreadableRadioTriesSettingsInsteadOfEndingTheRequestEarly() {
        val app = RuntimeEnvironment.getApplication()
        var result: ConnectionCatalog? = null
        val cancel = ConnectionCatalogReceiver.request(app, ConnectionKind.BLUETOOTH) { result = it }
        @Suppress("DEPRECATION")
        val primary = shadowOf(app).broadcastIntents.last()
            .getParcelableExtra<PendingIntent>(ConnectionCatalogBridge.CALLBACK)!!
        primary.send(app, 0, Intent().putExtra(ConnectionCatalogBridge.CATALOG, ConnectionCatalog().toJson()))
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(result)
        val backup = shadowOf(app).broadcastIntents.last()
        assertEquals(ConnectionCatalogBridge.SETTINGS_PACKAGE, backup.`package`)
        val expected = ConnectionCatalog(bluetooth = listOf(ConnectionTarget("AA:BB:CC:DD:EE:FF", "Headphones")),
            bluetoothReadable = true)
        @Suppress("DEPRECATION")
        val token = backup.getParcelableExtra<PendingIntent>(ConnectionCatalogBridge.CALLBACK)!!
        token.send(app, 0, Intent().putExtra(ConnectionCatalogBridge.CATALOG, expected.toJson()))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(expected, result)
        cancel()
    }
}
