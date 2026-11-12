package com.github.voxqon.xposed.autoflow.store

import android.content.ContentValues
import android.os.Process
import com.github.voxqon.xposed.autoflow.app.ExecutionLogProvider
import com.github.voxqon.xposed.autoflow.model.ExecutionLog
import com.github.voxqon.xposed.autoflow.model.ExecutionStatus
import com.github.voxqon.xposed.autoflow.model.ExecutionStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34])
class ExecutionLogStoreTest {
    private fun log(id: String, time: Long = System.currentTimeMillis()) = ExecutionLog(
        id = id, ruleId = "rule", ruleName = "Camera", phase = "Trigger", reason = "screen_on",
        startedAt = time, steps = listOf(ExecutionStep("Wi-Fi", ExecutionStatus.SUBMITTED)))

    @Test
    fun retainsNewestAcrossUpdatesAndImmediatelyTrimsWhenLimitChanges() {
        val store = ExecutionLogStore.get(RuntimeEnvironment.getApplication())
        store.setLimit(3)
        (1..5).forEach { store.append(log("$it", it.toLong())) }
        assertEquals(listOf("5", "4", "3"), store.load().map { it.id })
        store.append(log("4", 4).copy(steps = listOf(ExecutionStep("Wi-Fi", ExecutionStatus.FAILED))))
        assertEquals(3, store.load().size)
        assertEquals(ExecutionStatus.FAILED, store.load()[1].status)
        store.setLimit(1)
        assertEquals(listOf("5"), store.load().map { it.id })
        store.setLimit(0)
        store.append(log("ignored", 100))
        assertEquals(emptyList<ExecutionLog>(), store.load())
        store.setLimit(200)
    }

    @Test
    fun providerAcceptsSystemWritesAndRejectsOtherApplications() {
        val provider = Robolectric.buildContentProvider(ExecutionLogProvider::class.java).create().get()
        ShadowBinder.setCallingUid(Process.SYSTEM_UID)
        provider.insert(ExecutionLogStore.CONTENT_URI, ContentValues().apply { put("json", log("system").toJson()) })
        assertEquals("system", ExecutionLogStore.get(RuntimeEnvironment.getApplication()).load().first().id)
        ShadowBinder.setCallingUid(42_000)
        assertThrows(SecurityException::class.java) {
            provider.insert(ExecutionLogStore.CONTENT_URI, ContentValues().apply { put("json", log("fake").toJson()) })
        }
        assertThrows(SecurityException::class.java) {
            provider.query(ExecutionLogStore.CONTENT_URI, null, null, null, null)
        }
        ShadowBinder.setCallingUid(Process.myUid())
    }

    @Test
    fun clearingLogsDoesNotAllowLateConnectionCallbacksToResurrectOldExecutions() {
        val store = ExecutionLogStore.get(RuntimeEnvironment.getApplication())
        val old = log("old")
        store.append(old)
        store.clear()
        store.append(old.copy(steps = listOf(ExecutionStep("Wi-Fi", ExecutionStatus.SUCCESS))))
        assertEquals(emptyList<ExecutionLog>(), store.load())
    }
}
