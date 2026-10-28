package com.zhijian.schedule.core.jw

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 广西工程职业学院（`gxgcedu.com`）的校级档案回归。
 *
 * 该校官方通知里的入口 `http://edu.gxgcedu.com/` 已不解析（NXDOMAIN），
 * 现行入口是智慧校园统一认证 `https://zhxy.gxgcedu.com/login`：登录后从门户进教务，
 * 教务后端挂在同域 `/academic/` 下（校外可达时直连 :8088）。`/academic/` 正是
 * [UrpAdapter] 的强特征路径，因此协议标注为 `urp`。
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
    fun `gxgcedu portal is catalogued as urp profile`() {
        val profile = SchoolCatalog.byHost("zhxy.gxgcedu.com")
        assertNotNull(profile)
        assertEquals("广西工程职业学院", profile?.name)
        assertEquals(UrpAdapter.id, profile?.adapterId)
        assertEquals("/academic/", profile?.basePath)
        assertEquals("https://zhxy.gxgcedu.com/login", profile?.entry())
    }

    /** 入口是门户页而非课表页，抓取未实测，因此不得标成「已验证」 */
    @Test
    fun `gxgcedu profile stays unverified`() {
        assertFalse(SchoolCatalog.byHost("zhxy.gxgcedu.com")?.verified ?: true)
    }

    /**
     * 缺这行档案时门户首页会被判成「未识别教务系统」，学生以为不支持本校。
     *
     * 路由按两段走：门户 → 校级适配器，课表页 → URP。目录里的 `adapterId = urp`
     * 标注的是**协议**（教务后端确实是 URP），门户本身的识别由 [GuangXiGcAdapter] 承担。
     */
    @Test
    fun `gxgcedu portal home resolves to the school adapter instead of unknown`() {
        assertEquals(GuangXiGcAdapter.id, JwAdapterRegistry.forUrl("https://zhxy.gxgcedu.com/")?.id)
    }

    /** 进了教务后，课表页自身的路径指纹仍应压过目录标注 */
    @Test
    fun `gxgcedu timetable url still wins by path fingerprint`() {
        val url = "https://zhxy.gxgcedu.com/academic/manager/coursearrange/showTimetable.do?timetableType=STD"
        assertEquals(UrpAdapter.id, JwAdapterRegistry.forUrl(url)?.id)
    }

    @Test
    fun `gxgcedu is searchable by full name and pinyin alias`() {
        assertTrue(SchoolCatalog.search("广西工程职业学院").any { it.name == "广西工程职业学院" })
        assertTrue(SchoolCatalog.search("gxgc").any { it.name == "广西工程职业学院" })
    }

    /**
     * 门户地址不带 /academic/，URP 的基路径要靠目录补。
     * 这条失守时，代理给 URP 的抓取会退到 https://zhxy.gxgcedu.com/academic/ 以外的默认值。
     */
    @Test
    fun `urp base path is filled from the catalog for the portal url`() {
        assertEquals(
            "https://zhxy.gxgcedu.com/academic/",
            UrpAdapter.normalizeBase("https://zhxy.gxgcedu.com/login"),
        )
    }
}
