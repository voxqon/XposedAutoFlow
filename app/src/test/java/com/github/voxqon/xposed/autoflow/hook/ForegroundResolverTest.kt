package com.github.voxqon.xposed.autoflow.hook

import android.content.ComponentName
import android.os.Binder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34])
class ForegroundResolverTest {
    private class NamedRecord(val packageName: String)
    private class ComponentRecord(val mActivityComponent: ComponentName)

    @Test
    fun resumesResolveRecordsFromReceiversArgumentsAndBinderTokens() {
        val record = NamedRecord("com.example.camera")
        assertEquals("com.example.camera", ForegroundResolver.packageName(record, emptyArray()) { null })
        assertEquals("com.example.camera", ForegroundResolver.packageName(null, arrayOf(record)) { null })
        val token = Binder()
        assertEquals("com.example.camera", ForegroundResolver.packageName(null, arrayOf(token, true)) {
            if (it === token) record else null
        })
    }

    @Test
    fun componentFallbackReturnsThePackageAndInvalidTokensAreIgnored() {
        val record = ComponentRecord(ComponentName("com.example.home", "com.example.home.Main"))
        assertEquals("com.example.home", ForegroundResolver.packageName(record, emptyArray()) { null })
        assertNull(ForegroundResolver.packageName(null, arrayOf(Binder())) { error("Removed activity") })
    }
}
