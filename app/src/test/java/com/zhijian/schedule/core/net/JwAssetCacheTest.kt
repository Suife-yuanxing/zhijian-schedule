package com.zhijian.schedule.core.net

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

/**
 * 教务门户静态资源缓存。fixture 口径：学校门户的静态资源不带任何缓存头，
 * 靠 URL 上的 `?cache_id=` 做版本——缓存按完整 URL 落盘即安全。
 */
class JwAssetCacheTest {
    private val server = MockWebServer()
    private val client = OkHttpClient()
    private lateinit var dir: java.io.File
    private var now = 1_000_000_000L
    private lateinit var cache: JwAssetCache

    @Before
    fun setUp() {
        server.start()
        dir = Files.createTempDirectory("jw-assets").toFile()
        cache = JwAssetCache(client, dir, { now })
    }

    @After
    fun tearDown() {
        cache.joinBackgroundWork()
        server.shutdown()
        dir.deleteRecursively()
    }

    @Test
    fun `stores on fetch and serves on intercept`() {
        server.enqueue(
            MockResponse().setBody("body{}").addHeader("Content-Type", "text/css; charset=utf-8"),
        )
        val url = server.url("/ui/ext/a.css?cache_id=1001").toString()

        val stored = cache.fetchAndStore(url)!!
        assertEquals("text/css", stored.contentType)
        assertEquals("utf-8", stored.charset)
        assertEquals("body{}", String(stored.bytes))

        val hit = cache.intercept(url)!!
        assertEquals("body{}", String(hit.bytes))
        assertEquals("text/css", hit.contentType)
        // 命中走磁盘，不再发网络
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `only known static assets on non-login paths are cached`() {
        assertTrue(cache.shouldCache("https://jw.edu/ui/common.js?cache_id=1001".toHttpUrl()))
        assertTrue(cache.shouldCache("https://jw.edu/ui/fonts/iconfont.css".toHttpUrl()))
        assertTrue(cache.shouldCache("https://jw.edu/ui/logo.png".toHttpUrl()))
        // 主框架页面（无扩展名）不缓存
        assertFalse(cache.shouldCache("https://jw.edu/timetable/timetableView/studentTimetable".toHttpUrl()))
        // 登录相关路径不缓存：登录 JS 里是加密与风控逻辑，不能拿旧的
        assertFalse(cache.shouldCache("https://jw.edu/login.js?cache_id=1001".toHttpUrl()))
        assertFalse(cache.shouldCache("https://jw.edu/ui/login.encrypt.common.js".toHttpUrl()))
        assertFalse(cache.shouldCache("https://jw.edu/Login/assets/icon.png".toHttpUrl()))
    }

    @Test
    fun `expires after ttl`() {
        server.enqueue(MockResponse().setBody("x").addHeader("Content-Type", "text/css"))
        val url = server.url("/ui/a.css").toString()
        cache.fetchAndStore(url)

        now += 7L * 24 * 60 * 60 * 1000 + 1
        assertNull(cache.intercept(url))
        cache.joinBackgroundWork()
    }

    @Test
    fun `torn body counts as miss`() {
        server.enqueue(MockResponse().setBody("x").addHeader("Content-Type", "text/css"))
        val url = server.url("/ui/a.css").toString()
        cache.fetchAndStore(url)
        // 模拟并发写入只换了一半：正文被另一个版本覆盖，长度对不上 meta
        val bodyFile = dir.listFiles()!!.first { !it.name.endsWith(".meta") }
        bodyFile.writeBytes(byteArrayOf(1, 2, 3))
        assertNull(cache.intercept(url))
    }

    @Test
    fun `failed fetch stores nothing`() {
        server.enqueue(MockResponse().setResponseCode(500))
        assertNull(cache.fetchAndStore(server.url("/ui/a.css").toString()))
        assertEquals(0, dir.listFiles()?.size ?: 0)
    }

    @Test
    fun `missing content type falls back to the extension`() {
        server.enqueue(MockResponse().setBody("var a = 1;"))
        val stored = cache.fetchAndStore(server.url("/ui/ext/vue.min.js").toString())!!
        assertEquals("application/javascript", stored.contentType)
        assertNull(stored.charset)
    }
}
