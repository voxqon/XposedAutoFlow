package com.github.voxqon.xposed.autoflow.backup

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import java.io.StringReader
import java.util.concurrent.TimeUnit

data class WebDavConfig(val url: String = "", val username: String = "", val password: String = "") {
    val isJianguoyun: Boolean
        get() = url.trim().toHttpUrlOrNull()?.host == JIANGUOYUN_HOST

    companion object {
        const val JIANGUOYUN_HOST = "dav.jianguoyun.com"
        const val JIANGUOYUN_BACKUP_URL = "https://dav.jianguoyun.com/dav/AutoTaskLite/"
    }
}

class WebDavClient(
    private val config: WebDavConfig,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build(),
    private val allowLocalHttp: Boolean = false,
) {
    private fun directory(): HttpUrl {
        var url = config.url.trim().toHttpUrl()
        require(url.isHttps || (allowLocalHttp && url.host in setOf("localhost", "127.0.0.1", "::1"))) {
            "请使用 HTTPS WebDAV 目录地址"
        }
        require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
            "目录地址不能包含账号、密码、查询参数或片段"
        }
        require(!config.username.contains(':') && !config.username.contains('\n') &&
            !config.username.contains('\r')) { "用户名无效" }
        if (config.isJianguoyun) {
            if (url.encodedPath == "/") url = url.newBuilder().encodedPath("/dav/").build()
            require(url.encodedPath == "/dav" || url.encodedPath.startsWith("/dav/")) {
                "坚果云目录地址需以 https://dav.jianguoyun.com/dav/ 开头"
            }
            require(config.username.isNotBlank()) { "请输入坚果云账号邮箱" }
            require(config.password.isNotEmpty()) { "请输入坚果云第三方应用密码" }
        }
        return if (url.encodedPath.endsWith('/')) url else url.newBuilder().addPathSegment("").build()
    }

    fun validate() { directory() }

    private fun request(url: HttpUrl): Request.Builder = Request.Builder().url(url)
        .header("User-Agent", "AutoTaskLite/1.0 WebDAV")
        .apply {
            if (config.username.isNotEmpty() || config.password.isNotEmpty()) {
                header("Authorization", Credentials.basic(config.username.trim(), config.password, Charsets.UTF_8))
            }
        }

    /** Returns false when an existing parent was checked and the backup directory is still missing. */
    fun testConnection(): Boolean {
        val collection = directory()
        var current = collection
        while (true) {
            if (readCollection(current)) return current == collection
            current = parentDirectory(current) ?: fail(404)
        }
    }

    private fun readCollection(url: HttpUrl): Boolean {
        val body = """<?xml version="1.0" encoding="utf-8"?>
            |<d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/></d:prop></d:propfind>"""
            .trimMargin().toRequestBody("application/xml; charset=utf-8".toMediaType())
        client.newCall(request(url).header("Depth", "0").method("PROPFIND", body).build())
            .execute().use { response ->
                if (response.code == 404) return false
                if (response.code != 207) fail(response.code)
                val xml = readLimited(response.body?.byteStream() ?: throw IOException("服务器返回空内容"), 256 * 1024)
                require(isCollection(xml)) { "此地址不是可读取的 WebDAV 目录" }
                return true
            }
    }

    fun backup(data: BackupData) {
        val json = BackupCodec.encode(data)
        require(json.toByteArray(Charsets.UTF_8).size <= BackupCodec.MAX_BYTES) { "备份内容超过 5 MiB" }
        val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
        val collection = directory()
        val target = collection.newBuilder().addPathSegment(FILE_NAME).build()
        val first = put(target, body)
        if (first in setOf(200, 201, 204)) return
        if (first !in setOf(404, 409)) fail(first)

        if (parentDirectory(collection) == null) fail(first)
        createCollection(collection)
        val retry = put(target, body)
        if (retry !in setOf(200, 201, 204)) fail(retry)
    }

    private fun put(target: HttpUrl, body: okhttp3.RequestBody): Int =
        client.newCall(request(target).put(body).build()).execute().use { it.code }

    private fun parentDirectory(url: HttpUrl): HttpUrl? {
        if (url.encodedPath == "/" || (config.isJianguoyun && url.encodedPath == "/dav/")) return null
        return url.newBuilder().removePathSegment(url.pathSegments.size - 2).build()
    }

    private fun createCollection(collection: HttpUrl) {
        val missing = mutableListOf<HttpUrl>()
        var current = collection
        while (true) {
            val code = mkcol(current)
            if (code in setOf(200, 201, 204, 405)) break
            if (code !in setOf(404, 409)) fail(code)
            // Walk upward only after a missing-parent response, then create children in order.
            val parent = parentDirectory(current) ?: fail(code)
            if (parentDirectory(parent) == null) fail(code)
            missing += current
            current = parent
        }
        for (child in missing.asReversed()) {
            val code = mkcol(child)
            if (code !in setOf(200, 201, 204, 405)) fail(code)
        }
    }

    private fun mkcol(url: HttpUrl): Int =
        client.newCall(request(url).method("MKCOL", null).build()).execute().use { it.code }

    fun restore(): BackupData {
        val target = directory().newBuilder().addPathSegment(FILE_NAME).build()
        client.newCall(request(target).get().build()).execute().use {
            if (it.code != 200) fail(it.code)
            val stream = it.body?.byteStream() ?: throw IOException("备份文件为空")
            return BackupCodec.decode(readLimited(stream, BackupCodec.MAX_BYTES))
        }
    }

    private fun fail(code: Int): Nothing = throw IOException(when (code) {
        301, 302, 303, 307, 308 -> "服务器要求重定向，请填写最终的 HTTPS WebDAV 目录地址"
        401 -> if (config.isJianguoyun) "坚果云认证失败，请检查账号邮箱和第三方应用密码（非登录密码）"
            else "认证失败，请检查用户名和密码或应用专用密码"
        403 -> "账号没有此目录的读写权限"
        404 -> "目录或备份文件不存在"
        409 -> "备份目录不存在，请先在服务器创建目录"
        413, 507 -> "服务器空间或文件大小限制不足"
        423 -> "备份文件已被锁定，请稍后重试"
        429 -> "WebDAV 请求过于频繁，请稍后重试"
        503 -> "WebDAV 服务暂时不可用，请稍后重试（HTTP 503）"
        else -> "WebDAV 请求失败（HTTP $code）"
    })

    companion object {
        const val FILE_NAME = "autotasklite-backup.json"

        private fun readLimited(input: java.io.InputStream, max: Int): String = input.use {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val read = it.read(buffer)
                if (read < 0) break
                require(output.size() + read <= max) { "服务器返回的文件过大" }
                output.write(buffer, 0, read)
            }
            output.toString("UTF-8")
        }

        private fun isCollection(xml: String): Boolean {
            val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
            parser.setInput(StringReader(xml))
            var collection = false
            var propstatDepth = -1
            var statusOk = false
            var inResourceType = false
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.DOCDECL) error("不支持含 DTD 的 WebDAV 响应")
                if (parser.eventType == XmlPullParser.START_TAG && parser.namespace == "DAV:") {
                    when (parser.name) {
                        "propstat" -> { propstatDepth = parser.depth; statusOk = false; collection = false }
                        "resourcetype" -> inResourceType = true
                        "collection" -> if (inResourceType) collection = true
                        "status" -> if (propstatDepth >= 0) {
                            statusOk = parser.nextText().split(' ').getOrNull(1)?.toIntOrNull() in 200..299
                        }
                    }
                }
                if (parser.eventType == XmlPullParser.END_TAG && parser.namespace == "DAV:") {
                    if (parser.name == "resourcetype") inResourceType = false
                    if (parser.name == "propstat" && parser.depth == propstatDepth) {
                        if (statusOk && collection) return true
                        propstatDepth = -1
                    }
                }
                parser.nextToken()
            }
            return false
        }
    }
}
