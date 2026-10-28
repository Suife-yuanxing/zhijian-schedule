package com.zhijian.schedule.core.jw

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

/** 抓课表时顺带把页面上的作息抓回来：不再依赖写死的 08:20 / 14:20。 */
class QiangZhiFetchTest {
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

    private val page =
        """
        <html><body>
        <table id="kbtable">
          <tr><th>&nbsp;</th><th>星期一</th><th>星期二</th><th>星期三</th><th>星期四</th><th>星期五</th></tr>
          <tr>
            <th>第一大节<br>08:00-09:35</th>
            <td><div class="kbcontent">包装材料与检测<br><font title="老师">张峻岭</font><br><font title="周次(节次)">12(周)[01-02-03-04节]</font><br><font title="教室">6A308</font><br></div></td>
            <td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td>
          </tr>
          <tr>
            <th>第二大节<br>09:55-11:30</th>
            <td>&nbsp;</td>
            <td><div class="kbcontent">大学英语<br><font title="老师">李四</font><br><font title="周次(节次)">2-19(周)[03-04节]</font><br><font face="宋体">6B201</font></div></td>
            <td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td>
          </tr>
        </table>
        </body></html>
        """.trimIndent()

    @Test
    fun `fetch returns courses together with the page section times`() =
        runBlocking {
            server.enqueue(MockResponse().setBody(page))

            val result = QiangZhiAdapter.fetchSchedule(client, server.url("/jsxsd/").toString(), 20)

            assertEquals(2, result.courses.size)
            assertEquals(4, result.sectionTimes.size)
            assertEquals("08:00", result.sectionTimes[0].startTime)
            assertEquals("09:35", result.sectionTimes[1].endTime)
            assertEquals("09:55", result.sectionTimes[2].startTime)
            assertEquals("11:30", result.sectionTimes[3].endTime)
        }

    @Test
    fun `fetch without a time column keeps courses and leaves times empty`() =
        runBlocking {
            val noTime =
                """
                <html><body>
                <table id="kbtable">
                  <tr><th>节次</th><th>星期一</th><th>星期二</th><th>星期三</th></tr>
                  <tr><td>1-2节</td>
                    <td>高等数学<br>王五<br>1-16周<br>理A401</td>
                    <td>&nbsp;</td><td>&nbsp;</td></tr>
                </table>
                </body></html>
                """.trimIndent()
            server.enqueue(MockResponse().setBody(noTime))

            val result = QiangZhiAdapter.fetchSchedule(client, server.url("/jsxsd/").toString(), 20)

            assertEquals(1, result.courses.size)
            assertTrue(result.sectionTimes.isEmpty())
        }
}
