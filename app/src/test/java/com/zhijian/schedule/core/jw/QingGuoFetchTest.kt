package com.zhijian.schedule.core.jw

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** 青果 / URP 老版 / 树维 classic 共用「每行一小节 + 时间列」的表格，作息要从同一张表抽出来。 */
class QingGuoFetchTest {
    private lateinit var server: MockWebServer
    private val client = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private val page =
        """
        <html><body>
        <table class="Nsb_r_list">
          <tr><th>节次</th><th>时间</th><th>星期一</th><th>星期二</th><th>星期三</th></tr>
          <tr><td>1</td><td>08:00-08:45</td>
            <td>高等数学<br>王五<br>1-16周<br>理A401</td><td></td><td></td></tr>
          <tr><td>2</td><td>08:50-09:35</td><td></td><td></td><td></td></tr>
          <tr><td>3</td><td>10:00-10:45</td><td></td><td>大学英语<br>李四<br>1-16周<br>外语楼203</td><td></td></tr>
        </table>
        </body></html>
        """.trimIndent()

    @Test
    fun `qingguo fetch returns courses and the table section times`() =
        runBlocking {
            server.enqueue(MockResponse().setBody(page))
            server.enqueue(MockResponse().setBody(page))
            server.enqueue(MockResponse().setBody(page))
            server.enqueue(MockResponse().setBody(page))

            val result = QingGuoAdapter.fetchSchedule(client, server.url("/jwweb/").toString(), 20)

            assertEquals(2, result.courses.size)
            assertEquals(3, result.sectionTimes.size)
            assertEquals("08:00", result.sectionTimes[0].startTime)
            assertEquals("09:35", result.sectionTimes[1].endTime)
            assertEquals("10:00", result.sectionTimes[2].startTime)
        }
}
