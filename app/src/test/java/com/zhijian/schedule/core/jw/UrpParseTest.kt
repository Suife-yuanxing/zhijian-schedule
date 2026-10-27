package com.zhijian.schedule.core.jw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UrpParseTest {
    /** 新 URP：dateList[].selectCourseList[].timeAndPlaceList[] */
    private val json =
        """
        {"dateList":[{"selectCourseList":[
          {"courseName":"高等数学","attendClassTeacher":"张三","timeAndPlaceList":[
            {"classDay":1,"classSessions":1,"continuingSession":2,
             "classWeek":"11111111111111110000","weekDescription":"1-16周",
             "campusName":"东校区","teachingBuildingName":"教一","classroomName":"101"}
          ]},
          {"courseName":"大学物理","attendClassTeacher":"李四","timeAndPlaceList":[
            {"classDay":3,"classSessions":5,"continuingSession":3,
             "classWeek":"","weekDescription":"2-18周(双)",
             "teachingBuildingName":"理科楼","classroomName":"B203"}
          ]}
        ]}]}
        """.trimIndent()

    @Test
    fun `parse new urp dateList`() {
        val drafts = UrpAdapter.parseJson(json, 20)
        assertEquals(2, drafts.size)

        val math = drafts.first { it.name == "高等数学" }
        assertEquals(1, math.dayOfWeek)
        assertEquals(1, math.startSection)
        assertEquals(2, math.endSection)
        assertEquals("张三", math.teacher)
        assertEquals("东校区教一101", math.room)
        assertEquals(16, math.weekMask.count { it == '1' })

        // classWeek 为空时退回 weekDescription 的双周表达式
        val physics = drafts.first { it.name == "大学物理" }
        assertEquals(3, physics.dayOfWeek)
        assertEquals(5, physics.startSection)
        assertEquals(7, physics.endSection)
        assertTrue(physics.weekMask[0] == '0')
        assertTrue(physics.weekMask[1] == '1')
        assertEquals("理科楼B203", physics.room)
    }

    /** timeAndPlaceList 为 null（未排地点）要跳过而不是崩 */
    @Test
    fun `null place list is skipped`() {
        val broken =
            """{"dateList":[{"selectCourseList":[
                 {"courseName":"待排课程","attendClassTeacher":"王五","timeAndPlaceList":null},
                 {"courseName":"体育","attendClassTeacher":"赵六","timeAndPlaceList":[
                   {"classDay":2,"classSessions":3,"continuingSession":1}]}
               ]}]}"""
        val drafts = UrpAdapter.parseJson(broken, 20)
        assertEquals(1, drafts.size)
        assertEquals("体育", drafts.first().name)
        assertEquals(3, drafts.first().startSection)
    }

    @Test
    fun `html body falls back to table parser`() {
        val html =
            """
            <table>
              <tr><td>节次</td><td>星期一</td><td>星期二</td><td>星期三</td></tr>
              <tr><td>1-2节</td><td>线性代数<br>1-16周<br>[01-02节]<br>孙七<br>教201</td><td></td><td></td></tr>
            </table>
            """.trimIndent()
        val drafts = UrpAdapter.parseBody(html, 20)
        assertEquals(1, drafts.size)
        assertEquals("线性代数", drafts.first().name)
        assertEquals(1, drafts.first().startSection)
    }

    @Test
    fun `showTimetable path is recognized as urp`() {
        assertEquals(
            "urp",
            JwAdapterRegistry
                .forUrl("http://jw.example.edu.cn/academic/manager/coursearrange/showTimetable.do?timetableType=STD")
                ?.id,
        )
    }

    @Test
    fun `academic base path is recognized as urp`() {
        assertEquals("urp", JwAdapterRegistry.forUrl("http://zhjw.example.edu.cn/academic/")?.id)
    }

    @Test
    fun `urp normalize keeps academic base`() {
        assertEquals(
            "http://zhjw.example.edu.cn/academic/",
            UrpAdapter.normalizeBase("http://zhjw.example.edu.cn/academic/manager/coursearrange/showTimetable.do?id=1"),
        )
    }

    @Test
    fun `eams path is not urp`() {
        val adapter = JwAdapterRegistry.forUrl("https://jw.example.edu.cn/eams/login.action")
        assertNotEquals("urp", adapter?.id)
        assertEquals("shuwei_classic", adapter?.id)
    }
}
