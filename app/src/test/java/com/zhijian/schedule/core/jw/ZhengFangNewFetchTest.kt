package com.zhijian.schedule.core.jw

import com.zhijian.schedule.core.jw.ZhengFangNewAdapter
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** 正方新版抓取链路：先读页面选中学期，再带 Cookie POST 课表接口 */
class ZhengFangNewFetchTest {
    private lateinit var server: MockWebServer
    private val client =
        OkHttpClient
            .Builder()
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
    fun `fetch reads selected term and posts kbList form`() =
        runBlocking {
            val index =
                """
                <html><body>
                  <select id="xnm"><option value="2025-2026" selected>2025-2026</option></select>
                  <select id="xqm"><option value="3" selected>第一学期</option></select>
                </body></html>
                """.trimIndent()
            val json =
                """
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

    /**
     * 学期名要跟着抓取同步：正方页面本来就有 xnm/xqm，不翻译成人话等于白读。
     * 学期名与开学日期是两件事——kbList 不含日期，所以这里只断言 semester。
     */
    @Test
    fun `fetch reports semester name from selected term`() =
        runBlocking {
            server.enqueue(MockResponse().setBody(indexPage("2026-2027", "12")))
            server.enqueue(MockResponse().setBody(KB_JSON).setHeader("Content-Type", "application/json"))

            val result = ZhengFangNewAdapter.fetchSchedule(client, server.url("/jwglxt/").toString(), 20)

            assertEquals("2026-2027学年第二学期", result.meta?.semester)
            // kbList 无日期：宁可留空让本地值保留，也不能编一个开学日期出来
            assertEquals("", result.meta?.startDate.orEmpty())
            assertEquals(0, result.meta?.totalWeeks)
        }

    /** 兜底候选（按月份推断）不该被当成学期名——只有真正返回课程的那次才算数 */
    @Test
    fun `fallback candidate does not overwrite semester`() =
        runBlocking {
            // 页面没有 selected 项 → 走 termCandidates 兜底；首个候选返回空课表，第二个才命中
            server.enqueue(MockResponse().setBody("<html><body>无下拉</body></html>"))
            server.enqueue(MockResponse().setBody("""{"kbList":[]}""").setHeader("Content-Type", "application/json"))
            server.enqueue(MockResponse().setBody("""{"kbList":[]}""").setHeader("Content-Type", "application/json"))
            server.enqueue(MockResponse().setBody(KB_JSON).setHeader("Content-Type", "application/json"))

            val result = ZhengFangNewAdapter.fetchSchedule(client, server.url("/jwglxt/").toString(), 20)

            assertEquals(1, result.courses.size)
            // 学期名必须出自那次命中，而不是循环里第一个兜底候选。兜底顺序随当前月份变，
            // 所以期望值从 termCandidates 现算，不写死「第一学期」——否则测试只在一半的月份成立。
            val hit = ZhengFangNewAdapter.termCandidates()[1]
            assertEquals(JwSemesterLabel.of(hit.first, hit.second), result.meta?.semester)
        }

    private fun indexPage(
        xnm: String,
        xqm: String,
    ): String =
        """
        <html><body>
          <select id="xnm"><option value="$xnm" selected>$xnm</option></select>
          <select id="xqm"><option value="$xqm" selected>学期</option></select>
        </body></html>
        """.trimIndent()

    private companion object {
        const val KB_JSON =
            """{"kbList":[{"kcmc":"高等数学","xm":"王五","cdmc":"理A401","xqj":"1","jc":"1-2节","zcd":"1-16周"}]}"""
    }
}
