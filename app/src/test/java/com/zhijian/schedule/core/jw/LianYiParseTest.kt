package com.zhijian.schedule.core.jw

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 联奕教务：lyuapServer CAS 指纹、React 课表页的尽力而为解析 */
class LianYiParseTest {
    private val client = OkHttpClient()

    @Test
    fun `cas login page matches exactly`() {
        assertEquals(
            "lianyi",
            JwAdapterRegistry.forUrl("https://cas.scau.edu.cn/lyuapServer/login?service=x")?.id,
        )
        assertNull(JwAdapterRegistry.forUrl("https://jw.example.edu.cn/"))
    }

    /** 服务端渲染出课表 DOM 时可解析（React 容器 + 星期 × 节次表格） */
    @Test
    fun `ssr timetable page parses`() =
        runBlocking {
            val server = MockWebServer()
            server.start()
            try {
                server.enqueue(MockResponse().setBody(ttHtml))
                val result =
                    LianYiAdapter.fetchSchedule(client, server.url("/app-components-CourseTimeTable").toString(), 20)
                assertEquals(1, result.courses.size)
                assertEquals("有机化学", result.courses[0].name)
                assertEquals(2, result.courses[0].dayOfWeek)
                assertEquals("11111111000000000000", result.courses[0].weekMask)
            } finally {
                server.shutdown()
            }
        }

    /** 客户端渲染的 JS 壳：明确报「抓不到」，不是静默空结果 */
    @Test
    fun `spa shell fails with clear message`() =
        runBlocking {
            val server = MockWebServer()
            server.start()
            try {
                val shell = """<html><body><div id="app"></div><script src="app.js"></script></body></html>"""
                server.enqueue(MockResponse().setBody(shell))
                try {
                    LianYiAdapter.fetchSchedule(client, server.url("/courseTable").toString(), 20)
                    throw AssertionError("expected ParseFailed")
                } catch (ignored: JwException.ParseFailed) {
                    // 预期路径
                }
            } finally {
                server.shutdown()
            }
        }

    /** CAS 登录页抛 NeedsLogin */
    @Test
    fun `login page means needs login`() =
        runBlocking {
            val server = MockWebServer()
            server.start()
            try {
                server.enqueue(MockResponse().setBody(loginHtml))
                try {
                    LianYiAdapter.fetchSchedule(client, server.url("/lyuapServer/login").toString(), 20)
                    throw AssertionError("expected NeedsLogin")
                } catch (ignored: JwException.NeedsLogin) {
                    // 预期路径
                }
            } finally {
                server.shutdown()
            }
        }

    private val loginHtml =
        """
        <html><head><title>统一身份认证</title></head><body>
        <form action="/lyuapServer/login"><input type="password" name="password"><button>登录</button></form>
        </body></html>
        """.trimIndent()

    private val ttHtml =
        """
        <html><body><div class="app-components-CourseTimeTable-styles-timeTable">
        <table>
        <tr><td>节次</td><td>星期一</td><td>星期二</td><td>星期三</td></tr>
        <tr><td>3-4节</td><td>&nbsp;</td><td>有机化学<br>1-8周<br>实验楼201<br>孙八</td><td>&nbsp;</td></tr>
        </table></div></body></html>
        """.trimIndent()
}
