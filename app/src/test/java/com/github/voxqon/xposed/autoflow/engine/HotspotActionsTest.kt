package com.github.voxqon.xposed.autoflow.engine

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34])
class HotspotActionsTest {
    class Request(val type: Int) {
        class Builder(private val type: Int) {
            fun build() = Request(type)
        }
    }

    interface Callback {
        fun onTetheringStarted()
        fun onTetheringFailed(code: Int)
    }

    class Manager(private val error: Int? = null, private val respond: Boolean = true) {
        var stopped = false
        var type = -1
        fun startTethering(type: Int, executor: Executor, callback: Callback) {
            error("The request overload must be selected")
        }
        fun startTethering(request: Request, executor: Executor, callback: Callback) {
            type = request.type
            if (respond) executor.execute {
                if (error == null) callback.onTetheringStarted() else callback.onTetheringFailed(error)
            }
        }
        fun stopTethering(type: Int) { this.type = type; stopped = true }
    }

    @Test
    fun usesModularRequestAndReportsCallbackResult() {
        val manager = Manager()
        assertEquals(true, HotspotActions.request(manager, true))
        assertEquals(0, manager.type)
        assertEquals(true, HotspotActions.request(manager, false))
        assertTrue(manager.stopped)
    }

    @Test
    fun failuresIncludeSystemErrorAndMissingCallbacksTimeOut() {
        val error = assertThrows(IllegalStateException::class.java) {
            HotspotActions.request(Manager(error = 14), true)
        }
        assertTrue(error.message!!.contains("14"))
        assertThrows(IllegalStateException::class.java) {
            HotspotActions.request(Manager(respond = false), true, timeoutMs = 1)
        }
        assertNull(HotspotActions.request(Any(), true))
        assertNull(HotspotActions.request(Any(), false))
    }
}
