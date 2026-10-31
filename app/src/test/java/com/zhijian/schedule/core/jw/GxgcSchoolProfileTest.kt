package com.zhijian.schedule.core.jw

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 广西工程职业学院（`gxgcedu.com`）的校级档案回归。
 *
 * 2026 年 10 月用真实账号实测：教务是智慧校园（CIP 平台）自带的模块，与门户同域同源，
 * 路由形如 `/timetable/timetableView/studentTimetable`；旧的 `/academic/`（URP 特征路径）
 * 已 404，协议因此由 `urp` 改为校级适配器 [GuangXiGcAdapter] 原生说 CIP 协议。
 *
 * 条目放在 [SchoolCatalog] 内置表而不是 `assets/jw/schools.tsv`：后者由
 * `tools/gen_school_catalog.py` 从社区快照重新生成，手写行会被下一次生成覆盖掉。
 */
class GxgcSchoolProfileTest {
    @Before
    fun setUp() {
        SchoolCatalog.resetForTest()
    }

    @After
    fun tearDown() {
        SchoolCatalog.resetForTest()
    }

    @Test
    fun `gxgcedu portal is catalogued as the school adapter`() {
        val profile = SchoolCatalog.byHost("zhxy.gxgcedu.com")
        assertNotNull(profile)
        assertEquals("广西工程职业学院", profile?.name)
        assertEquals(GuangXiGcAdapter.id, profile?.adapterId)
        assertEquals("https://zhxy.gxgcedu.com/login", profile?.entry())
    }

    /** 协议与作息均按 2026-10 实测落地，选校页按「已验证」展示 */
    @Test
    fun `gxgcedu profile is verified by real fetch`() {
        assertTrue(SchoolCatalog.byHost("zhxy.gxgcedu.com")?.verified ?: false)
    }

    /**
     * 缺这行档案时门户首页会被判成「未识别教务系统」，学生以为不支持本校。
     * 全主机归校级适配器：门户、课表页、任意路由都由它自己接管。
     */
    @Test
    fun `gxgcedu portal home resolves to the school adapter instead of unknown`() {
        assertEquals(GuangXiGcAdapter.id, JwAdapterRegistry.forUrl("https://zhxy.gxgcedu.com/")?.id)
        assertEquals(
            GuangXiGcAdapter.id,
            JwAdapterRegistry.forUrl("https://zhxy.gxgcedu.com/timetable/timetableView/studentTimetable")?.id,
        )
    }

    /** URP 的 CATALOG 打分靠目录 adapterId；本校档案指向 gxgc 后，URP 对这台主机不再得分 */
    @Test
    fun `urp never scores via the catalog on the gxgcedu host`() {
        assertEquals(JwMatchScore.NONE, UrpAdapter.match("https://zhxy.gxgcedu.com/"))
        assertEquals(
            JwMatchScore.NONE,
            UrpAdapter.match("https://zhxy.gxgcedu.com/timetable/timetableView/studentTimetable"),
        )
    }

    @Test
    fun `gxgcedu is searchable by full name and pinyin alias`() {
        assertTrue(SchoolCatalog.search("广西工程职业学院").any { it.name == "广西工程职业学院" })
        assertTrue(SchoolCatalog.search("gxgc").any { it.name == "广西工程职业学院" })
    }
}
