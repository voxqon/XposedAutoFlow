package com.github.voxqon.xposed.autoflow.backup

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class WebDavClientTest {
    @Test
    fun realHttpRequestsUseDepthZeroAuthenticationAndRoundTripTheBackup() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(207).setBody(
                    """<d:multistatus xmlns:d="DAV:"><d:response><d:propstat><d:prop>
                        <d:resourcetype><d:collection/></d:resourcetype></d:prop>
                        <d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"""))
        server.enqueue(MockResponse().setResponseCode(201))
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            val client = WebDavClient(WebDavConfig(server.localUrl("/dav"), "user", "pass"),
                allowLocalHttp = true)
            client.testConnection()
            val data = BackupData(emptyList(), 123)
            client.backup(data)
            val propfind = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("PROPFIND", propfind.method)
            assertEquals("/dav/", propfind.path)
            assertEquals("0", propfind.getHeader("Depth"))
            assertEquals("Basic dXNlcjpwYXNz", propfind.getHeader("Authorization"))
            val put = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("PUT", put.method)
            assertEquals("/dav/${WebDavClient.FILE_NAME}", put.path)
            server.enqueue(MockResponse().setBody(put.body.readUtf8()))
            assertEquals(data, client.restore())
            assertEquals("GET", server.takeRequest(1, TimeUnit.SECONDS)!!.method)
        } finally { server.shutdown() }
    }

    @Test
    fun createsMissingCollectionAndRetriesBackupUpload() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(201))
        server.enqueue(MockResponse().setResponseCode(201))
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            val client = WebDavClient(WebDavConfig(server.localUrl("/dav/AutoTaskLite/")),
                allowLocalHttp = true)
            client.backup(BackupData(emptyList(), 123))
            val firstPut = server.takeRequest(1, TimeUnit.SECONDS)!!
            val mkcol = server.takeRequest(1, TimeUnit.SECONDS)!!
            val retryPut = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("PUT", firstPut.method)
            assertEquals("/dav/AutoTaskLite/autotasklite-backup.json", firstPut.path)
            assertEquals("MKCOL", mkcol.method)
            assertEquals("/dav/AutoTaskLite/", mkcol.path)
            assertEquals("PUT", retryPut.method)
            assertEquals(firstPut.body.readUtf8(), retryPut.body.readUtf8())
        } finally { server.shutdown() }
    }

    @Test
    fun retriesUploadWhenServerReportsTheConfiguredCollectionAlreadyExists() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(409))
        server.enqueue(MockResponse().setResponseCode(405))
        server.enqueue(MockResponse().setResponseCode(204))
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            WebDavClient(WebDavConfig(server.localUrl("/dav/")), allowLocalHttp = true)
                .backup(BackupData(emptyList(), 123))
            assertEquals(listOf("PUT", "MKCOL", "PUT"),
                (0..2).map { server.takeRequest(1, TimeUnit.SECONDS)!!.method })
        } finally { server.shutdown() }
    }

    @Test
    fun jianguoyunRootAddressesUseDavAndKeepTheApplicationPasswordUnchanged() {
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            listOf("https://dav.jianguoyun.com", "https://dav.jianguoyun.com/dav").forEach { url ->
                server.enqueue(collectionResponse())
                assertTrue(server.jianguoyunClient(url).testConnection())
                val request = server.takeRequest(1, TimeUnit.SECONDS)!!
                assertEquals("/dav/", request.path)
                assertEquals("PROPFIND", request.method)
                assertEquals("0", request.getHeader("Depth"))
                assertEquals(okhttp3.Credentials.basic("user@example.com", " app password ", Charsets.UTF_8),
                    request.getHeader("Authorization"))
            }
        } finally { server.shutdown() }
    }

    @Test
    fun jianguoyunPresetRoundTripsWithinItsOwnDirectory() {
        val server = MockWebServer()
        server.enqueue(collectionResponse())
        server.enqueue(MockResponse().setResponseCode(201))
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            val client = server.jianguoyunClient(WebDavConfig.JIANGUOYUN_BACKUP_URL)
            assertTrue(client.testConnection())
            val data = BackupData(emptyList(), 123)
            client.backup(data)
            assertEquals("/dav/AutoTaskLite/", server.takeRequest(1, TimeUnit.SECONDS)!!.path)
            val upload = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("/dav/AutoTaskLite/${WebDavClient.FILE_NAME}", upload.path)
            server.enqueue(MockResponse().setBody(upload.body.readUtf8()))
            assertEquals(data, client.restore())
            assertEquals(upload.path, server.takeRequest(1, TimeUnit.SECONDS)!!.path)
        } finally { server.shutdown() }
    }

    @Test
    fun jianguoyunValidationRejectsWrongPathsAndMissingApplicationCredentials() {
        assertTrue(WebDavConfig(WebDavConfig.JIANGUOYUN_BACKUP_URL).isJianguoyun)
        assertFalse(WebDavConfig("https://dav.jianguoyun.com.example.com/dav/").isJianguoyun)
        listOf(
            WebDavConfig("https://dav.jianguoyun.com/backup/", "user@example.com", "app-password"),
            WebDavConfig(WebDavConfig.JIANGUOYUN_BACKUP_URL, "", "app-password"),
            WebDavConfig(WebDavConfig.JIANGUOYUN_BACKUP_URL, "user@example.com", ""),
        ).forEach { config ->
            assertThrows(IllegalArgumentException::class.java) { WebDavClient(config).validate() }
        }
    }

    @Test
    fun missingBackupDirectoryChecksTheNearestExistingParentWithoutWriting() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(collectionResponse())
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            assertFalse(server.jianguoyunClient(
                "https://dav.jianguoyun.com/dav/AutoTaskLite/daily/").testConnection())
            val requests = (0..2).map { server.takeRequest(1, TimeUnit.SECONDS)!! }
            assertEquals(listOf("/dav/AutoTaskLite/daily/", "/dav/AutoTaskLite/", "/dav/"),
                requests.map { it.path })
            assertTrue(requests.all { it.method == "PROPFIND" && it.getHeader("Depth") == "0" })
        } finally { server.shutdown() }
    }

    @Test
    fun missingJianguoyunDavRootDoesNotProbeOrCreateTheWebsiteRoot() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(409))
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            val client = server.jianguoyunClient("https://dav.jianguoyun.com/dav/")
            assertThrows(IOException::class.java) { client.testConnection() }
            val missingChild = server.jianguoyunClient(WebDavConfig.JIANGUOYUN_BACKUP_URL)
            assertThrows(IOException::class.java) { missingChild.backup(BackupData(emptyList(), 123)) }
            assertEquals(listOf("/dav/", "/dav/AutoTaskLite/${WebDavClient.FILE_NAME}", "/dav/AutoTaskLite/"),
                (0..2).map { server.takeRequest(1, TimeUnit.SECONDS)!!.path })
            assertEquals(3, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test
    fun createsMissingParentsInOrderAndPreservesEncodedDirectoryNames() {
        val server = MockWebServer()
        listOf(409, 409, 201, 201, 201).forEach { server.enqueue(MockResponse().setResponseCode(it)) }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            val client = server.jianguoyunClient("https://dav.jianguoyun.com/dav/任务备份/daily/")
            client.backup(BackupData(emptyList(), 123))
            val requests = (0..4).map { server.takeRequest(1, TimeUnit.SECONDS)!! }
            assertEquals(listOf("PUT", "MKCOL", "MKCOL", "MKCOL", "PUT"), requests.map { it.method })
            val parent = "/dav/%E4%BB%BB%E5%8A%A1%E5%A4%87%E4%BB%BD/"
            assertEquals(listOf("${parent}daily/${WebDavClient.FILE_NAME}", "${parent}daily/",
                parent, "${parent}daily/", "${parent}daily/${WebDavClient.FILE_NAME}"),
                requests.map { it.path })
            assertEquals(requests.first().body.readUtf8(), requests.last().body.readUtf8())
        } finally { server.shutdown() }
    }

    @Test
    fun parentCreationFailureDoesNotRetryTheUploadOrContinueToOtherDirectories() {
        val server = MockWebServer()
        listOf(404, 409, 403).forEach { server.enqueue(MockResponse().setResponseCode(it)) }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            assertThrows(IOException::class.java) {
                server.jianguoyunClient("https://dav.jianguoyun.com/dav/AutoTaskLite/daily/")
                    .backup(BackupData(emptyList(), 123))
            }
            assertEquals(listOf("PUT", "MKCOL", "MKCOL"),
                (0..2).map { server.takeRequest(1, TimeUnit.SECONDS)!!.method })
            assertEquals(3, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test
    fun authenticationAndRateLimitFailuresHaveUsefulMessagesAndAreNotRetried() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(429))
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            val client = server.jianguoyunClient(WebDavConfig.JIANGUOYUN_BACKUP_URL)
            assertTrue(assertThrows(IOException::class.java) { client.testConnection() }
                .message!!.contains("第三方应用密码"))
            assertTrue(assertThrows(IOException::class.java) { client.backup(BackupData(emptyList(), 123)) }
                .message!!.contains("请求过于频繁"))
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test
    fun connectionTestRejectsHtmlAndFailedCollectionPropertiesWithoutProbingParents() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>login</html>"))
        server.enqueue(MockResponse().setResponseCode(207).setBody(
            """<multistatus xmlns="DAV:"><response><propstat><prop><resourcetype><collection/>
                </resourcetype></prop><status>HTTP/1.1 403 Forbidden</status>
                </propstat></response></multistatus>"""))
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            val client = server.jianguoyunClient(WebDavConfig.JIANGUOYUN_BACKUP_URL)
            assertThrows(IOException::class.java) { client.testConnection() }
            assertThrows(IllegalArgumentException::class.java) { client.testConnection() }
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test
    fun rejectsPlaintextAndEmbeddedCredentials() {
        listOf("http://example.com/dav/", "https://user:pass@example.com/dav/",
            "https://example.com/dav/?token=secret").forEach { url ->
            assertThrows(IllegalArgumentException::class.java) { WebDavClient(WebDavConfig(url)).validate() }
        }
    }

    @Test
    fun rejectsAuthenticationErrorsRedirectsHtmlAndCorruptBackups() {
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            val client = WebDavClient(WebDavConfig(server.localUrl("/")), allowLocalHttp = true)
            server.enqueue(MockResponse().setResponseCode(401))
            assertTrue(assertThrows(IOException::class.java) { client.restore() }.message!!.contains("认证失败"))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/secret/")))
            assertTrue(assertThrows(IOException::class.java) { client.restore() }.message!!.contains("重定向"))
            server.enqueue(MockResponse().setResponseCode(500).setBody("<html>not JSON</html>"))
            assertTrue(assertThrows(IOException::class.java) { client.restore() }.message!!.contains("500"))
            server.enqueue(MockResponse().setBody("<html>not JSON</html>"))
            assertThrows(Exception::class.java) { client.restore() }
            server.enqueue(MockResponse().setBody("""{"format":"autotasklite.backup","version":1,"rules":[]}"""))
            assertThrows(Exception::class.java) { client.restore() }
            assertEquals(5, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test
    fun readTimeoutDoesNotProduceAUsableBackup() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            val transport = OkHttpClient.Builder().readTimeout(50, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(false).build()
            val client = WebDavClient(WebDavConfig(server.localUrl("/")),
                transport, allowLocalHttp = true)
            assertThrows(IOException::class.java) { client.restore() }
        } finally { server.shutdown() }
    }

    private fun MockWebServer.localUrl(path: String): String =
        url(path).newBuilder().host("127.0.0.1").build().toString()

    private fun MockWebServer.jianguoyunClient(address: String): WebDavClient {
        // Keep provider URL handling in production code and route only the transport to localhost.
        val transport = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder()
                    .url(localUrl(chain.request().url.encodedPath)).build())
            }.build()
        return WebDavClient(WebDavConfig(address, " user@example.com ", " app password "), transport)
    }

    private fun collectionResponse(): MockResponse = MockResponse().setResponseCode(207).setBody(
        """<?xml version="1.0" encoding="utf-8"?><multistatus xmlns="DAV:"><response><propstat>
            <prop><resourcetype><collection/></resourcetype></prop><status>HTTP/1.1 200 OK</status>
            </propstat></response></multistatus>""")
}
