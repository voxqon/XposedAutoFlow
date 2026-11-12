package com.github.voxqon.xposed.autoflow.backup

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.IOException

/** Optional contract exercise: start postman/simulations/webdav.sim.yaml on port 4517. */
class WebDavSimulationTest {
    @Test
    fun backupClientRoundTripsAgainstPostmanAndRejectsServerFailures() {
        assumeTrue("Set AUTOFLOW_WEBDAV_SIMULATION=1 to run the local simulation",
            System.getenv("AUTOFLOW_WEBDAV_SIMULATION") == "1")
        OkHttpClient().newCall(Request.Builder()
            .url("http://127.0.0.1:4517/webdav/__reset")
            .post(ByteArray(0).toRequestBody()).build()).execute().use {
            assertEquals(204, it.code)
        }
        fun client(path: String, authenticated: Boolean = false) = WebDavClient(
            WebDavConfig("http://127.0.0.1:4517/webdav/$path/",
                if (authenticated) "simulation@example.com" else "",
                if (authenticated) "simulation-app-password" else ""), allowLocalHttp = true)
        val client = client("backup")
        client.testConnection()
        val data = BackupData(emptyList(), 321)
        client.backup(data)
        assertEquals(data, client.restore())
        val missingCollection = client("missing")
        missingCollection.backup(data)
        assertEquals(data, missingCollection.restore())
        val nestedCollection = client("dav/AutoTaskLite/daily", authenticated = true)
        assertFalse(nestedCollection.testConnection())
        nestedCollection.backup(data)
        assertTrue(nestedCollection.testConnection())
        assertEquals(data, nestedCollection.restore())
        val replacement = BackupData(emptyList(), 654)
        nestedCollection.backup(replacement)
        assertEquals(replacement, nestedCollection.restore())
        assertThrows(IOException::class.java) { client("dav").testConnection() }
        assertThrows(IOException::class.java) { client("unauthorized").restore() }
        assertTrue(assertThrows(IOException::class.java) { client("rate-limited").backup(data) }
            .message!!.contains("请求过于频繁"))
        assertThrows(IOException::class.java) { client("failure").backup(data) }
        assertThrows(IOException::class.java) { client("redirect").restore() }
        assertThrows(Exception::class.java) { client("corrupt").restore() }
    }
}
