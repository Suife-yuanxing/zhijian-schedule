package com.zhijian.schedule.core.jw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Eams5ParseTest {
    /** eams5 把课表当成 HTML 片段塞在 JSON 字段里，这是三家部署中最稳的一种形态 */
    private val tableHtml =
        """
        <table class="courseTable">
          <tr><th>节次</th><th>星期一</th><th>星期二</th><th>星期三</th></tr>
          <tr><td>1</td><td rowspan="2">数据结构<br>1-16周<br>张三<br>A301</td><td></td><td></td></tr>
          <tr><td>2</td><td></td><td></td></tr>
        </table>
        """.trimIndent()

    @Test
    fun `payload with html inside json is parsed`() {
        val payload = """{"data":${quote(tableHtml)},"periods":[{"index":1}]}"""
        val drafts = Eams5Adapter.parsePayload(payload, 20)
        assertEquals(1, drafts.size)
        assertEquals("数据结构", drafts.first().name)
        assertEquals(1, drafts.first().dayOfWeek)
        assertEquals(1, drafts.first().startSection)
        assertEquals(2, drafts.first().endSection)
        assertEquals("张三", drafts.first().teacher)
        assertEquals("A301", drafts.first().room)
    }

    @Test
    fun `payload that is raw html is parsed`() {
        val drafts = Eams5Adapter.parsePayload(tableHtml, 20)
        assertEquals(1, drafts.size)
        assertEquals("数据结构", drafts.first().name)
    }

    /** 认不出的 JSON 结构必须返回空，由调用方继续下一条路，不能按猜的字段拼课 */
    @Test
    fun `unknown json shape yields nothing`() {
        val payload = """{"lessonIds":[1,2,3],"studentId":"20230001","weekIndex":1}"""
        assertTrue(Eams5Adapter.parsePayload(payload, 20).isEmpty())
    }

    @Test
    fun `eams5 student path wins over shuwei classic`() {
        assertEquals(
            "eams5",
            JwAdapterRegistry
                .forUrl("https://jxglstu.example.edu.cn/eams5-student/for-std/course-table")
                ?.id,
        )
    }

    @Test
    fun `eams5 normalize keeps eams5-student base`() {
        assertEquals(
            "https://jxglstu.example.edu.cn/eams5-student/",
            Eams5Adapter.normalizeBase(
                "https://jxglstu.example.edu.cn/eams5-student/for-std/course-table?semesterId=202",
            ),
        )
    }

    @Test
    fun `student only prefix is kept`() {
        assertEquals(
            "https://jw.example.edu.cn/student/",
            Eams5Adapter.normalizeBase("https://jw.example.edu.cn/student/for-std/course-table"),
        )
    }

    private fun quote(text: String): String =
        "\"" +
            text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n") +
            "\""
}
