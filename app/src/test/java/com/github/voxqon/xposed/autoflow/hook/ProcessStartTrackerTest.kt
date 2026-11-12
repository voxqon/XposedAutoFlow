package com.github.voxqon.xposed.autoflow.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProcessStartTrackerTest {
    private class AppInfo(val packageName: String)
    private class LegacyRecord(var pid: Int, val processName: String, var startSeq: Long = 1)
    private class ModernRecord(private var mPid: Int, val info: AppInfo) {
        fun getPid() = mPid
        fun getStartSeq() = 3L
    }

    @Test
    fun pendingAndFailedStartsDoNotPublishOrConsumeAnEvent() {
        val tracker = ProcessStartTracker()
        val record = LegacyRecord(0, "com.example.camera:worker")
        assertNull(tracker.onStarted(record))
        record.pid = -1
        assertNull(tracker.onStarted(record))
        record.pid = 42
        assertEquals("com.example.camera", tracker.onStarted(record))
    }

    @Test
    fun nestedCompletionCallbacksPublishOnceAndRestartsPublishAgain() {
        val tracker = ProcessStartTracker()
        val record = LegacyRecord(42, "com.example.camera")
        assertEquals("com.example.camera", tracker.onStarted(record))
        assertNull(tracker.onStarted(record))
        record.pid = 43
        assertEquals("com.example.camera", tracker.onStarted(record))
        assertNull(tracker.onStarted(record))
        record.startSeq++
        assertEquals("com.example.camera", tracker.onStarted(record))
    }

    @Test
    fun modernRecordsUseTheirApplicationPackageRatherThanCustomProcessName() {
        val tracker = ProcessStartTracker()
        val record = ModernRecord(42, AppInfo("com.example.camera"))
        assertEquals("com.example.camera", tracker.onStarted(record))
        assertNull(tracker.onStarted(record))
    }

    @Test
    fun separateProcessRecordsAreTrackedIndependently() {
        val tracker = ProcessStartTracker()
        assertEquals("com.example.camera", tracker.onStarted(LegacyRecord(42, "com.example.camera")))
        assertEquals("com.example.camera", tracker.onStarted(LegacyRecord(43, "com.example.camera:worker")))
    }

    @Test
    fun missingIdentityOrPidIsIgnored() {
        val tracker = ProcessStartTracker()
        assertNull(tracker.onStarted(Any()))
        assertNull(tracker.onStarted(LegacyRecord(42, "")))
        assertNull(ProcessStartTracker.packageName(null))
    }
}
