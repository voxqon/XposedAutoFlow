package com.github.voxqon.xposed.autoflow.engine

import android.app.BroadcastOptions
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EventBridgeTest {
    @Test
    fun auxiliaryBroadcastOptsIntoTheIdentityRequiredByTheReceiver() {
        var sent: Intent? = null
        var options: Bundle? = null
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun sendBroadcast(intent: Intent, receiverPermission: String?, broadcastOptions: Bundle?) {
                sent = intent
                options = broadcastOptions
            }
        }
        val event = Event(EventTypes.APP_FG, pkg = "com.example.camera")
        EventBridge.send(context, event)
        assertNotNull(sent)
        assertEquals("android", sent?.`package`)
        assertEquals(event, Event.fromJson(requireNotNull(sent?.getStringExtra(EventBridge.EXTRA_JSON))))
        assertTrue(BroadcastOptions.fromBundle(requireNotNull(options)).isShareIdentityEnabled)
    }
}
