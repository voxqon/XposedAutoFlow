package com.github.voxqon.xposed.autoflow.store

import android.content.Context
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import com.github.voxqon.xposed.autoflow.app.AppPrefs
import com.github.voxqon.xposed.autoflow.app.RuleConfigProvider
import com.github.voxqon.xposed.autoflow.model.Rule
import com.github.voxqon.xposed.autoflow.model.RuleJson
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34])
class RuleConfigProviderTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun provider() = Robolectric.buildContentProvider(RuleConfigProvider::class.java).create().get()
    private fun rule(id: String = "private") =
        Rule(id, "Private", true, emptyList(), emptyList(), emptyList(), emptyList(), 0, 0)

    @Test(timeout = 10000) fun privateSnapshotSupportsSystemReaderAndRejectsOtherUids() {
        AppPrefs.saveRules(app, listOf(rule()))
        AppPrefs.setShellAllowed(app, true)
        val provider = provider()
        ShadowBinder.setCallingUid(Process.SYSTEM_UID)
        val descriptor = provider.openFile(RuleConfigContract.URI, "r")
        // Robolectric backs pipes with files, so unlike Android the read does not block for the writer.
        val expectedSize = AppPrefs.configurationSnapshot(app).toByteArray(Charsets.UTF_8).size.toLong()
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
        while (descriptor.statSize != expectedSize && System.nanoTime() < deadline) Thread.sleep(5)
        assertEquals(expectedSize, descriptor.statSize)
        val json = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
            .bufferedReader().use { it.readText() }
        val snapshot = JSONObject(json)
        assertTrue(snapshot.getBoolean("shellAllowed"))
        assertEquals(listOf(rule()), RuleJson.rulesFromJson(snapshot.getString("rules")))
        assertThrows(java.io.FileNotFoundException::class.java) { provider.openFile(RuleConfigContract.URI, "w") }
        ShadowBinder.setCallingUid(42_000)
        try {
            assertThrows(SecurityException::class.java) { provider.openFile(RuleConfigContract.URI, "r") }
            assertThrows(SecurityException::class.java) { provider.call("import_legacy", null, Bundle()) }
            assertThrows(SecurityException::class.java) { provider.query(RuleConfigContract.URI, null, null, null, null) }
        } finally {
            ShadowBinder.setCallingUid(Process.myUid())
        }
    }

    @Test fun legacyImportIsValidatedAndCannotOverwriteEvenAnIntentionallyEmptyList() {
        val provider = provider()
        assertFalse(provider.call("import_legacy", null, Bundle().apply { putString("rules", "broken") })
            .getBoolean("imported"))
        val rules = RuleJson.rulesToJson(listOf(rule("old")))
        assertTrue(provider.call("import_legacy", null, Bundle().apply { putString("rules", rules) })
            .getBoolean("imported"))
        assertEquals("old", AppPrefs.loadRules(app).single().id)
        assertFalse(AppPrefs.shellAllowed(app))
        AppPrefs.saveRules(app, emptyList())
        assertFalse(provider.call("import_legacy", null, Bundle().apply { putString("rules", rules) })
            .getBoolean("imported"))
        assertTrue(AppPrefs.loadRules(app).isEmpty())
    }

    @Test fun notificationSettingsAvoidTransferringRulesAndStillRejectOtherUids() {
        val provider = provider()
        ShadowContentResolver.registerProviderInternal("com.github.voxqon.xposed.autoflow.rule-config", provider)
        AppPrefs.setNotifyExecution(app, false)
        AppPrefs.setFloatingBottomBar(app, true)
        val settings = provider.call(RuleConfigContract.GET_SETTINGS, null, null)
        assertFalse(settings.containsKey("rules"))
        assertFalse(settings.getBoolean("notifyExecution"))
        RuleStore.initialize(app)
        assertFalse(RuleStore.notifyExecution())
        assertTrue(RuleStore.floatingBottomBar())
        ShadowBinder.setCallingUid(42_000)
        try {
            assertThrows(SecurityException::class.java) {
                provider.call(RuleConfigContract.GET_SETTINGS, null, null)
            }
        } finally {
            ShadowBinder.setCallingUid(Process.myUid())
        }
    }

    @Test(timeout = 10000) fun runtimeRetainsGoodRulesOnMalformedDataButAcceptsSavedEmptyList() {
        org.robolectric.Shadows.shadowOf(app.contentResolver).registerInputStreamSupplier(RuleConfigContract.URI) {
            java.io.ByteArrayInputStream(AppPrefs.configurationSnapshot(app).toByteArray(Charsets.UTF_8))
        }
        AppPrefs.saveRules(app, listOf(rule()))
        RuleStore.initialize(app)
        RuleStore.invalidate()
        assertEquals(listOf(rule()), RuleStore.load())
        app.getSharedPreferences(RuleStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(RuleStore.KEY_RULES, "malformed").commit()
        RuleStore.invalidate()
        assertEquals(listOf(rule()), RuleStore.load())
        AppPrefs.saveRules(app, emptyList())
        RuleStore.invalidate()
        assertTrue(RuleStore.load().isEmpty())
        assertFalse(RuleStore.shellAllowed())
        AppPrefs.setShellAllowed(app, true)
        assertTrue(RuleStore.shellAllowed())
        org.robolectric.Shadows.shadowOf(app.contentResolver).registerInputStreamSupplier(RuleConfigContract.URI) {
            throw java.io.FileNotFoundException("Provider unavailable")
        }
        assertFalse(RuleStore.shellAllowed())
    }
}
