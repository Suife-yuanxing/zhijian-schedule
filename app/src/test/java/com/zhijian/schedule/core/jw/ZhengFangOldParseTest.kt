package com.zhijian.schedule.core.jw

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

/** 正方旧版（ASP/GBK）：地址指纹、xh 提取、课表/成绩全链路 */
class ZhengFangOldParseTest {
    private val client = OkHttpClient()

    @Test
    fun `old zf pages match exactly`() {
        assertEquals(
            "zhengfang_old",
            JwAdapterRegistry.forUrl("https://jw.xx.edu.cn/default2.aspx")?.id,
        )
        assertEquals(
            "zhengfang_old",
            JwAdapterRegistry.forUrl("https://jw.xx.edu.cn/xs_main.aspx?xh=2023001")?.id,
        )
        assertEquals(
            "zhengfang_old",
            JwAdapterRegistry.forUrl("https://jw.xx.edu.cn/xskbcx.aspx?xh=2023001")?.id,
        )
    }

    @Test
    fun `new zf and other vendors do not collide`() {
        // jwglxt 是新版正方的路径，本来就该判给 zf_new，旧版不能抢
        assertEquals(
            "zhengfang_new",
            JwAdapterRegistry.forUrl("https://jw.xx.edu.cn/jwglxt/xtgl/login_slogin.html")?.id,
        )
        assertNull(JwAdapterRegistry.forUrl("https://jw.xx.edu.cn/"))
    }

    @Test
    fun `xh comes from the login redirect url`() {
        assertEquals("2023001", ZhengFangOldAdapter.xhOf("https://jw.xx.edu.cn/xs_main.aspx?xh=2023001"))
        assertNull(ZhengFangOldAdapter.xhOf("https://jw.xx.edu.cn/xs_main.aspx"))
    }

    @Test
    fun `normalize base keeps deployment subdirectory`() {
        assertEquals(
            "https://jw.xx.edu.cn/",
            ZhengFangOldAdapter.normalizeBase("https://jw.xx.edu.cn/xs_main.aspx?xh=1"),
        )
        assertEquals(
            "https://jw.xx.edu.cn/jwweb/",
            ZhengFangOldAdapter.normalizeBase("https://jw.xx.edu.cn/jwweb/default2.aspx"),
        )
    }

    /** 课表全链路：xskbcx.aspx 大表 → 课程；GB2312 编码的页面也能正确解码 */
    @Test
    fun `schedule fetch replays xskbcx with xh`() =
        runBlocking {
            val server = MockWebServer()
            server.start()
            try {
                server.enqueue(MockResponse().setBody(gbk(scheduleHtml)))

                val result =
                    ZhengFangOldAdapter.fetchSchedule(
                        client,
                        server.url("/xs_main.aspx?xh=2023001").toString(),
                        20,
                    )

                assertEquals(2, result.courses.size)
                val math = result.courses.first { it.name == "高等数学" }
                assertEquals(1, math.dayOfWeek)
                assertEquals(1, math.startSection)
                assertEquals(2, math.endSection)
                assertEquals("张三", math.teacher)
                assertEquals("11111111111111110000", math.weekMask)

                val request = server.takeRequest()
                assertTrue(request.path.orEmpty().startsWith("/xskbcx.aspx"))
                assertTrue(request.path.orEmpty().contains("xh=2023001"))
            } finally {
                server.shutdown()
            }
        }

    /** 成绩全链路：xscjcx.aspx（GBK）→ 通用表头解析 */
    @Test
    fun `grade fetch parses gbk score table`() =
        runBlocking {
            val server = MockWebServer()
            server.start()
            try {
                server.enqueue(MockResponse().setBody(gbk(gradeHtml)))

                val records = ZhengFangOldAdapter.fetchGrades(client, server.url("/xs_main.aspx?xh=2023001").toString())

                assertEquals(2, records.size)
                assertEquals("高等数学", records[0].name)
                assertEquals("89", records[0].score)
                assertEquals(4.0, records[0].credit, 0.001)
                assertEquals("2023-2024-1", records[0].term)
                assertEquals("大学英语", records[1].name)
                assertEquals("不及格", records[1].score)

                val request = server.takeRequest()
                assertTrue(request.path.orEmpty().startsWith("/xscjcx.aspx"))
            } finally {
                server.shutdown()
            }
        }

    /** 登录页（TextBox1+Button1 表单）抛 NeedsLogin 而不是硬解析 */
    @Test
    fun `login form means needs login`() =
        runBlocking {
            val server = MockWebServer()
            server.start()
            try {
                val loginForm =
                    """<html><form><input name="__VIEWSTATE"><input name="TextBox1">""" +
                        """<input name="TextBox2" type="password">""" +
                        """<input name="Button1" value=" 登 录 "></form></html>"""
                server.enqueue(MockResponse().setBody(loginForm))
                try {
                    ZhengFangOldAdapter.fetchSchedule(
                        client,
                        server.url("/xs_main.aspx?xh=2023001").toString(),
                        20,
                    )
                    throw AssertionError("expected NeedsLogin")
                } catch (ignored: JwException.NeedsLogin) {
                    // 预期路径
                }
            } finally {
                server.shutdown()
            }
        }

    private fun gbk(html: String): Buffer = Buffer().writeString(html, Charset.forName("GBK"))

    private val scheduleHtml =
        """
        <html><head><meta charset="gb2312"><title>学生课表</title></head><body>
        <table>
        <tr><td>节次</td><td>星期一</td><td>星期二</td><td>星期三</td></tr>
        <tr><td>1-2节</td><td>高等数学<br>1-16周<br>教1-101<br>张三</td><td>&nbsp;</td><td>大学英语<br>1-16周<br>教2-202<br>李四</td></tr>
        <tr><td>3-4节</td><td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td></tr>
        </table></body></html>
        """.trimIndent()

    private val gradeHtml =
        """
        <html><head><meta charset="gb2312"><title>成绩查询</title></head><body>
        <table id="DataGrid1">
        <tr><td>学年学期</td><td>课程名称</td><td>课程性质</td><td>学分</td><td>成绩</td></tr>
        <tr><td>2023-2024-1</td><td>高等数学</td><td>必修课</td><td>4</td><td>89</td></tr>
        <tr><td>2023-2024-1</td><td>大学英语</td><td>必修课</td><td>3</td><td>不及格</td></tr>
        </table></body></html>
        """.trimIndent()
}
