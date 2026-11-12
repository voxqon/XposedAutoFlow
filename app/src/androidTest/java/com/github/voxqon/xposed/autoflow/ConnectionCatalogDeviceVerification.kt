package com.github.voxqon.xposed.autoflow

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import com.github.voxqon.xposed.autoflow.app.ConnectionCatalogReceiver
import com.github.voxqon.xposed.autoflow.model.ConnectionCatalog
import com.github.voxqon.xposed.autoflow.model.ConnectionKind
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercises the real private PendingIntent replies without opening an activity or changing radios. */
class ConnectionCatalogDeviceVerification : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val output = StringBuilder()
        var passed = 0
        for (kind in ConnectionKind.entries) {
            val status = Bundle().apply {
                putInt("numtests", ConnectionKind.entries.size)
                putInt("current", kind.ordinal + 1)
                putString("class", ConnectionCatalogDeviceVerification::class.java.name)
                putString("test", "read${kind.name}Catalog")
            }
            sendStatus(1, status)
            val result = runCatching {
                val done = CountDownLatch(1)
                var catalog: ConnectionCatalog? = null
                var cancel: (() -> Unit)? = null
                runOnMainSync {
                    cancel = ConnectionCatalogReceiver.request(targetContext, kind) {
                        catalog = it
                        done.countDown()
                    }
                }
                try {
                    check(done.await(8, TimeUnit.SECONDS)) { "Catalog callback timed out" }
                    val received = checkNotNull(catalog) { "No module bridge replied" }
                    check(received.readable(kind)) { "$kind catalog is unreadable" }
                    val count = received.targets(kind).size
                    output.appendLine("$kind: $count targets")
                    status.putString("stream", "$kind: $count targets\n")
                } finally {
                    runOnMainSync { cancel?.invoke() }
                }
            }
            if (result.isSuccess) {
                passed++
                sendStatus(0, status)
            } else {
                val error = result.exceptionOrNull()
                output.appendLine("$kind failed: $error")
                status.putString("stack", error?.stackTraceToString())
                status.putString("stream", "$kind failed: $error\n")
                sendStatus(-2, status)
            }
        }
        finish(if (passed == ConnectionKind.entries.size) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString("stream", "\n$output\n$passed/${ConnectionKind.entries.size} checks passed\n") })
    }
}
