package com.zhijian.schedule.core.jw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdapterStepsTest {
    @Test
    fun `every adapter carries a timetable page hint`() {
        for (adapter in JwAdapterRegistry.all()) {
            assertTrue("${adapter.id} 缺少课表页指引", adapter.steps.timetablePage.isNotBlank())
        }
    }

    /** 菜单路径必须按厂商不同，否则等于没定制 */
    @Test
    fun `timetable hints differ across vendors`() {
        val pages = JwAdapterRegistry.all().map { it.steps.timetablePage }.toSet()
        assertTrue("六个适配器的课表入口文案完全一样，定制没生效", pages.size >= MIN_DISTINCT_HINTS)
    }

    @Test
    fun `qiangzhi points at student timetable menu`() {
        assertTrue(QiangZhiAdapter.steps.timetablePage.contains("学生课表"))
        assertTrue(QiangZhiAdapter.steps.note.contains("菜单"))
    }

    @Test
    fun `zhengfang points at info query menu`() {
        assertTrue(ZhengFangNewAdapter.steps.timetablePage.contains("信息查询"))
        assertTrue(ZhengFangNewAdapter.steps.note.contains("学期"))
    }

    @Test
    fun `qingguo points at left side menu`() {
        assertTrue(QingGuoAdapter.steps.timetablePage.contains("课表查询"))
        assertTrue(QingGuoAdapter.steps.note.contains("左侧"))
    }

    @Test
    fun `urp points at personal management timetable`() {
        assertTrue(UrpAdapter.steps.timetablePage.contains("本学期课表"))
        assertTrue(UrpAdapter.steps.note.contains("showTimetable"))
    }

    @Test
    fun `shuwei classic requires courseTableForStd page`() {
        assertTrue(ShuWeiClassicAdapter.steps.note.contains("courseTableForStd"))
    }

    @Test
    fun `eams5 warns about spa rendering`() {
        assertTrue(Eams5Adapter.steps.note.contains("单页"))
    }

    @Test
    fun `steps follow detected adapter`() {
        assertEquals(
            "学生课表",
            JwAdapterRegistry.forUrl("https://jw.dgpt.edu.cn/jsxsd/")?.steps?.timetablePage,
        )
        assertTrue(
            JwAdapterRegistry
                .forUrl("https://jw.example.edu.cn/jwglxt/")
                ?.steps
                ?.timetablePage
                .orEmpty()
                .contains("信息查询"),
        )
    }

    @Test
    fun `unknown url has no steps`() {
        assertNull(JwAdapterRegistry.forUrl("https://jw.example.edu.cn/")?.steps)
    }

    private companion object {
        const val MIN_DISTINCT_HINTS = 4
    }
}
