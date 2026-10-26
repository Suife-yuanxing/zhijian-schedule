package com.zhijian.schedule.core.jw

import com.zhijian.schedule.core.jw.ZhengFangNewAdapter
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 正方新版抓取链路：先读页面选中学期，再带 Cookie POST 课表接口 */
class ZhengFangNewFetchTest {

    private lateinit var server: MockWebServer
    private val client = OkHttpClient.Builder()
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `fetch reads selected term and posts kbList form`() = runBlocking {
        val index = """
            <html><body>
              <select id="xnm"><option value="2025-2026" selected>2025-2026</option></select>
              <select id="xqm"><option value="3" selected>第一学期</option></select>
            </body></html>
        """.trimIndent()
        val json = """
            {"kbList":[{"kcmc":"高等数学","xm":"王五","cdmc":"理A401","xqj":"1","jc":"1-2节","zcd":"1-16周"}]}
        """.trimIndent()
        server.enqueue(MockResponse().setBody(index))
        server.enqueue(MockResponse().setBody(json).setHeader("Content-Type", "application/json"))

        val base = server.url("/jwglxt/").toString()
        val drafts = ZhengFangNewAdapter.fetch(client, base, 20)

        assertEquals(1, drafts.size)
        assertEquals("高等数学", drafts.first().name)

        val indexRequest = server.takeRequest()
        assertTrue(indexRequest.path.orEmpty().startsWith("/jwglxt/kbcx/xskbcx_cxXskbcxIndex.html"))
        assertTrue(indexRequest.path.orEmpty().contains("gnmkdm=N2151"))

        val postRequest = server.takeRequest()
        assertEquals("POST", postRequest.method)
        assertTrue(postRequest.path.orEmpty().startsWith("/jwglxt/kbcx/xskbcx_cxXsKb.html"))
        assertEquals("xnm=2025-2026&xqm=3&kzlx=ck", postRequest.body.readUtf8())
    }
}
